// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.proxy.http.Http1ChunkMode
import com.dsha.sidecar.net.proxy.http.Http1Header
import com.dsha.sidecar.net.proxy.http.Http1Headers
import com.dsha.sidecar.net.proxy.http.Http1HeadCodec
import com.dsha.sidecar.net.proxy.http.Http1Limits
import com.dsha.sidecar.net.proxy.http.Http1Reader
import com.dsha.sidecar.net.proxy.http.Http1RequestHead
import com.dsha.sidecar.net.proxy.http.Http1Target
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/** Bootstrap/pins for the provider service, never for the DNS question name. */
fun interface NativeDnsEndpointResolver {
    fun resolve(hostname: String): InetAddress

    fun resolve(hostname: String, family: NativeDnsFamily): InetAddress = resolve(hostname)

    companion object {
        /** Explicit platform DNS bootstrap. This does not promise system-DNS bypass. */
        val SYSTEM: NativeDnsEndpointResolver = object : NativeDnsEndpointResolver {
            override fun resolve(hostname: String): InetAddress = resolve(hostname, NativeDnsFamily.ANY)

            override fun resolve(hostname: String, family: NativeDnsFamily): InetAddress {
                val addresses = NativeDnsWire.literalBytes(hostname)?.let {
                    arrayOf(InetAddress.getByAddress(it))
                } ?: InetAddress.getAllByName(hostname)
                return addresses.firstOrNull {
                    family == NativeDnsFamily.ANY ||
                        (family == NativeDnsFamily.IPV4 && it is Inet4Address) ||
                        (family == NativeDnsFamily.IPV6 && it is Inet6Address)
                } ?: throw UnknownHostException("Provider has no address in requested bootstrap family")
            }
        }
    }
}

data class SocketDnsLimits(
    val workers: Int = 4,
    val queueCapacity: Int = 16,
    val queryTimeoutMillis: Int = 5_000,
    val maxPacketBytes: Int = 65535,
    val udpPayloadBytes: Int = 1232,
    val maxRecords: Int = 128,
    val maxAddresses: Int = 32,
    val maxCnameHops: Int = 8,
) {
    init {
        require(workers in 1..64 && queueCapacity in 1..4096)
        require(queryTimeoutMillis in 1..120_000)
        require(maxPacketBytes in 512..65535 && udpPayloadBytes in 512..4096)
        require(maxRecords in 1..2048 && maxAddresses in 1..1024 && maxCnameHops in 1..32)
    }
}

/**
 * Owned, cancellable sockets with a total deadline covering queueing, bootstrap,
 * connect/handshake, CNAME following, and both ANY-family queries.
 * No shared sockets, connection pool, platform trust mutation, or JDK HttpClient.
 */
class SocketDnsTransport(
    private val endpointResolver: NativeDnsEndpointResolver = NativeDnsEndpointResolver.SYSTEM,
    private val sslContext: SSLContext = SSLContext.getDefault(),
    private val limits: SocketDnsLimits = SocketDnsLimits(),
) : DnsTransport, HttpsRecordTransport, NativeNat64DohDialer {
    data class State(
        val queries: Int,
        val resources: Int,
        val queuedTasks: Int,
        val runningWorkers: Int,
        val closed: Boolean,
    )

    private data class Endpoint(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val httpUri: URI? = null,
        val sni: String? = null,
    )

    private val monitor = Any()
    private val pending = linkedSetOf<Work<*>>()
    private var closed = false
    private val resourceCount = AtomicInteger()
    private val random = SecureRandom()
    private val threads = AtomicInteger()
    private val factory = ThreadFactory { task ->
        Thread(task, "native-dns-${threads.incrementAndGet()}").apply { isDaemon = true }
    }
    private val executor = ThreadPoolExecutor(
        limits.workers, limits.workers, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(limits.queueCapacity), factory,
    )
    private val deadlines = ScheduledThreadPoolExecutor(1, factory).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    override fun query(query: NativeDnsQuery): CompletableFuture<NativeDnsAnswer> = submit { perform(query, it) }

    override fun queryHttps(query: HttpsRecordQuery): CompletableFuture<HttpsRecordAnswer> =
        submit { performHttps(query, it) }

    override fun queryNat64Doh(query: NativeNat64DohQuery): CompletableFuture<NativeNat64DohAnswer> =
        submit { performNat64Doh(query, it) }

    private fun <T : Any> submit(operation: (Work<T>) -> T): CompletableFuture<T> {
        val work = Work<T>()
        synchronized(monitor) {
            if (closed) return failed(NativeDnsClosedException())
            if (pending.size >= limits.workers + limits.queueCapacity) {
                return failed(NativeDnsCapacityException())
            }
            pending.add(work)
        }
        try {
            work.setTimer(deadlines.schedule({
                work.finish(error = SocketTimeoutException("Native DNS total query deadline exceeded"))
            }, limits.queryTimeoutMillis.toLong(), TimeUnit.MILLISECONDS))
            work.submit(FutureTask {
                try {
                    work.finish(answer = operation(work))
                } catch (error: Exception) {
                    work.finish(error = error)
                }
                Unit
            })
        } catch (_: RejectedExecutionException) {
            work.finish(error = synchronized(monitor) {
                if (closed) NativeDnsClosedException() else NativeDnsCapacityException()
            })
        }
        return work.future
    }

    fun state(): State = synchronized(monitor) {
        State(pending.size, resourceCount.get(), executor.queue.size, executor.activeCount, closed)
    }

    override fun close() {
        val snapshot = synchronized(monitor) {
            if (closed) return
            closed = true
            pending.toList()
        }
        snapshot.forEach { it.finish(error = NativeDnsClosedException()) }
        executor.shutdownNow()
        deadlines.shutdownNow()
    }

    /** Optional bounded lifecycle join for tests/process teardown, never called by query/close. */
    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        if (!executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        return deadlines.awaitTermination((end - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    private inner class QueryFuture<T : Any>(private val work: Work<T>) : CompletableFuture<T>() {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = work.finish(canceled = true)
        fun markCanceled(): Boolean = super.cancel(true)
    }

    private inner class Work<T : Any> {
        private val lock = Any()
        private val resources = linkedSetOf<Closeable>()
        private var terminal = false
        private var task: FutureTask<Unit>? = null
        private var timer: ScheduledFuture<*>? = null
        private val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.queryTimeoutMillis.toLong())
        val future = QueryFuture<T>(this)

        fun setTimer(value: ScheduledFuture<*>) {
            synchronized(lock) {
                if (terminal) value.cancel(false) else timer = value
            }
        }

        fun submit(value: FutureTask<Unit>) {
            synchronized(lock) {
                if (terminal) return
                task = value
                // Atomic with cancellation/removal: a canceled queued task cannot
                // be enqueued after its remover has already run.
                executor.execute(value)
            }
        }

        fun remainingMillis(): Int {
            synchronized(lock) { if (terminal) throw CancellationException("DNS query ended") }
            val nanos = end - System.nanoTime()
            if (nanos <= 0) throw SocketTimeoutException("Native DNS query deadline exceeded")
            return ((nanos + 999_999) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        fun <T : Closeable> own(resource: T): T {
            synchronized(lock) {
                if (!terminal) {
                    resources.add(resource)
                    resourceCount.incrementAndGet()
                    return resource
                }
            }
            quietlyClose(resource)
            throw CancellationException("DNS query ended before resource registration")
        }

        fun release(resource: Closeable) {
            val removed = synchronized(lock) { resources.remove(resource) }
            if (removed) {
                resourceCount.decrementAndGet()
                quietlyClose(resource)
            }
        }

        fun finish(
            answer: T? = null,
            error: Throwable? = null,
            canceled: Boolean = false,
        ): Boolean {
            val owned: List<Closeable>
            val running: FutureTask<Unit>?
            val alarm: ScheduledFuture<*>?
            synchronized(lock) {
                if (terminal) return false
                terminal = true
                owned = resources.toList()
                resources.clear()
                running = task
                task = null
                alarm = timer
                timer = null
            }
            synchronized(monitor) { pending.remove(this) }
            alarm?.cancel(false)
            if (error != null || canceled) running?.cancel(true)
            if (running != null) executor.remove(running)
            // Raw socket is registered before a TLS wrapper; closing it first
            // releases a blocked handshake/read before TLS close_notify cleanup.
            owned.forEach {
                resourceCount.decrementAndGet()
                quietlyClose(it)
            }
            return when {
                canceled -> future.markCanceled()
                error != null -> future.completeExceptionally(error)
                else -> future.complete(requireNotNull(answer))
            }
        }
    }

    private fun bootstrap(provider: DnsProviderDescriptor, work: Work<*>): Pair<Endpoint, InetAddress> {
        val endpoint = endpoint(provider)
        val bootstrapFamily = when (provider.family?.toString()) {
            null, "0", "0.0" -> NativeDnsFamily.ANY
            "4", "4.0" -> NativeDnsFamily.IPV4
            "6", "6.0" -> NativeDnsFamily.IPV6
            else -> throw IllegalArgumentException("Unsupported provider family")
        }
        work.remainingMillis()
        val address = endpointResolver.resolve(endpoint.host, bootstrapFamily)
        work.remainingMillis()
        when (bootstrapFamily) {
            NativeDnsFamily.ANY -> Unit
            NativeDnsFamily.IPV4 -> require(address is Inet4Address) { "Provider requires IPv4 bootstrap" }
            NativeDnsFamily.IPV6 -> require(address is Inet6Address) { "Provider requires IPv6 bootstrap" }
        }
        return endpoint to address
    }

    private fun perform(query: NativeDnsQuery, work: Work<*>): NativeDnsAnswer {
        val normalized = query.copy(hostname = NativeDnsWire.name(query.hostname))
        val (endpoint, address) = bootstrap(query.provider, work)
        val types = when (query.family) {
            NativeDnsFamily.IPV4 -> listOf(NativeDnsWire.A)
            NativeDnsFamily.IPV6 -> listOf(NativeDnsWire.AAAA)
            NativeDnsFamily.ANY -> listOf(NativeDnsWire.A, NativeDnsWire.AAAA)
        }
        val answers = types.map { type -> resolveType(normalized, type, endpoint, address, work) }
        if (answers.any { it.code == NativeDnsAnswerCode.NXDOMAIN }) {
            if (answers.any { it.code != NativeDnsAnswerCode.NXDOMAIN }) {
                throw NativeDnsProtocolException("Inconsistent ANY-family NXDOMAIN responses")
            }
            return NativeDnsAnswer(code = NativeDnsAnswerCode.NXDOMAIN,
                negativeTtlMillis = answers.minOf { it.negativeTtlMillis })
        }
        val records = answers.flatMap { it.records }
        if (records.size > limits.maxAddresses) throw NativeDnsProtocolException("Too many combined DNS addresses")
        return NativeDnsAnswer(
            records, if (records.isEmpty()) NativeDnsAnswerCode.NODATA else NativeDnsAnswerCode.ANSWER,
            if (records.isEmpty()) answers.minOf { it.negativeTtlMillis } else 0,
        )
    }

    private fun performHttps(query: HttpsRecordQuery, work: Work<*>): HttpsRecordAnswer {
        var hostname = NativeDnsWire.name(query.hostname)
        val (endpoint, address) = bootstrap(query.provider, work)
        val names = mutableListOf<String>()
        val visited = mutableSetOf(hostname)
        var ttl = Long.MAX_VALUE
        while (true) {
            work.remainingMillis()
            val question = NativeHttpsWire.question(hostname, query.type, random.nextInt(65536), limits.udpPayloadBytes)
            fun parse(bytes: ByteArray) = NativeHttpsWire.parse(
                bytes, question, limits.maxRecords, limits.maxAddresses, limits.maxCnameHops,
            )
            var reply = parse(exchange(query.provider.transport, endpoint, address, question.bytes, work))
            if (reply.truncated && query.provider.transport == "udp") {
                reply = parse(tcp(endpoint.copy(tls = false), address, question.bytes, work))
            }
            if (reply.truncated) throw NativeDnsProtocolException("Truncated stream/DoH service response")
            for (alias in reply.answer.canonicalNames) {
                if (names.size >= limits.maxCnameHops || !visited.add(alias)) {
                    throw NativeDnsProtocolException("Service CNAME cycle/limit")
                }
                names.add(alias)
            }
            ttl = minOf(ttl, reply.aliasTtlMillis)
            val target = reply.followUp
            if (target != null) {
                hostname = target
                continue
            }
            return HttpsRecordAnswer(
                reply.answer.records.map { it.withTtl(ttl) }, reply.answer.code,
                minOf(ttl, reply.answer.negativeTtlMillis), names,
            )
        }
    }

    private fun performNat64Doh(query: NativeNat64DohQuery, work: Work<*>): NativeNat64DohAnswer {
        val service = endpoint(query.provider)
        require(service.httpUri != null && service.tls) { "Pinned NAT64 DoH requires HTTPS" }
        val literal = NativeDnsWire.literalBytes(query.ipv6Destination)
        require(literal != null && literal.size == 16) { "Pinned NAT64 destination must be IPv6" }
        val address = InetAddress.getByAddress(literal)
        var hostname = NativeDnsWire.name(query.hostname)
        val visited = mutableSetOf(hostname)
        val aliases = mutableListOf<String>()
        var ttl = Long.MAX_VALUE
        while (true) {
            work.remainingMillis()
            val bytes = httpRequest(service, address, work, "GET",
                NativeNat64Json.target(service.httpUri, hostname, query.type), null,
                listOf("application/dns-json", "application/json", "application/dns+json"))
            val parsed = NativeNat64Json.parse(bytes, hostname, query.type,
                limits.maxRecords, limits.maxAddresses, limits.maxCnameHops)
            for (alias in parsed.aliases) {
                require(aliases.size < limits.maxCnameHops && visited.add(alias)) { "JSON CNAME cycle/limit" }
                aliases.add(alias)
            }
            ttl = minOf(ttl, parsed.aliasTtlMillis)
            val follow = parsed.followUp
            if (follow != null) { hostname = follow; continue }
            return when (val value = parsed.value) {
                is NativeNat64DohAnswer.Addresses -> NativeNat64DohAnswer.Addresses(value.answer.copy(
                    records = value.answer.records.map { it.copy(ttlMillis = minOf(ttl, it.ttlMillis)) },
                    negativeTtlMillis = minOf(ttl, value.answer.negativeTtlMillis),
                ))
                is NativeNat64DohAnswer.Services -> NativeNat64DohAnswer.Services(HttpsRecordAnswer(
                    value.answer.records.map { it.withTtl(ttl) }, value.answer.code,
                    minOf(ttl, value.answer.negativeTtlMillis), aliases,
                ))
            }
        }
    }

    private fun resolveType(
        query: NativeDnsQuery,
        type: Int,
        endpoint: Endpoint,
        address: InetAddress,
        work: Work<*>,
    ): NativeDnsAnswer {
        var hostname = NativeDnsWire.name(query.hostname)
        val visited = linkedSetOf(hostname)
        var aliases = 0
        var ttl = Long.MAX_VALUE
        while (true) {
            work.remainingMillis()
            val question = NativeDnsWire.question(hostname, type, random.nextInt(65536), limits.udpPayloadBytes)
            var packet = exchange(query.provider.transport, endpoint, address, question.bytes, work)
            var reply = parse(packet, question)
            if (reply.truncated && query.provider.transport == "udp") {
                packet = tcp(endpoint.copy(tls = false), address, question.bytes, work)
                reply = parse(packet, question)
            }
            if (reply.truncated) throw NativeDnsProtocolException("Truncated stream/DoH DNS response")
            aliases += reply.aliases.size
            if (aliases > limits.maxCnameHops) throw NativeDnsProtocolException("CNAME chain exceeds limit")
            for (alias in reply.aliases) {
                if (!visited.add(alias)) throw NativeDnsProtocolException("Cross-response CNAME cycle")
            }
            ttl = minOf(ttl, reply.aliasTtlMillis)
            val target = reply.followUp
            if (target != null) {
                hostname = target
                continue
            }
            return reply.answer.copy(
                records = reply.answer.records.map { it.copy(ttlMillis = minOf(ttl, it.ttlMillis)) },
                negativeTtlMillis = minOf(ttl, reply.answer.negativeTtlMillis),
            )
        }
    }

    private fun parse(bytes: ByteArray, question: NativeDnsWire.Question) =
        NativeDnsWire.parse(bytes, question, limits.maxRecords, limits.maxAddresses, limits.maxCnameHops)

    private fun exchange(
        type: String,
        endpoint: Endpoint,
        address: InetAddress,
        bytes: ByteArray,
        work: Work<*>,
    ): ByteArray = when (type) {
        "udp" -> udp(endpoint, address, bytes, work)
        "tcp", "tls" -> tcp(endpoint, address, bytes, work)
        "https" -> http(endpoint, address, bytes, work)
        else -> throw UnsupportedOperationException("Unsupported DNS provider transport: $type")
    }

    private fun udp(endpoint: Endpoint, address: InetAddress, bytes: ByteArray, work: Work<*>): ByteArray {
        val socket = work.own(DatagramSocket(null))
        try {
            socket.bind(InetSocketAddress(0))
            val peer = InetSocketAddress(address, endpoint.port)
            socket.connect(peer)
            socket.soTimeout = work.remainingMillis()
            socket.send(DatagramPacket(bytes, bytes.size))
            val buffer = ByteArray(limits.maxPacketBytes + 1)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.soTimeout = work.remainingMillis()
            socket.receive(packet)
            if (packet.socketAddress != peer) throw NativeDnsProtocolException("UDP peer mismatch")
            if (packet.length > limits.maxPacketBytes) throw NativeDnsProtocolException("Oversized UDP DNS response")
            return buffer.copyOf(packet.length)
        } finally {
            work.release(socket)
        }
    }

    private fun tcp(endpoint: Endpoint, address: InetAddress, bytes: ByteArray, work: Work<*>): ByteArray =
        withSocket(endpoint, address, work) { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(bytes.size)
            output.write(bytes)
            output.flush()
            val input = DataInputStream(deadlineInput(socket, work))
            val size = input.readUnsignedShort()
            if (size !in 12..limits.maxPacketBytes) throw NativeDnsProtocolException("Invalid DNS stream length")
            ByteArray(size).also(input::readFully)
        }

    private fun http(endpoint: Endpoint, address: InetAddress, bytes: ByteArray, work: Work<*>): ByteArray {
        val uri = requireNotNull(endpoint.httpUri)
        return httpRequest(endpoint, address, work, "POST",
            uri.rawPath.orEmpty().ifEmpty { "/" } + uri.rawQuery?.let { "?$it" }.orEmpty(), bytes,
            listOf("application/dns-message"))
    }

    private fun httpRequest(
        endpoint: Endpoint,
        address: InetAddress,
        work: Work<*>,
        method: String,
        target: String,
        bytes: ByteArray?,
        responseTypes: List<String>,
    ): ByteArray =
        withSocket(endpoint, address, work) { socket ->
            val uri = requireNotNull(endpoint.httpUri)
            val httpLimits = Http1Limits(
                maxHeadBytes = 16 * 1024, maxLineBytes = 4096, maxHeaderCount = 64,
                maxTrailerBytes = 4096, maxTrailerCount = 16,
                maxBodyBytes = limits.maxPacketBytes.toLong(), bufferBytes = 4096,
            )
            val headers = Http1Headers(listOf(
                Http1Header("Host", " ${uri.rawAuthority}"),
                Http1Header("Accept", " ${responseTypes.first()}"),
                Http1Header("Connection", " close"),
            ) + if (bytes == null) emptyList() else listOf(
                Http1Header("Content-Type", " application/dns-message"),
                Http1Header("Content-Length", " ${bytes.size}"),
            ))
            val output = socket.getOutputStream()
            Http1HeadCodec.writeRequest(output,
                Http1RequestHead(method, Http1Target.Origin(target), "HTTP/1.1", headers), httpLimits)
            bytes?.let(output::write)
            output.flush()
            val reader = Http1Reader(deadlineInput(socket, work), httpLimits)
            val response = reader.readResponse(method) ?: throw NativeDnsProtocolException("Empty DoH response")
            if (response.head.status != 200) throw NativeDnsProtocolException("DoH HTTP status ${response.head.status}")
            val types = response.head.headers.values("Content-Type")
            if (types.size != 1 || responseTypes.none { it.equals(types[0].substringBefore(';').trim(), true) }) {
                throw UnsupportedOperationException("Unsupported DoH response Content-Type")
            }
            val encodings = response.head.headers.values("Content-Encoding")
            if (encodings.isNotEmpty() && !(encodings.size == 1 && encodings[0].equals("identity", true))) {
                throw UnsupportedOperationException("Compressed DoH response unsupported")
            }
            val body = ByteArrayOutputStream()
            reader.transferBody(body, Http1ChunkMode.DECODE)
            body.toByteArray()
        }

    private fun <T> withSocket(
        endpoint: Endpoint,
        address: InetAddress,
        work: Work<*>,
        block: (Socket) -> T,
    ): T {
        val raw = work.own(Socket())
        var secure: SSLSocket? = null
        try {
            raw.connect(InetSocketAddress(address, endpoint.port), work.remainingMillis())
            raw.soTimeout = work.remainingMillis()
            val socket = if (endpoint.tls) {
                val tls = work.own(sslContext.socketFactory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket)
                secure = tls
                val parameters = tls.sslParameters
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                parameters.serverNames = endpoint.sni?.let { listOf(SNIHostName(it)) } ?: emptyList()
                tls.sslParameters = parameters
                tls.soTimeout = work.remainingMillis()
                tls.startHandshake()
                // Some JSSE implementations consider configured SNI during their
                // identity check. Always check the service host independently.
                verifyServiceIdentity(endpoint.host, tls.session.peerCertificates[0] as X509Certificate)
                tls
            } else raw
            work.remainingMillis()
            return block(socket)
        } finally {
            secure?.let(work::release)
            work.release(raw)
        }
    }

    private fun deadlineInput(socket: Socket, work: Work<*>): InputStream =
        object : FilterInputStream(socket.getInputStream()) {
            override fun read(): Int {
                socket.soTimeout = work.remainingMillis()
                return `in`.read()
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                socket.soTimeout = work.remainingMillis()
                return `in`.read(bytes, offset, length)
            }
        }

    private fun endpoint(provider: DnsProviderDescriptor): Endpoint {
        if (provider.transport !in listOf("udp", "tcp", "tls", "https")) {
            throw UnsupportedOperationException("Unsupported DNS provider transport: ${provider.transport}")
        }
        val server = requireNotNull(provider.server) { "DNS provider server is required" }
        require(server.length in 1..4096 && server.none { it.code < 33 || it.code > 126 }) {
            "Invalid DNS provider server"
        }
        if (provider.transport == "https") {
            val uri = URI(server)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "http" || scheme == "https") { "Unsupported DoH URL scheme" }
            require(uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535) && provider.port == null) { "Invalid DoH URL/options" }
            val host = serviceHost(uri.host)
            val tls = scheme == "https"
            require(tls || provider.sni.isNullOrEmpty()) { "SNI requires TLS" }
            return Endpoint(host, if (uri.port == -1) if (tls) 443 else 80 else uri.port,
                tls, uri, if (tls) sni(provider.sni, host) else null)
        }
        val host = serviceHost(server)
        val tls = provider.transport == "tls"
        require(tls || provider.sni.isNullOrEmpty()) { "SNI requires TLS" }
        return Endpoint(host, port(provider.port, if (tls) 853 else 53), tls,
            sni = if (tls) sni(provider.sni, host) else null)
    }

    private fun serviceHost(server: String): String {
        val host = server.removePrefix("[").removeSuffix("]")
        if (server.startsWith('[') || server.endsWith(']')) {
            require(server.startsWith('[') && server.endsWith(']') &&
                NativeDnsWire.literalBytes(host)?.size == 16) { "Invalid bracketed provider address" }
        }
        require('%' !in host) { "Scoped provider addresses are unsupported" }
        return if (NativeDnsWire.literalBytes(host) != null) host else NativeDnsWire.name(host)
    }

    private fun sni(configured: String?, host: String): String? {
        if (configured.isNullOrEmpty()) return host.takeUnless { NativeDnsWire.literalBytes(it) != null }
        require(NativeDnsWire.literalBytes(configured) == null) { "SNI must be a DNS name" }
        return SNIHostName(configured).asciiName
    }

    private fun port(raw: Any?, default: Int): Int {
        if (raw == null) return default
        val number = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toDoubleOrNull()
            else -> null
        }
        require(number != null && number.isFinite() && number in 1.0..65535.0 && number % 1.0 == 0.0) {
            "Invalid DNS provider port"
        }
        return number.toInt()
    }

    private fun verifyServiceIdentity(host: String, certificate: X509Certificate) {
        val literal = NativeDnsWire.literalBytes(host)
        val sans = certificate.subjectAlternativeNames.orEmpty()
        val matches = sans.any { san ->
            if (literal != null) {
                if (san[0] != 7) false else {
                    val candidate = when (val value = san[1]) {
                        is String -> NativeDnsWire.literalBytes(value)
                        is ByteArray -> value
                        else -> null
                    }
                    candidate?.contentEquals(literal) == true
                }
            } else {
                val dns = (san.getOrNull(1) as? String)?.lowercase(Locale.ROOT)
                san[0] == 2 && dns != null && (dns == host || (
                    dns.startsWith("*.") && dns.count { it == '.' } >= 2 &&
                        host.substringAfter('.', "") == dns.substring(2)
                    ))
            }
        }
        if (!matches) throw SSLHandshakeException("Certificate SAN does not match DNS service host $host")
    }

    private fun quietlyClose(resource: Closeable) {
        try { resource.close() } catch (_: IOException) { }
    }

    private fun <T> failed(error: Exception) = CompletableFuture<T>().apply { completeExceptionally(error) }
}
