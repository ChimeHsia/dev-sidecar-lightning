package com.dsha.nativecorecheck

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.dsha.sidecar.net.proxy.http.Http1HeadCodec
import com.dsha.sidecar.net.proxy.http.Http1Reader
import com.dsha.sidecar.net.proxy.native.NativeProxyOptions
import com.dsha.sidecar.net.proxy.tls.MitmCertificateAuthority
import com.dsha.sidecar.net.rules.native.RulePlanner
import com.dsha.sidecar.net.runtime.NativeRuntime
import com.dsha.sidecar.net.runtime.NativeRuntimeOptions
import com.dsha.sidecar.net.runtime.NativeRuntimeState
import java.io.StringWriter
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.conscrypt.Conscrypt

class NativeCoreInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(Conscrypt.isAvailable()) { "Conscrypt native library unavailable" }
            checkNamedGroups()
            exerciseRuntime()
            result.putString("stream", "\nNative core HTTP, CONNECT/MITM and lifecycle: PASS\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "\nNative core check FAILED:\n${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkNamedGroups() {
        val rules = RulePlanner(listOf(mapOf("intercepts" to mapOf(
            "browser.test" to mapOf(
                "^/item/(?<id>[0-9]+)$" to mapOf(
                    "replace" to "^/item/(?<number>[0-9]+)$",
                    "proxy" to "https://mirror.test/\$<number>/\${id}",
                ),
            ),
        ))))
        val plan = rules.plan("browser.test", "/item/42")
        check(plan.routeOverride?.path == "/42/42") {
            "Unexpected named-capture route: ${plan.routeOverride}; issues: ${plan.issues}; selected: ${plan.selected}"
        }
    }

    private fun exerciseRuntime() {
        val provider = BouncyCastleProvider()
        val keys = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Native core temporary test CA")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.ONE, Date(now - 60_000), Date(now + 86_400_000), name, keys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keys.private)
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
        val authority = MitmCertificateAuthority.fromPem(
            pem("CERTIFICATE", certificate.encoded), pem("PRIVATE KEY", keys.private.encoded),
        )
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("test-ca", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val clientTls = SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
        val config = mapOf("server" to mapOf("intercepts" to mapOf(
            "127.0.0.1" to mapOf("^/success$" to mapOf("success" to mapOf("text" to "device-check"))),
        )))
        val runtime = NativeRuntime.fromDecoded(
            config, authority, NativeRuntimeOptions(proxy = NativeProxyOptions(readTimeoutMillis = 5_000)),
        )
        try {
            check(runtime.state == NativeRuntimeState.NEW)
            val address = runtime.start()
            check(runtime.state == NativeRuntimeState.LISTENING)
            Socket().use { socket ->
                socket.connect(address, 5_000)
                socket.soTimeout = 10_000
                send(socket, "GET /success HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                checkResponse(socket)
            }
            Socket().use { socket ->
                socket.connect(address, 5_000)
                socket.soTimeout = 10_000
                send(socket, "CONNECT 127.0.0.1:443 HTTP/1.1\r\nHost: 127.0.0.1:443\r\n\r\n")
                val response = Http1HeadCodec.readResponse(socket.getInputStream(), "CONNECT")
                check(response?.head?.status == 200) { "CONNECT failed: $response" }
                (clientTls.socketFactory.createSocket(socket, "127.0.0.1", 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = 10_000
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    send(tls, "GET /success HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    checkResponse(tls)
                }
            }
        } finally {
            runtime.close()
        }
        check(runtime.state == NativeRuntimeState.STOPPED)
    }

    private fun checkResponse(socket: Socket) {
        val reader = Http1Reader(socket.getInputStream())
        check(reader.readResponse("GET")?.head?.status == 200)
        val body = ByteArrayOutputStream()
        reader.transferBody(body)
        check(body.toString("UTF-8") == "device-check")
    }

    private fun send(socket: Socket, request: String) {
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
    }

    private fun pem(type: String, bytes: ByteArray): String {
        val writer = StringWriter()
        PemWriter(writer).use { it.writeObject(PemObject(type, bytes)) }
        return writer.toString()
    }
}
