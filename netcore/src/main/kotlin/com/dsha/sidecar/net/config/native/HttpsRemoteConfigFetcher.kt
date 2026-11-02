package com.dsha.sidecar.net.config.native

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

data class HttpsConfigLimits(
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 30_000,
    val totalTimeoutMillis: Int = 60_000,
    val maxWireBytes: Int = 1_048_576,
    val maxDecodedBytes: Int = 1_048_576,
    val maxRedirects: Int = 3,
) {
    init {
        require(connectTimeoutMillis in 1..120_000 && readTimeoutMillis in 1..120_000)
        require(totalTimeoutMillis in 1..120_000)
        require(maxWireBytes in 2..8_388_608 && maxDecodedBytes in 2..8_388_608)
        require(maxRedirects in 0..10)
    }
}

enum class HttpsConfigError {
    ADDRESS, REDIRECT, STATUS, CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT,
    CANCELLED, LIMIT, ENCODING, TLS, IO, BUSY, CLOSED,
}

/** Never retains a URL, response text, underlying exception or suppressed cause. */
class HttpsConfigFailure(val error: HttpsConfigError) :
    java.io.IOException("HTTPS configuration fetch rejected: $error")

/**
 * Explicit direct HTTPS GET only. Construction starts no threads or network.
 * Each connection gets a fresh system-trust SSLContext and HTTPS endpoint ID.
 * No global proxy, verifier, TLS context, provider, logger or cache is changed.
 *
 * Caller deadlines are bounded even if a platform DNS/native connect ignores
 * interruption. Such work can occupy one of two bounded daemon I/O leases until
 * the platform returns; overload fails BUSY rather than creating more threads.
 */
class HttpsRemoteConfigFetcher internal constructor(
    private val limits: HttpsConfigLimits,
    private val openConnection: (URI) -> HttpsURLConnection,
    private val newContext: () -> SSLContext,
) : RemoteConfigFetcher, Closeable {
    constructor(limits: HttpsConfigLimits = HttpsConfigLimits()) : this(
        limits,
        { uri -> uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection },
        {
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(null as KeyStore?)
            SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        },
    )

    private val closed = AtomicBoolean()
    private val active = Collections.newSetFromMap(ConcurrentHashMap<HttpsOperation, Boolean>())
    private val io = ThreadPoolExecutor(
        2, 2, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
        namedDaemon("native-config-https"), ThreadPoolExecutor.AbortPolicy(),
    )
    private val cleanup = ThreadPoolExecutor(
        2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        namedDaemon("native-config-close"), ThreadPoolExecutor.AbortPolicy(),
    )

    /** A session supports sequential shared/personal fetches and active-I/O cancellation. */
    fun newSession(): Session {
        if (closed.get()) failHttps(HttpsConfigError.CLOSED)
        return Session()
    }

    override fun fetch(address: RemoteConfigAddress, maximumBytes: Int): ByteArray =
        newSession().use { it.fetch(address, maximumBytes) }

    inner class Session internal constructor() : RemoteConfigFetcher, Closeable {
        private val lock = Any()
        private var operation: HttpsOperation? = null
        private var ended = false
        private var cancelled = false

        override fun fetch(address: RemoteConfigAddress, maximumBytes: Int): ByteArray {
            val uri = checkedUri(address.uri)
            if (maximumBytes !in 2..8_388_608) failHttps(HttpsConfigError.LIMIT)
            val current = synchronized(lock) {
                if (ended || closed.get()) failHttps(HttpsConfigError.CLOSED)
                if (cancelled) failHttps(HttpsConfigError.CANCELLED)
                if (operation != null) failHttps(HttpsConfigError.BUSY)
                HttpsOperation(limits).also { operation = it }
            }
            try {
                return execute(current, uri, minOf(maximumBytes, limits.maxDecodedBytes))
            } finally {
                synchronized(lock) { operation = null }
            }
        }

        /**
         * True means an active fetch was cancelled before its successful return
         * linearization. Cancellation after a fetch has finished returns false;
         * it cannot revoke a repository epoch that has already been published.
         */
        fun cancel(): Boolean = synchronized(lock) {
            cancelled = true
            operation?.let { abort(it, HttpsConfigError.CANCELLED) } ?: false
        }

        override fun close() {
            synchronized(lock) {
                ended = true
                operation?.let { abort(it, HttpsConfigError.CANCELLED) }
            }
        }
    }

    private fun execute(operation: HttpsOperation, uri: URI, maximumBytes: Int): ByteArray {
        if (closed.get()) failHttps(HttpsConfigError.CLOSED)
        active += operation
        val task = FutureTask {
            try {
                val result = download(operation, uri, maximumBytes)
                operation.succeed()
                result
            } catch (failure: Exception) {
                throw operation.redact(failure)
            } finally {
                operation.closeResources()
                active -= operation
            }
        }
        operation.bind(task)
        try {
            if (closed.get()) {
                abort(operation, HttpsConfigError.CLOSED)
                failHttps(HttpsConfigError.CLOSED)
            }
            io.execute(task)
            return task.get(operation.remainingNanos(), TimeUnit.NANOSECONDS)
        } catch (_: RejectedExecutionException) {
            abort(operation, HttpsConfigError.BUSY)
            failHttps(HttpsConfigError.BUSY)
        } catch (_: TimeoutException) {
            abort(operation, HttpsConfigError.TOTAL_TIMEOUT)
            failHttps(HttpsConfigError.TOTAL_TIMEOUT)
        } catch (_: InterruptedException) {
            abort(operation, HttpsConfigError.CANCELLED)
            Thread.currentThread().interrupt()
            failHttps(HttpsConfigError.CANCELLED)
        } catch (_: CancellationException) {
            throw operation.reason()
        } catch (failure: ExecutionException) {
            throw operation.redact(failure.cause)
        } catch (failure: HttpsConfigFailure) {
            if (failure.error == HttpsConfigError.TOTAL_TIMEOUT) abort(operation, failure.error)
            throw failure
        } finally {
            // A task cancelled before it started never enters its worker finally.
            if (task.isCancelled) active -= operation
        }
    }

    private fun abort(operation: HttpsOperation, reason: HttpsConfigError): Boolean {
        if (!operation.abort(reason)) return false
        try {
            cleanup.execute { operation.closeResources() }
        } catch (_: RejectedExecutionException) {
            // Bounded I/O worker finally also owns cleanup; no unbounded reaper queue.
        }
        return true
    }

    private fun download(operation: HttpsOperation, original: URI, maximumBytes: Int): ByteArray {
        var uri = original
        val seen = mutableSetOf<URI>()
        repeat(limits.maxRedirects + 1) { redirects ->
            operation.check()
            if (!seen.add(uri)) failHttps(HttpsConfigError.REDIRECT)
            val connection = openConnection(uri)
            operation.attach(connection)
            try {
                connection.sslSocketFactory = EndpointSocketFactory(newContext().socketFactory, operation)
                // HTTPS endpoint identification is enabled on every socket below.
                // Never allow a process-global custom verifier to override a mismatch.
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> false }
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.allowUserInteraction = false
                connection.requestMethod = "GET"
                connection.doInput = true
                connection.doOutput = false
                connection.connectTimeout = operation.timeout(limits.connectTimeoutMillis)
                connection.readTimeout = operation.timeout(limits.readTimeoutMillis)
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.setRequestProperty("User-Agent", "dev-sidecar-native-config/1")
                // Explicit header disables Android URLConnection's transparent gzip.
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Connection", "close")
                operation.connecting = true
                connection.connect()
                operation.connecting = false
                operation.check()
                val status = connection.responseCode
                checkHeaders(connection)
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirects == limits.maxRedirects) failHttps(HttpsConfigError.REDIRECT)
                    val location = connection.getHeaderField("Location") ?: failHttps(HttpsConfigError.REDIRECT)
                    if (location.length > 8192) failHttps(HttpsConfigError.REDIRECT)
                    val next = try { checkedUri(uri.resolve(location)) } catch (_: Exception) {
                        failHttps(HttpsConfigError.REDIRECT)
                    }
                    if (!sameOrigin(original, next)) failHttps(HttpsConfigError.REDIRECT)
                    uri = next
                } else {
                    if (status != 200) failHttps(HttpsConfigError.STATUS)
                    val charset = responseCharset(connection.getHeaderField("Content-Type"))
                    val encoding = connection.getHeaderField("Content-Encoding")?.trim()?.lowercase(Locale.ROOT)
                    if (encoding != null && encoding !in setOf("identity", "gzip")) failHttps(HttpsConfigError.ENCODING)
                    val length = connection.getHeaderField("Content-Length")?.let {
                        if (!it.matches(Regex("[0-9]+"))) failHttps(HttpsConfigError.ENCODING)
                        it.toLongOrNull() ?: failHttps(HttpsConfigError.LIMIT)
                    }
                    if (length != null && length > limits.maxWireBytes) failHttps(HttpsConfigError.LIMIT)
                    val raw = connection.inputStream
                    operation.attach(raw)
                    val wire = WireLimit(raw, operation, limits.maxWireBytes)
                    val body = if (encoding == "gzip") GZIPInputStream(wire, 4096) else wire
                    val result = ByteArrayOutputStream()
                    body.use {
                        val buffer = ByteArray(4096)
                        while (true) {
                            operation.check()
                            connection.readTimeout = operation.timeout(limits.readTimeoutMillis)
                            val count = it.read(buffer)
                            if (count < 0) break
                            if (result.size().toLong() + count > maximumBytes) failHttps(HttpsConfigError.LIMIT)
                            result.write(buffer, 0, count)
                        }
                    }
                    if (length != null && wire.count != length) failHttps(HttpsConfigError.ENCODING)
                    val bytes = result.toByteArray()
                    if (bytes.size < 2) failHttps(HttpsConfigError.ENCODING)
                    if (charset == "us-ascii" && bytes.any { it.toInt() < 0 }) failHttps(HttpsConfigError.ENCODING)
                    try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                    } catch (_: java.nio.charset.CharacterCodingException) {
                        failHttps(HttpsConfigError.ENCODING)
                    }
                    operation.check()
                    return bytes
                }
            } finally {
                operation.closeResources()
            }
        }
        failHttps(HttpsConfigError.REDIRECT)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            active.forEach { abort(it, HttpsConfigError.CLOSED) }
            io.shutdownNow()
            cleanup.shutdown()
        }
    }

    private fun checkHeaders(connection: HttpsURLConnection) {
        var chars = 0L
        val fields = connection.headerFields
        if (fields.size > 64) failHttps(HttpsConfigError.LIMIT)
        fields.forEach { (name, values) ->
            chars += name?.length ?: 0
            if (values.size > 64) failHttps(HttpsConfigError.LIMIT)
            for (value in values) chars += value.length
        }
        if (chars > 32_768) failHttps(HttpsConfigError.LIMIT)
        for (name in listOf("Location", "Content-Length", "Content-Encoding", "Content-Type")) {
            if (fields.filterKeys { it?.equals(name, ignoreCase = true) == true }.values.sumOf { it.size } > 1) {
                failHttps(HttpsConfigError.ENCODING)
            }
        }
    }

    private fun responseCharset(type: String?): String {
        if (type == null) return "utf-8"
        val parts = type.split(';')
        if (parts.first().trim().lowercase(Locale.ROOT) !in setOf(
                "application/json", "application/json5", "text/plain", "application/octet-stream",
            ) || parts.size > 2
        ) failHttps(HttpsConfigError.ENCODING)
        if (parts.size == 1) return "utf-8"
        val parameter = parts[1].trim()
        if (!parameter.startsWith("charset=", ignoreCase = true)) failHttps(HttpsConfigError.ENCODING)
        val value = parameter.substring(8).trim().removeSurrounding("\"").lowercase(Locale.ROOT)
        return when (value) {
            "utf8", "utf-8" -> "utf-8"
            "us-ascii" -> "us-ascii"
            else -> failHttps(HttpsConfigError.ENCODING)
        }
    }
}

private fun checkedUri(uri: URI): URI {
    if (!uri.scheme.equals("https", ignoreCase = true)) failHttps(HttpsConfigError.ADDRESS)
    return try {
        RemoteConfigPolicy.address(RemoteChannel.SHARED, uri.toString())?.uri
            ?: failHttps(HttpsConfigError.ADDRESS)
    } catch (_: Exception) {
        failHttps(HttpsConfigError.ADDRESS)
    }
}

private fun sameOrigin(a: URI, b: URI): Boolean =
    a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) &&
        (if (a.port == -1) 443 else a.port) == (if (b.port == -1) 443 else b.port)

private fun failHttps(error: HttpsConfigError): Nothing = throw HttpsConfigFailure(error)

private fun namedDaemon(prefix: String): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}

private class HttpsOperation(private val limits: HttpsConfigLimits) {
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.totalTimeoutMillis.toLong())
    private val lock = Any()
    private var abortReason: HttpsConfigError? = null
    private var finished = false
    private var task: FutureTask<ByteArray>? = null
    private var connection: HttpsURLConnection? = null
    private var stream: InputStream? = null
    private val sockets = mutableListOf<Socket>()
    @Volatile var connecting = false

    fun bind(future: FutureTask<ByteArray>) = synchronized(lock) {
        task = future
        if (abortReason != null) future.cancel(true)
    }

    fun remainingNanos(): Long = (deadline - System.nanoTime()).also {
        if (it <= 0) failHttps(HttpsConfigError.TOTAL_TIMEOUT)
    }

    fun timeout(configured: Int): Int =
        minOf(configured.toLong(), (remainingNanos() + 999_999) / 1_000_000).coerceAtLeast(1).toInt()

    fun check() {
        synchronized(lock) { abortReason?.let(::failHttps) }
        remainingNanos()
    }

    fun attach(value: HttpsURLConnection) = synchronized(lock) {
        abortReason?.let(::failHttps)
        connection = value
    }

    fun attach(value: InputStream) = synchronized(lock) {
        abortReason?.let(::failHttps)
        stream = value
    }

    fun socket(value: Socket): Socket {
        synchronized(lock) {
            if (abortReason != null) {
                try { value.close() } catch (_: Exception) {}
                failHttps(abortReason!!)
            }
            sockets += value
        }
        return value
    }

    fun succeed() = synchronized(lock) {
        abortReason?.let(::failHttps)
        remainingNanos()
        finished = true
    }

    fun abort(reason: HttpsConfigError): Boolean {
        val future = synchronized(lock) {
            if (finished || abortReason != null) return false
            abortReason = reason
            task
        }
        future?.cancel(true)
        return true
    }

    fun reason(): HttpsConfigFailure = synchronized(lock) {
        HttpsConfigFailure(abortReason ?: HttpsConfigError.CANCELLED)
    }

    fun redact(failure: Throwable?): HttpsConfigFailure = synchronized(lock) {
        HttpsConfigFailure(abortReason ?: when {
            System.nanoTime() >= deadline -> HttpsConfigError.TOTAL_TIMEOUT
            failure is HttpsConfigFailure -> failure.error
            failure is SocketTimeoutException ->
                if (connecting) HttpsConfigError.CONNECT_TIMEOUT else HttpsConfigError.READ_TIMEOUT
            failure is SSLException -> HttpsConfigError.TLS
            failure is java.util.zip.ZipException -> HttpsConfigError.ENCODING
            else -> HttpsConfigError.IO
        })
    }

    fun closeResources() {
        val resources = synchronized(lock) {
            Triple(sockets.toList(), stream, connection).also {
                sockets.clear()
                stream = null
                connection = null
            }
        }
        resources.first.forEach { try { it.close() } catch (_: Exception) {} }
        try { resources.second?.close() } catch (_: Exception) {}
        try { resources.third?.errorStream?.close() } catch (_: Exception) {}
        try { resources.third?.disconnect() } catch (_: Exception) {}
    }
}

private class WireLimit(
    stream: InputStream,
    private val operation: HttpsOperation,
    private val maximum: Int,
) : FilterInputStream(stream) {
    var count = 0L
        private set

    override fun read(): Int {
        operation.check()
        return `in`.read().also { if (it >= 0) add(1) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        operation.check()
        return `in`.read(buffer, offset, minOf(length.toLong(), maximum - count + 1).coerceAtLeast(1).toInt())
            .also { if (it > 0) add(it) }
    }

    private fun add(size: Int) {
        count += size
        if (count > maximum) failHttps(HttpsConfigError.LIMIT)
        operation.check()
    }
}

/** Configures platform TLS and records sockets for cancellation; implements no TLS protocol. */
private class EndpointSocketFactory(
    private val delegate: SSLSocketFactory,
    private val operation: HttpsOperation,
) : SSLSocketFactory() {
    private fun configure(socket: Socket): Socket {
        operation.socket(socket)
        if (socket !is SSLSocket) failHttps(HttpsConfigError.TLS)
        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        return socket
    }
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = configure(delegate.createSocket())
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        configure(delegate.createSocket(s, host, port, autoClose))
    override fun createSocket(host: String, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, local, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, local, localPort))
}
