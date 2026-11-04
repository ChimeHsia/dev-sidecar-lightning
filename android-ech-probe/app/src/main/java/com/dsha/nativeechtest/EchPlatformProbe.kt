package com.dsha.nativeechtest

import android.os.Build
import android.os.Bundle
import android.util.Base64
import com.dsha.sidecar.net.proxy.ech.ConscryptEchConnector
import com.dsha.sidecar.net.proxy.ech.EchCancellation
import com.dsha.sidecar.net.proxy.ech.EchConnectionException
import com.dsha.sidecar.net.proxy.ech.EchDiagnostics
import com.dsha.sidecar.net.proxy.ech.EchPeerTrust
import com.dsha.sidecar.net.proxy.ech.TypedEchConfigList
import org.conscrypt.Conscrypt
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/** Ephemeral inputs are host-generated public config/root certs only. No user trust/key access. */
object EchPlatformProbe {
    const val LOG_TAG = "NativeEchProof"
    const val PATH = "/native/android-ech-proof?q=24"

    fun run(input: Bundle): JSONObject {
        val result = JSONObject()
            .put("schema", 2)
            .put("probeRevision", "diag2-explicit-tmf")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
        val providers = Security.getProviders().map { it.name }
        val defaults = SSLContext.getDefault()
        val httpsFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val started = System.nanoTime()
        var raw: Socket? = null
        val cancellation = EchCancellation()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val expected = input.getString("expected", "accepted")
        var stage = "INPUT_VALIDATION"
        result.put("nativeAvailable", false)
        try {
            val port = input.getString("port")?.toIntOrNull() ?: 0
            require(port in 1..65535)
            val timeout = input.getString("timeout_ms", "3000").toInt()
            require(timeout in 100..10000)
            val configText = requireNotNull(input.getString("config_b64"))
            require(configText.length <= 90_000)
            val config = TypedEchConfigList.parse(Base64.decode(configText, Base64.DEFAULT))
            result.put("configValidated", true).put("configWireBytes", config.wire.size)
            stage = "PUBLIC_ROOT_PARSING"
            val trust = if (input.getString("trust", "anchors") == "system") {
                result.put("trustMode", "system")
                EchPeerTrust.SystemRoots
            } else {
                result.put("trustMode", "anchors")
                val caText = requireNotNull(input.getString("ca_b64"))
                require(caText.length <= 16_384)
                val ca = CertificateFactory.getInstance("X.509").generateCertificate(
                    Base64.decode(caText, Base64.DEFAULT).inputStream()
                ) as X509Certificate
                EchPeerTrust.TrustAnchors(listOf(ca))
            }
            stage = "NATIVE_AVAILABILITY"
            Conscrypt.checkAvailability()
            result.put("nativeAvailable", true)
            val version = Conscrypt.version()
            result.put("conscrypt", "${version.major()}.${version.minor()}.${version.patch()}")
            stage = "RAW_LOOPBACK_CONNECT"
            raw = Socket()
            raw.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 1500)
            if (expected == "CANCELLED") scheduler.schedule({ cancellation.cancel() }, 200, TimeUnit.MILLISECONDS)
            stage = "CONNECTOR"
            ConscryptEchConnector().connect(
                raw, config, "inner.example", "verify.example", trust, timeout, cancellation
            ).use { connection ->
                stage = "VERIFIED_CONNECTION"
                val socket = connection.socket
                socket.soTimeout = 3000
                result.put("status", connection.echStatus.name)
                    .put("protocol", socket.session.protocol)
                    .put("providerSocket", Conscrypt.isConscrypt(socket))
                stage = "APPLICATION_WRITE"
                socket.outputStream.write(
                    "GET $PATH HTTP/1.1\r\nHost: inner.example\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                socket.outputStream.flush()
                stage = "APPLICATION_READ"
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = socket.inputStream.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= 8192) { "Probe response exceeds bound" }
                    bytes.write(buffer, 0, count)
                }
                val response = bytes.toString("US-ASCII")
                val body = response.substringAfter("\r\n\r\n", "")
                val match = response.startsWith("HTTP/1.1 200 ") &&
                    body == "GO-ECH GET $PATH HTTP/1.1\r\n"
                result.put("responseMatch", match)
                result.put("pass", expected == "accepted" && match)
                result.put("stage", "COMPLETE")
            }
        } catch (failure: Throwable) {
            // Only stage and cause class names; no messages, configs, certificates or stack traces.
            val category = if (failure is EchConnectionException) failure.failure.name else failure.javaClass.simpleName
            val diagnostic = EchDiagnostics.capture(failure, stage)
            val causes = JSONArray()
            diagnostic.causeClasses.forEach {
                causes.put(JSONObject().put("simpleName", it.simpleName).put("fullName", it.fullName))
            }
            result.put("failure", category).put("pass", expected == category)
                .put("stage", diagnostic.stage).put("causeClasses", causes)
                .put("causeChainTruncated", diagnostic.causeChainTruncated)
        } finally {
            runCatching { raw?.close() }
            scheduler.shutdownNow()
            val unchanged = providers == Security.getProviders().map { it.name } &&
                defaults === SSLContext.getDefault() &&
                httpsFactory === HttpsURLConnection.getDefaultSSLSocketFactory() &&
                verifier === HttpsURLConnection.getDefaultHostnameVerifier()
            result.put("globalDefaultsUnchanged", unchanged)
            if (!unchanged) result.put("pass", false)
            result.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        }
        return result
    }
}
