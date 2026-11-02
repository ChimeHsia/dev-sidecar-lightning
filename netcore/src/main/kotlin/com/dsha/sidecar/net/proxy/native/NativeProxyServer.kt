package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.http.*
import com.dsha.sidecar.net.proxy.tls.LeafCertificateCache
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

internal fun releaseConcurrentSockets(sockets: Iterable<Socket>, release: (Socket) -> Unit) {
    // Concurrent removal can invalidate the size shortcut used by toList().
    for (socket in sockets) release(socket)
}

data class NativeProxyOptions(
    val listenAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    val port: Int = 0,
    val backlog: Int = 32,
    val workers: Int = 4,
    val queuedConnections: Int = 16,
    val connectTimeoutMillis: Int = 5_000,
    val handshakeTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 15_000,
    val exchangeTimeoutMillis: Int = 60_000,
    val stopTimeoutMillis: Int = 5_000,
    val maxRequestsPerConnection: Int = 100,
    val limits: Http1Limits = Http1Limits(maxHeadBytes = 65_536, maxBodyBytes = Long.MAX_VALUE),
    val downstreamProtocols: List<String> = listOf("TLSv1.3", "TLSv1.2"),
    /** Zero permits idle/long-lived upgraded connections; worker and buffer bounds still apply. */
    val duplexIdleTimeoutMillis: Int = 0,
    val duplexTimeoutMillis: Int = 0,
    /** Half-close and discard grace prevents unread upload bytes from resetting the final response. */
    val earlyResponseCloseTimeoutMillis: Int = 250,
) {
    init {
        require(listenAddress.isLoopbackAddress && port in 0..65535)
        require(backlog > 0 && workers in 1..256 && queuedConnections in 1..4096)
        require(listOf(connectTimeoutMillis, handshakeTimeoutMillis, readTimeoutMillis,
            exchangeTimeoutMillis, stopTimeoutMillis, maxRequestsPerConnection).all { it > 0 })
        require(downstreamProtocols.isNotEmpty() &&
            downstreamProtocols.all { it == "TLSv1.2" || it == "TLSv1.3" })
        require(duplexIdleTimeoutMillis >= 0 && duplexTimeoutMillis >= 0)
        require(earlyResponseCloseTimeoutMillis > 0)
    }
}

enum class NativeProxyState { NEW, STARTING, LISTENING, FAILED, STOPPED }

/** Single-use JVM engine. Binding is synchronous; start returns only after the socket is listening. */
class NativeProxyServer(
    private val resolver: RouteResolver,
    private val certificates: LeafCertificateCache?,
    val options: NativeProxyOptions = NativeProxyOptions(),
    private val transformer: NativeRequestTransformer = NativeRequestTransformer { it.head },
    private val tlsConnector: NativeTlsDialer = NativeTlsConnector(),
    private val exchangePolicy: NativeExchangePolicy = NativeExchangePolicy.Passthrough,
) : Closeable {
    private val lifecycle = Any()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val sequence = AtomicInteger()
    private val threads = ThreadFactory { task ->
        Thread(task, "native-proxy-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ThreadPoolExecutor? = null
    private var helpers: ThreadPoolExecutor? = null
    private var timer: ScheduledExecutorService? = null

    @Volatile var state: NativeProxyState = NativeProxyState.NEW
        private set
    @Volatile var failure: Throwable? = null
        private set
    @Volatile var localAddress: InetSocketAddress? = null
        private set
    val activeSocketCount: Int get() = sockets.size

    fun start(): InetSocketAddress = synchronized(lifecycle) {
        check(state == NativeProxyState.NEW) { "Server is single-use: $state" }
        state = NativeProxyState.STARTING
        val bound = ServerSocket()
        try {
            bound.reuseAddress = false
            bound.bind(InetSocketAddress(options.listenAddress, options.port), options.backlog)
            listener = bound
            workers = ThreadPoolExecutor(options.workers, options.workers, 0, TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(options.queuedConnections), threads)
            helpers = ThreadPoolExecutor(options.workers, options.workers, 0, TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(options.workers), threads)
            timer = ScheduledThreadPoolExecutor(1, threads).apply { removeOnCancelPolicy = true }
            localAddress = bound.localSocketAddress as InetSocketAddress
            state = NativeProxyState.LISTENING
            acceptThread = threads.newThread { acceptLoop(bound) }.also { it.start() }
            localAddress!!
        } catch (error: Throwable) {
            runCatching { bound.close() }
            workers?.shutdownNow()
            shutdownHelpers()
            timer?.shutdownNow()
            failure = error
            state = NativeProxyState.FAILED
            throw error
        }
    }

    private fun acceptLoop(bound: ServerSocket) {
        try {
            while (state == NativeProxyState.LISTENING) {
                val socket = bound.accept()
                try {
                    socket.soTimeout = options.readTimeoutMillis
                    socket.tcpNoDelay = true
                    register(socket)
                    workers!!.execute {
                        val scope = SocketScope(socket)
                        try {
                            serve(scope)
                        } finally {
                            scope.finishDownstreamTls()
                            scope.close()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    release(socket)
                } catch (error: Exception) {
                    release(socket)
                    if (state == NativeProxyState.LISTENING && error !is IOException) throw error
                }
            }
        } catch (error: Exception) {
            synchronized(lifecycle) {
                if (state == NativeProxyState.LISTENING) {
                    failure = error
                    state = NativeProxyState.FAILED
                    runCatching { bound.close() }
                    releaseConcurrentSockets(sockets, ::release)
                    workers?.shutdownNow()
                    shutdownHelpers()
                    timer?.shutdownNow()
                }
            }
        }
    }

    private fun register(socket: Socket) = synchronized(lifecycle) {
        if (state != NativeProxyState.LISTENING) {
            socket.close()
            throw IOException("Server stopped")
        }
        sockets.add(socket)
        Unit
    }

    private fun release(socket: Socket) {
        runCatching { socket.close() }
        sockets.remove(socket)
    }

    private fun shutdownHelpers() {
        helpers?.shutdownNow()?.forEach { task ->
            if (task is Future<*>) task.cancel(false)
        }
    }

    private inner class SocketScope(val downstream: Socket) : Closeable {
        private val owned = ConcurrentHashMap.newKeySet<Socket>().apply { add(downstream) }
        var downstreamTls: SSLSocket? = null
        fun open(route: NativeProxyRoute, beforeConnect: (Socket) -> Unit = {}): Socket {
            val socket = Socket()
            owned.add(socket)
            try {
                register(socket)
                beforeConnect(socket)
                try {
                    socket.connect(route.destination, options.connectTimeoutMillis)
                } catch (failure: IOException) {
                    if (state == NativeProxyState.LISTENING) {
                        runCatching { route.socketOutcome(NativeSocketOutcome.CONNECT_FAILED) }
                    }
                    throw failure
                }
                runCatching { route.socketOutcome(NativeSocketOutcome.CONNECTED) }
                socket.soTimeout = options.readTimeoutMillis
                socket.tcpNoDelay = true
                return socket
            } catch (error: Exception) {
                drop(socket)
                throw error
            }
        }
        fun drop(socket: Socket) {
            release(socket)
            owned.remove(socket)
        }
        fun finishDownstreamTls() {
            val secure = downstreamTls ?: return
            val abort = try {
                timer!!.schedule({ runCatching { downstream.close() } },
                    options.stopTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                runCatching { downstream.close() }
                null
            }
            try {
                // Normal completion sends close_notify; the raw transport still bounds a blocked close.
                runCatching { secure.close() }
            } finally {
                abort?.cancel(false)
            }
        }
        override fun close() {
            releaseConcurrentSockets(owned, ::drop)
        }
    }

    private inner class Deadline(scope: SocketScope, millis: Int) : Closeable {
        val expired = AtomicBoolean()
        private val task = timer!!.schedule({
            expired.set(true)
            scope.close()
        }, millis.toLong(), TimeUnit.MILLISECONDS)
        override fun close() { task.cancel(false) }
    }

    private fun serve(scope: SocketScope) {
        var downstream: Socket = scope.downstream
        var reader = Http1Reader(downstream.getInputStream(), options.limits)
        var bound: NativeAuthority? = null
        var count = 0
        while (state == NativeProxyState.LISTENING && count < options.maxRequestsPerConnection) {
            val output = downstream.getOutputStream()
            val headExpired = AtomicBoolean()
            val headDeadline = timer!!.schedule({
                headExpired.set(true)
                runCatching { scope.downstream.close() }
            }, options.exchangeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            val request = try {
                reader.readRequest() ?: return
            } catch (_: SocketTimeoutException) {
                error(output, 408)
                return
            } catch (_: Http1Exception) {
                error(output, 400)
                return
            } catch (_: IOException) {
                return
            } finally {
                headDeadline.cancel(false)
            }
            if (headExpired.get()) return
            count++
            try {
                val validated = validateRequest(request.head, bound)
                val negotiation = NativeHttpNegotiation.request(request)
                if (request.head.method == "CONNECT" && request.head.headers.values("Expect").isNotEmpty()) {
                    throw NativeClientFailure(417)
                }
                if (request.head.method == "CONNECT") {
                    if (request.framing != Http1BodyFraming.None &&
                        request.framing != Http1BodyFraming.Fixed(0)) throw NativeClientFailure(400)
                    val decision = requestDecision(validated)
                    if (decision is NativeRequestDecision.LocalResponse) {
                        localResponse(scope, downstream, output, validated, decision)
                        return
                    }
                    decision as NativeRequestDecision.Forward
                    val selected = trustedForward(validated, request, negotiation, decision)
                    if (selected.authority != validated.authority) {
                        throw NativeUpstreamFailure("CONNECT identity rewrites require a separate tunnel contract")
                    }
                    reader.transferBody(DISCARD)
                    val route = decision.route ?: resolver.resolve(selected)
                    when (route.connectPolicy) {
                        ConnectPolicy.BLOCK -> { error(output, 403); return }
                        ConnectPolicy.TUNNEL -> {
                            tunnel(scope, reader.handoff(), output, route)
                            return
                        }
                        ConnectPolicy.MITM -> {
                            val cache = certificates ?: throw IOException("MITM certificate cache required")
                            val leaf = cache.get(validated.authority.host)
                            // This overload retains all pre-read ClientHello bytes from Http1Reader.
                            output.write(CONNECT_OK)
                            output.flush()
                            try {
                                Deadline(scope, options.handshakeTimeoutMillis).use {
                                    val secure = leaf.sslContext.socketFactory.createSocket(
                                        scope.downstream, reader.handoff(), true,
                                    ) as SSLSocket
                                    scope.downstreamTls = secure
                                    secure.useClientMode = false
                                    secure.enabledProtocols = options.downstreamProtocols
                                        .filter { it in SUPPORTED_TLS && it in secure.supportedProtocols }.toTypedArray()
                                        .also { check(it.isNotEmpty()) }
                                    secure.soTimeout = options.handshakeTimeoutMillis
                                    secure.startHandshake()
                                    secure.soTimeout = options.readTimeoutMillis
                                    downstream = secure
                                }
                            } catch (_: Exception) {
                                return // HTTP cannot be written after the CONNECT success / TLS handoff.
                            }
                            reader = Http1Reader(downstream.getInputStream(), options.limits)
                            bound = validated.authority
                        }
                    }
                } else {
                    val last = count == options.maxRequestsPerConnection
                    if (!forward(scope, downstream, reader, output, request, validated, negotiation, last)) return
                }
            } catch (bad: NativeClientFailure) {
                error(output, bad.status)
                return
            } catch (_: Http1Exception) {
                error(output, 400)
                return
            } catch (_: SocketTimeoutException) {
                error(output, 504)
                return
            } catch (_: Exception) {
                error(output, 502)
                return
            }
        }
    }

    private fun forward(
        scope: SocketScope,
        clientSocket: Socket,
        downstream: Http1Reader,
        output: OutputStream,
        request: Http1Request,
        validated: NativeProxyRequest,
        negotiation: NativeHttpNegotiation,
        last: Boolean,
    ): Boolean {
        var committed = false
        var upload: NativeUpload? = null
        var responseWait: NativeResponseWait? = null
        var upstreamAttempt: NativeUpstreamAttempt? = null
        var abandonUpload = false
        val upstreamRaw = AtomicReference<Socket?>()
        val writingClient = AtomicBoolean()
        val timedOut = AtomicBoolean()
        val clientOutput = object : FilterOutputStream(output) {
            override fun write(value: Int) {
                writingClient.set(true)
                try { out.write(value) } finally { writingClient.set(false) }
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writingClient.set(true)
                try { out.write(bytes, offset, length) } finally { writingClient.set(false) }
            }
            override fun flush() {
                writingClient.set(true)
                try { out.flush() } finally { writingClient.set(false) }
            }
        }
        // The response worker remains free to send a timeout while the upload helper is blocked.
        val deadline = timer!!.schedule({
            timedOut.set(true)
            upstreamRaw.get()?.let { runCatching { it.close() } }
            if (writingClient.get()) runCatching { scope.downstream.close() }
        }, options.exchangeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        try {
            var transformed: Http1RequestHead
            var checked: NativeProxyRequest
            val outgoing: Http1RequestHead
            try {
                transformed = transformer.transform(validated)
                checked = validateRequest(transformed, validated.connectAuthority)
                if (checked.authority != validated.authority || checked.secure != validated.secure ||
                    transformed.method != request.head.method ||
                    Http1Framing.request(transformed, options.limits) != request.framing
                ) throw NativeClientFailure(400)
                if (NativeHttpNegotiation.request(Http1Request(transformed, request.framing)) != negotiation) {
                    throw NativeClientFailure(400)
                }
                hopHeaders(transformed.headers, client = true, upgrade = negotiation.upgrade != null)
                Http1HeadCodec.writeRequest(DISCARD, transformed, options.limits)
            } catch (_: Http1Exception) {
                throw NativeClientFailure(400)
            }
            val decision = requestDecision(checked)
            if (decision is NativeRequestDecision.LocalResponse) {
                localResponse(scope, clientSocket, clientOutput, checked, decision)
                return false
            }
            decision as NativeRequestDecision.Forward
            checked = trustedForward(checked, request, negotiation, decision)
            transformed = checked.head
            try {
                val target = when (val target = transformed.target) {
                    is Http1Target.Absolute -> Http1Target.Origin(target.originForm)
                    else -> target
                }
                val headers = hopHeaders(transformed.headers, client = true,
                    upgrade = negotiation.upgrade != null).fields.filterNot {
                    it.name.equals("Host", true) ||
                        (it.name.equals("Expect", true) &&
                            (!negotiation.expectContinue || !hasRequestBody(request.framing)))
                } + Http1Header("Host", " ${checked.authority.wire}") +
                    Http1Header("Connection", if (negotiation.upgrade == null) " close" else " Upgrade")
                outgoing = transformed.copy(target = target, version = "HTTP/1.1", headers = Http1Headers(headers))
                Http1HeadCodec.writeRequest(DISCARD, outgoing, options.limits)
            } catch (failure: Http1Exception) {
                throw NativeUpstreamFailure("Invalid policy forwarding head", failure)
            }
            val persistent = !last &&
                Http1Persistence.isPersistent(request.head.version, request.head.headers) &&
                Http1Persistence.isPersistent(transformed.version, transformed.headers)
            val route = decision.route ?: resolver.resolve(checked)
            if (checked.secure && route.tls == null) throw IOException("HTTPS route requires explicit TLS policy")
            val raw = scope.open(route) { upstreamRaw.set(it) }
            if (timedOut.get()) throw SocketTimeoutException("Exchange deadline")
            val upstream = route.tls?.let { policy ->
                val handshakeExpired = AtomicBoolean()
                val abort = timer!!.schedule({
                    handshakeExpired.set(true)
                    runCatching { raw.close() }
                }, options.handshakeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                try {
                    tlsConnector.connect(raw, policy, options.handshakeTimeoutMillis)
                } catch (failure: Exception) {
                    if (handshakeExpired.get()) throw SocketTimeoutException("TLS handshake deadline")
                    throw failure
                } finally {
                    abort.cancel(false)
                }
            } ?: raw
            upstream.soTimeout = options.readTimeoutMillis
            val upstreamOutput = upstream.getOutputStream()
            Http1HeadCodec.writeRequest(upstreamOutput, outgoing, options.limits)
            upstreamOutput.flush()
            val wait = NativeResponseWait(upstream, raw, timer!!, options.readTimeoutMillis, timedOut,
                hasRequestBody(request.framing), negotiation.expectContinue)
            responseWait = wait
            if (hasRequestBody(request.framing)) {
                upload = NativeUpload(downstream, upstreamOutput, raw, helpers!!,
                    negotiation.expectContinue, wait::bodyFinished)
            } else {
                downstream.transferBody(DISCARD)
            }
            val responses = Http1Reader(upstream.getInputStream(), options.limits)
            val attempt = NativeUpstreamAttempt(responses, wait) { scope.drop(raw) }
            upstreamAttempt = attempt
            var informational = 0
            while (true) {
                val upstreamResponse = attempt.readHead(transformed.method)
                val response = policyResponse(checked, upstreamResponse)
                if (timedOut.get()) throw SocketTimeoutException("Response head deadline")
                upload?.throwClientFailure()
                if (response.head.status == 101) {
                    validateUpstreamUpgrade(response, negotiation.upgrade)
                    upload?.settleFinalResponse()
                    if (upload?.bodyComplete?.get() == false) {
                        throw NativeUpstreamFailure("Upstream upgraded before request body completed")
                    }
                    upload?.await(options.stopTimeoutMillis)
                    val head = response.head.copy(headers = Http1Headers(
                        hopHeaders(response.head.headers, client = false, upgrade = true).fields +
                            Http1Header("Connection", " Upgrade")))
                    Http1HeadCodec.writeResponse(DISCARD, head, transformed.method, options.limits)
                    if (timedOut.get()) throw SocketTimeoutException("Exchange deadline")
                    wait.close()
                    duplex(scope, downstream.handoff(), clientOutput, responses.handoff(),
                        upstreamOutput, clientSocket, upstream) {
                        committed = true
                        Http1HeadCodec.writeResponse(clientOutput, head, transformed.method, options.limits)
                        clientOutput.flush()
                        deadline.cancel(false)
                        if (timedOut.get()) throw SocketTimeoutException("Upgrade handshake deadline")
                    }
                    return false
                }
                if (response.framing == Http1BodyFraming.Handoff) throw NativeUpstreamFailure("Unexpected handoff")
                val interim = response.head.status in 100..199
                if (interim && ++informational > 16) throw IOException("Too many informational responses")
                if (!interim) {
                    upload?.settleFinalResponse()
                    upload?.throwClientFailure()
                    abandonUpload = upload?.bodyComplete?.get() == false
                    if (abandonUpload) upload?.cancel()
                    val entity = NativeResponseEntityReader(responses, response.framing, options.limits.maxBodyBytes)
                    val prepared = exchangePolicy.onResponseEntity(checked, response.head, entity)
                    if (prepared != null) {
                        val head = preparedResponse(request.head, response.head, prepared, entity.complete)
                        if (timedOut.get()) throw SocketTimeoutException("Exchange deadline")
                        committed = true
                        Http1HeadCodec.writeResponse(clientOutput, head, transformed.method, options.limits)
                        clientOutput.write(prepared.body)
                        clientOutput.flush()
                        if (!abandonUpload) upload?.await(options.stopTimeoutMillis)
                        return false
                    }
                    if (entity.consumed) throw NativeUpstreamFailure("Consumed response cannot stream unchanged")
                }
                val decode = request.head.version == "HTTP/1.0" && response.framing == Http1BodyFraming.Chunked
                val close = !persistent || abandonUpload || decode ||
                    response.framing == Http1BodyFraming.CloseDelimited
                var responseHeaders = hopHeaders(response.head.headers, client = false).fields
                if (decode) responseHeaders = responseHeaders.filterNot {
                    it.name.equals("Transfer-Encoding", true) || it.name.equals("Trailer", true)
                }
                if (!interim) responseHeaders = responseHeaders +
                    Http1Header("Connection", if (close) " close" else " keep-alive")
                val head = response.head.copy(version = request.head.version, headers = Http1Headers(responseHeaders))
                if (timedOut.get()) throw SocketTimeoutException("Exchange deadline")
                Http1HeadCodec.writeResponse(DISCARD, head, transformed.method, options.limits)
                if (!interim) committed = true
                Http1HeadCodec.writeResponse(clientOutput, head, transformed.method, options.limits)
                responses.transferBody(clientOutput, if (decode) Http1ChunkMode.DECODE else Http1ChunkMode.PRESERVE)
                clientOutput.flush()
                if (response.head.status == 100) {
                    wait.continued()
                    upload?.allowBody()
                }
                if (!interim) {
                    if (!abandonUpload) upload?.await(options.stopTimeoutMillis)
                    return !close
                }
            }
        } catch (failure: Exception) {
            if (!committed && !scope.downstream.isClosed) {
                val clientFailure = upload?.failure?.get() as? NativeClientFailure
                val status = when {
                    clientFailure != null -> clientFailure.status
                    failure is NativeClientFailure -> failure.status
                    failure is SocketTimeoutException || timedOut.get() -> 504
                    else -> 502
                }
                error(clientOutput, status)
            }
            return false
        } finally {
            upload?.cancel()
            if (upstreamAttempt != null) {
                upstreamAttempt.close()
            } else {
                responseWait?.close()
                upstreamRaw.get()?.let(scope::drop)
            }
            if (upload != null) {
                if (!upload.bodyComplete.get() || abandonUpload) {
                    finishEarlyResponse(scope, clientSocket, upload)
                } else if (state == NativeProxyState.LISTENING) {
                    runCatching { upload.await(options.stopTimeoutMillis) }
                }
            }
            deadline.cancel(false)
        }
    }

    private fun requestDecision(request: NativeProxyRequest): NativeRequestDecision = try {
        exchangePolicy.onRequest(request)
    } catch (failure: Exception) {
        throw NativeUpstreamFailure("Request policy failed", failure)
    }

    private fun trustedForward(
        original: NativeProxyRequest,
        request: Http1Request,
        negotiation: NativeHttpNegotiation,
        decision: NativeRequestDecision.Forward,
    ): NativeProxyRequest = try {
        val head = decision.head
        // A trusted rewrite can select a new logical authority, but cannot change transport scheme,
        // method, body interpretation, or negotiate a different protocol behind the client's back.
        val rewrittenBound = if (original.secure && head.method != "CONNECT") {
            val hosts = head.headers.values("Host")
            if (hosts.size != 1) throw Http1Exception("Exactly one rewritten Host is required")
            NativeAuthority.parse(hosts.single(), 443)
        } else null
        val checked = validateRequest(head, rewrittenBound)
        if (head.method != original.head.method || checked.secure != original.secure ||
            Http1Framing.request(head, options.limits) != request.framing ||
            NativeHttpNegotiation.request(Http1Request(head, request.framing)) != negotiation
        ) throw Http1Exception("Policy changed method, scheme, framing or negotiation")
        hopHeaders(head.headers, client = false, upgrade = negotiation.upgrade != null)
        Http1HeadCodec.writeRequest(DISCARD, head, options.limits)
        checked.copy(connectAuthority = original.connectAuthority)
    } catch (failure: Exception) {
        throw NativeUpstreamFailure("Invalid trusted request decision", failure)
    }

    private fun policyResponse(request: NativeProxyRequest, response: Http1Response): Http1Response = try {
        val head = exchangePolicy.onResponseHead(request, response.head)
        val protected = if (response.head.status == 101) listOf("Content-Length", "Transfer-Encoding",
            "Upgrade", "Sec-WebSocket-Accept", "Sec-WebSocket-Protocol", "Sec-WebSocket-Extensions")
            else listOf("Content-Length", "Transfer-Encoding", "Content-Encoding")
        if (head.status != response.head.status || head.version != response.head.version ||
            Http1Framing.response(head, request.head.method, options.limits) != response.framing ||
            protected.any { head.headers.values(it) != response.head.headers.values(it) }
        ) throw Http1Exception("Response policy changed framing or protocol identity")
        hopHeaders(head.headers, client = false, upgrade = head.status == 101)
        Http1HeadCodec.writeResponse(DISCARD, head, request.head.method, options.limits)
        response.copy(head = head)
    } catch (failure: Exception) {
        throw NativeUpstreamFailure("Invalid response policy head", failure)
    }

    private fun preparedResponse(
        request: Http1RequestHead,
        upstream: Http1ResponseHead,
        prepared: NativePreparedResponse,
        complete: Boolean,
    ): Http1ResponseHead {
        val supplied = prepared.head
        if (!complete || request.method == "HEAD" || upstream.status in listOf(204, 205, 304) ||
            supplied.status != upstream.status || supplied.version != upstream.version ||
            prepared.body.size > options.limits.maxBodyBytes ||
            supplied.headers.values("Content-Length") != listOf(prepared.body.size.toString()) ||
            supplied.headers.values("Transfer-Encoding").isNotEmpty() ||
            supplied.headers.values("Trailer").isNotEmpty()
        ) throw NativeUpstreamFailure("Invalid prepared response")
        val head = supplied.copy(version = request.version, headers = Http1Headers(
            hopHeaders(supplied.headers, client = false).fields + Http1Header("Connection", " close")))
        Http1HeadCodec.writeResponse(DISCARD, head, request.method, options.limits)
        return head
    }

    private fun localResponse(
        scope: SocketScope,
        client: Socket,
        output: OutputStream,
        request: NativeProxyRequest,
        response: NativeRequestDecision.LocalResponse,
    ) {
        val body = response.body
        val head = try {
            val supplied = response.head
            if (supplied.status !in 200..599 || (request.head.method == "CONNECT" && supplied.status in 200..299) ||
                body.size > 65_536 || body.size > options.limits.maxBodyBytes ||
                supplied.headers.values("Transfer-Encoding").isNotEmpty() ||
                (supplied.status in listOf(204, 304) && body.isNotEmpty())
            ) throw Http1Exception("Invalid local response status, body or quota")
            val lengths = supplied.headers.values("Content-Length")
            if (lengths.isNotEmpty() && lengths != listOf(body.size.toString())) {
                throw Http1Exception("Local Content-Length does not match body")
            }
            val headers = hopHeaders(supplied.headers, client = false).fields
                .filterNot { it.name.equals("Content-Length", true) } +
                (if (supplied.status in listOf(204, 304)) emptyList()
                    else listOf(Http1Header("Content-Length", " ${body.size}"))) +
                Http1Header("Connection", " close")
            supplied.copy(version = request.head.version, headers = Http1Headers(headers)).also {
                Http1HeadCodec.writeResponse(DISCARD, it, request.head.method, options.limits)
            }
        } catch (failure: Exception) {
            throw NativeUpstreamFailure("Invalid local policy response", failure)
        }
        try {
            Deadline(scope, options.exchangeTimeoutMillis).use {
                Http1HeadCodec.writeResponse(output, head, request.head.method, options.limits)
                if (request.head.method != "HEAD") output.write(body)
                output.flush()
            }
        } catch (_: IOException) {
            return // A partial local response cannot be followed by a second HTTP error.
        }
        if (hasRequestBody(Http1Framing.request(request.head, options.limits))) {
            finishEarlyResponse(scope, client, null)
        }
    }

    private fun finishEarlyResponse(scope: SocketScope, client: Socket, upload: NativeUpload?) {
        if (state != NativeProxyState.LISTENING || scope.downstream.isClosed) return
        val abort = try {
            timer!!.schedule({ runCatching { scope.downstream.close() } },
                options.earlyResponseCloseTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            runCatching { scope.downstream.close() }
            return
        }
        try {
            // Send ordered EOF after the final body, before aborting a still-writing peer.
            runCatching { client.shutdownOutput() }
            upload?.await(options.stopTimeoutMillis)
            // Only after the helper has relinquished input can the worker discard terminal data.
            if (!scope.downstream.isClosed) {
                client.soTimeout = options.earlyResponseCloseTimeoutMillis
                val buffer = ByteArray(options.limits.bufferBytes)
                val input = client.getInputStream()
                while (input.read(buffer) >= 0) { /* discard during the bounded close grace */ }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // The peer or grace deadline closed the terminal connection.
        } finally {
            abort.cancel(false)
            runCatching { scope.downstream.close() }
        }
    }

    private fun hopHeaders(headers: Http1Headers, client: Boolean, upgrade: Boolean = false): Http1Headers {
        val nominated = headers.tokens("Connection").map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        if (nominated.any { it in setOf("host", "content-length", "transfer-encoding", "trailer", "expect",
                "sec-websocket-key", "sec-websocket-accept", "sec-websocket-version", "sec-websocket-protocol") }) {
            if (client) throw NativeClientFailure(400) else throw IOException("Hop field hides message framing")
        }
        val remove = nominated + setOf("connection", "proxy-connection", "keep-alive", "upgrade",
            "proxy-authenticate", "proxy-authorization", "te")
        return Http1Headers(headers.fields.filterNot {
            it.name.lowercase(java.util.Locale.ROOT) in remove && !(upgrade && it.name.equals("Upgrade", true))
        })
    }

    private fun tunnel(
        scope: SocketScope,
        input: InputStream,
        output: OutputStream,
        route: NativeProxyRoute,
    ) {
        require(route.tls == null) { "TUNNEL is an opaque TCP route" }
        val raw = scope.open(route)
        var established = false
        try {
            duplex(scope, input, output, raw.getInputStream(), raw.getOutputStream(), scope.downstream, raw) {
                output.write(CONNECT_OK)
                output.flush()
                established = true
            }
        } catch (failure: Exception) {
            if (!established) throw failure
        } finally {
            scope.drop(raw)
        }
    }

    private fun duplex(
        scope: SocketScope,
        clientInput: InputStream,
        clientOutput: OutputStream,
        upstreamInput: InputStream,
        upstreamOutput: OutputStream,
        clientSocket: Socket,
        upstreamSocket: Socket,
        begin: () -> Unit,
    ) {
        clientSocket.soTimeout = options.duplexIdleTimeoutMillis
        upstreamSocket.soTimeout = options.duplexIdleTimeoutMillis
        val ready = CountDownLatch(1)
        val started = AtomicBoolean()
        val helper = helpers!!.submit {
            try {
                ready.await()
                if (started.get()) {
                    pump(upstreamInput, clientOutput)
                    clientSocket.shutdownOutput()
                }
            } catch (_: Exception) {
                scope.close()
            }
        }
        val deadline = if (options.duplexTimeoutMillis > 0) Deadline(scope, options.duplexTimeoutMillis) else null
        try {
            begin()
            started.set(true)
            ready.countDown()
            pump(clientInput, upstreamOutput)
            upstreamSocket.shutdownOutput()
            helper.get()
        } finally {
            deadline?.close()
            ready.countDown()
            scope.close()
            if (state == NativeProxyState.LISTENING) {
                runCatching { helper.get(options.stopTimeoutMillis.toLong(), TimeUnit.MILLISECONDS) }
            }
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(options.limits.bufferBytes)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun error(output: OutputStream, status: Int) {
        val reason = when (status) {
            400 -> "Bad Request"
            403 -> "Forbidden"
            408 -> "Request Timeout"
            417 -> "Expectation Failed"
            504 -> "Gateway Timeout"
            else -> "Bad Gateway"
        }
        runCatching {
            val message = "$status $reason\n".toByteArray(Charsets.US_ASCII)
            val body = if (message.size <= options.limits.maxBodyBytes) message else byteArrayOf()
            Http1HeadCodec.writeResponse(output, Http1ResponseHead("HTTP/1.1", status, reason,
                Http1Headers(listOf(Http1Header("Content-Length", " ${body.size}"),
                    Http1Header("Connection", " close")))), "GET", options.limits)
            output.write(body)
            output.flush()
        }
    }

    override fun close() {
        val accept: Thread?
        synchronized(lifecycle) {
            if (state == NativeProxyState.STOPPED) return
            state = NativeProxyState.STOPPED
            runCatching { listener?.close() }
            releaseConcurrentSockets(sockets, ::release)
            workers?.shutdownNow()
            shutdownHelpers()
            timer?.shutdownNow()
            accept = acceptThread
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.stopTimeoutMillis.toLong())
        try {
            if (accept != Thread.currentThread()) accept?.join(options.stopTimeoutMillis.toLong())
            for (pool in listOfNotNull(workers, helpers, timer)) {
                val remaining = deadline - System.nanoTime()
                if (remaining > 0) pool.awaitTermination(remaining, TimeUnit.NANOSECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val CONNECT_OK = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
        private val SUPPORTED_TLS = setOf("TLSv1.2", "TLSv1.3")
        private val DISCARD = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
    }
}
