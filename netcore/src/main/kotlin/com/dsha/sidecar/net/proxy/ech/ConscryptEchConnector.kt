package com.dsha.sidecar.net.proxy.ech

import com.dsha.sidecar.net.dns.EchConfig
import com.dsha.sidecar.net.dns.EchConfigList
import com.dsha.sidecar.net.dns.EchConfigListResult
import org.conscrypt.Conscrypt
import org.conscrypt.NativeEchPolicyAdapter
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** A validated, detached full ECHConfigList, never a contents-only ECHConfig. */
class TypedEchConfigList private constructor(wire: ByteArray, configs: List<EchConfig>) {
    private val bytes = wire.copyOf()
    private val values = configs.toList()
    val wire: ByteArray get() = bytes.copyOf()
    val configs: List<EchConfig> get() = values.toMutableList()

    companion object {
        fun parse(wire: ByteArray): TypedEchConfigList {
            val copy = wire.copyOf()
            val parsed = EchConfigList.parseEchConfigList(copy)
            require(parsed is EchConfigListResult.Success) { "Invalid ECHConfigList" }
            require(EchConfigList.selectEchConfig(parsed.configs) != null) {
                "No supported ECH config (FE0D/X25519/HKDF-SHA256/AES-128-GCM)"
            }
            return TypedEchConfigList(copy, parsed.configs)
        }
    }
}

/** Both policies validate the peer chain; there is no implicit trust-all mode. */
sealed class EchPeerTrust {
    object SystemRoots : EchPeerTrust()

    class TrustAnchors(certificates: List<X509Certificate>) : EchPeerTrust() {
        private val certificatesDer = certificates.map { it.encoded.copyOf() }

        init {
            require(certificatesDer.isNotEmpty()) { "Empty trust anchors" }
        }

        internal fun trustManager(): X509TrustManager {
            val factory = CertificateFactory.getInstance("X.509")
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certificatesDer.forEachIndexed { index, der ->
                store.setCertificateEntry("ech-anchor-$index", factory.generateCertificate(der.inputStream()))
            }
            return trustManagerFor(store)
        }
    }

    internal fun manager(): X509TrustManager = when (this) {
        SystemRoots -> trustManagerFor(null)
        is TrustAnchors -> trustManager()
    }

    companion object {
        private fun trustManagerFor(store: KeyStore?): X509TrustManager {
            // TLS 1.3 authType GENERIC is understood by Conscrypt's TM, unlike SunJSSE's bare TM.
            // Android's default Conscrypt ProviderBuilder omits TrustManagerFactory services.
            val provider = Conscrypt.newProviderBuilder().provideTrustManager(true).build()
            val factory = TrustManagerFactory.getInstance("PKIX", provider)
            factory.init(store)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }
}

enum class EchFailure {
    REQUIRED_POLICY_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    INVALID_REQUEST,
    ECH_REJECTED,
    PEER_AUTHENTICATION,
    HANDSHAKE,
    TIMEOUT,
    CANCELLED
}

enum class EchStage {
    UNKNOWN,
    INPUT_VALIDATION,
    CANCELLATION_REGISTRATION,
    TRUST_MANAGER_CREATION,
    REQUIRED_POLICY_PREFLIGHT,
    CONTEXT_CREATION,
    SOCKET_FACTORY,
    ENGINE_SOCKET_POLICY,
    LAYERED_SOCKET_CREATION,
    SOCKET_CONFIGURATION,
    ECH_CONFIG_INSTALLATION,
    HANDSHAKE_START,
    PEER_VERIFICATION,
    CONNECTION_HANDOFF
}

class EchConnectionException(
    val failure: EchFailure,
    cause: Throwable? = null,
    val stage: EchStage = EchStage.UNKNOWN
) : IOException("Conscrypt ECH connection failed: $failure", cause)

enum class EchStatus {
    /** Successful TLS 1.3 with usable config and the pinned adapter's enforced REQUIRED policy. */
    ACCEPTED_WITH_PINNED_REQUIRED_POLICY
}

class EchConnection internal constructor(val socket: SSLSocket, val echStatus: EchStatus) : Closeable {
    override fun close() = socket.close()
}

/** Cancels only an in-progress connect. A successful returned connection belongs to its caller. */
class EchCancellation {
    private var cancelled = false
    private val listeners = mutableSetOf<() -> Unit>()

    fun cancel() {
        val callbacks = synchronized(this) {
            if (cancelled) return
            cancelled = true
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { it() }
    }

    internal fun register(callback: () -> Unit): Closeable {
        val immediate = synchronized(this) {
            if (cancelled) true else {
                listeners.add(callback)
                false
            }
        }
        if (immediate) callback()
        return Closeable { synchronized(this) { listeners.remove(callback) } }
    }
}

/**
 * Isolated, unintegrated connector candidate using an explicit provider and the version-controlled
 * NativeEchPolicyAdapter. Conscrypt 2.8.0 has no supported public REQUIRED policy setter.
 */
class ConscryptEchConnector {
    /**
     * Takes ownership of an already-connected socket, closing it on every failure. No application
     * stream is returned until ECH enforcement, TLS 1.3, chain trust and verifyHost all succeed.
     * A fresh explicit-provider context per call avoids cross-route session resumption.
     */
    fun connect(
        raw: Socket,
        echConfigList: TypedEchConfigList,
        innerHost: String,
        verifyHost: String = innerHost,
        peerTrust: EchPeerTrust = EchPeerTrust.SystemRoots,
        handshakeTimeoutMillis: Int = 10_000,
        cancellation: EchCancellation = EchCancellation()
    ): EchConnection {
        var socket: SSLSocket? = null
        var deadline: java.util.concurrent.ScheduledFuture<*>? = null
        var registration: Closeable? = null
        val phase = AtomicReference(Phase.RUNNING)
        var stage = EchStage.INPUT_VALIDATION
        try {
            require(raw.isConnected && !raw.isClosed) { "Raw socket must be connected" }
            require(handshakeTimeoutMillis in 1..120_000) { "Invalid handshake timeout" }
            val inner = dnsHost(innerHost)
            val verify = dnsHost(verifyHost)
            stage = EchStage.CANCELLATION_REGISTRATION
            registration = cancellation.register {
                if (phase.compareAndSet(Phase.RUNNING, Phase.CANCELLED)) runCatching { raw.close() }
            }
            if (phase.get() == Phase.CANCELLED) throw EchConnectionException(EchFailure.CANCELLED)
            deadline = deadlines.schedule({
                if (phase.compareAndSet(Phase.RUNNING, Phase.TIMED_OUT)) runCatching { raw.close() }
            }, handshakeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)

            stage = EchStage.TRUST_MANAGER_CREATION
            val strictTrustManager = peerTrust.manager()
            stage = EchStage.REQUIRED_POLICY_PREFLIGHT
            val manager = try {
                NativeEchPolicyAdapter.requireEch(strictTrustManager, echConfigList.wire, inner)
            } catch (failure: LinkageError) {
                throw EchConnectionException(EchFailure.REQUIRED_POLICY_UNAVAILABLE, failure)
            } catch (failure: javax.net.ssl.SSLException) {
                throw EchConnectionException(EchFailure.REQUIRED_POLICY_UNAVAILABLE, failure)
            }
            stage = EchStage.CONTEXT_CREATION
            val context = newExplicitContext(manager)
            stage = EchStage.SOCKET_FACTORY
            val factory = context.socketFactory
            stage = EchStage.ENGINE_SOCKET_POLICY
            Conscrypt.setUseEngineSocket(factory, true)
            stage = EchStage.LAYERED_SOCKET_CREATION
            socket = factory.createSocket(raw, inner, raw.port, true) as SSLSocket
            stage = EchStage.SOCKET_CONFIGURATION
            socket.useClientMode = true
            socket.soTimeout = handshakeTimeoutMillis
            socket.enabledProtocols = arrayOf("TLSv1.3")
            // Endpoint identity is checked separately, since innerHost and verifyHost may differ.
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = null }
            Conscrypt.setHostname(socket, inner)
            Conscrypt.setUseSessionTickets(socket, false)
            Conscrypt.setApplicationProtocols(socket, arrayOf("http/1.1"))
            stage = EchStage.ECH_CONFIG_INSTALLATION
            Conscrypt.setEchConfigList(socket, echConfigList.wire)
            stage = EchStage.HANDSHAKE_START
            socket.startHandshake()
            stage = EchStage.PEER_VERIFICATION
            if (socket.session.protocol != "TLSv1.3") throw EchConnectionException(EchFailure.HANDSHAKE)
            val peer = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw CertificateException("Missing peer X509 certificate")
            verifyDnsSan(verify, peer)
            check(Conscrypt.getApplicationProtocol(socket).let { it == null || it == "http/1.1" }) {
                "Unexpected application protocol"
            }
            stage = EchStage.CONNECTION_HANDOFF
            if (!phase.compareAndSet(Phase.RUNNING, Phase.COMPLETE)) {
                throw EchConnectionException(if (phase.get() == Phase.CANCELLED) EchFailure.CANCELLED else EchFailure.TIMEOUT)
            }
            socket.soTimeout = 0
            return EchConnection(socket, EchStatus.ACCEPTED_WITH_PINNED_REQUIRED_POLICY)
        } catch (failure: Exception) {
            runCatching { raw.close() }
            runCatching { socket?.close() }
            val kind = when (phase.get()) {
                Phase.CANCELLED -> EchFailure.CANCELLED
                Phase.TIMED_OUT -> EchFailure.TIMEOUT
                else -> classify(failure)
            }
            throw if (failure is EchConnectionException && failure.failure == kind && failure.stage != EchStage.UNKNOWN) failure
                else EchConnectionException(kind, failure, stage)
        } catch (failure: LinkageError) {
            runCatching { raw.close() }
            runCatching { socket?.close() }
            throw EchConnectionException(EchFailure.PROVIDER_UNAVAILABLE, failure, stage)
        } finally {
            deadline?.cancel(false)
            registration?.close()
        }
    }

    private enum class Phase { RUNNING, CANCELLED, TIMED_OUT, COMPLETE }

    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "ech-connect-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        internal fun newExplicitContext(manager: X509TrustManager): SSLContext {
            try {
                Conscrypt.checkAvailability()
                val version = Conscrypt.version()
                check(version != null && version.major() == 2 && version.minor() == 8 && version.patch() == 0) {
                    "This candidate is pinned to Conscrypt 2.8.0"
                }
                return SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply {
                    init(null, arrayOf<TrustManager>(manager), null)
                    check(Conscrypt.isConscrypt(this))
                }
            } catch (failure: Exception) {
                throw EchConnectionException(EchFailure.PROVIDER_UNAVAILABLE, failure)
            } catch (failure: LinkageError) {
                throw EchConnectionException(EchFailure.PROVIDER_UNAVAILABLE, failure)
            }
        }

        private fun classify(failure: Throwable): EchFailure {
            if (failure is EchConnectionException) return failure.failure
            var current: Throwable? = failure
            val causes = mutableListOf<Throwable>()
            repeat(16) {
                val next = current ?: return@repeat
                if (causes.any { it === next }) return@repeat
                causes.add(next)
                current = next.cause
            }
            // Android and OpenJDK expose different wrapper packages. Only public exception names
            // are inspected; no private fields or native methods are accessed.
            if (causes.any { it.javaClass.simpleName in setOf("EchConfigMismatchException", "EchRejectedException") }) {
                return EchFailure.ECH_REJECTED
            }
            if (causes.any { it is CertificateException }) return EchFailure.PEER_AUTHENTICATION
            if (causes.any { it is java.net.SocketTimeoutException }) return EchFailure.TIMEOUT
            if (failure is IllegalArgumentException) return EchFailure.INVALID_REQUEST
            return EchFailure.HANDSHAKE
        }

        internal fun dnsHost(value: String): String {
            require(value.length in 1..253 && value.all { it.code in 33..126 }) { "Expected ASCII DNS host" }
            val canonical = value.lowercase(Locale.ROOT)
            require(canonical.any { it in 'a'..'z' }) { "IP verification is outside this candidate's DNS-only API" }
            require(canonical.split('.').all {
                it.length in 1..63 && it.first() != '-' && it.last() != '-' &&
                    it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }
            }) { "Invalid DNS host" }
            return canonical
        }

        internal fun verifyDnsSan(host: String, certificate: X509Certificate) {
            val matched = certificate.subjectAlternativeNames.orEmpty().any { entry ->
                if (entry.size < 2 || entry[0] != 2 || entry[1] !is String) return@any false
                val pattern = (entry[1] as String).lowercase(Locale.ROOT)
                if (pattern.startsWith("*.")) {
                    val suffix = pattern.substring(2)
                    suffix.contains('.') && host.substringAfter('.', "") == suffix &&
                        runCatching { dnsHost(suffix) }.isSuccess
                } else {
                    runCatching { dnsHost(pattern) == host }.getOrDefault(false)
                }
            }
            if (!matched) throw CertificateException("Peer certificate SAN does not match verifyHost")
        }
    }
}
