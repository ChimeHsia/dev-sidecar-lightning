// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** Test-only BC instance and in-memory trust. No installed provider/CA or key files. */
internal object NativeDnsTestTls {
    private val provider = BouncyCastleProvider()
    private val serial = AtomicLong(1)
    private val rootKeys = keys()
    private val rootName = X500Name("CN=Isolated DNS test root")
    private val root = issue(rootName, rootKeys, ca = true)
    val client: SSLContext = SSLContext.getInstance("TLS").apply {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("root", root)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        init(null, managers.trustManagers, null)
    }

    fun server(dnsNames: List<String> = listOf("resolver.test"), ip: String? = null, expired: Boolean = false): SSLContext {
        val keys = keys()
        val leaf = issue(X500Name("CN=resolver.test"), keys, false, dnsNames, ip, expired)
        val password = "in-memory-only".toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("server", keys.private, password, arrayOf(leaf, root))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    }

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC", provider).apply { initialize(256) }.generateKeyPair()

    private fun issue(
        subject: X500Name,
        keys: KeyPair,
        ca: Boolean,
        dnsNames: List<String> = emptyList(),
        ip: String? = null,
        expired: Boolean = false,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val issuer = if (ca) subject else rootName
        val builder = JcaX509v3CertificateBuilder(
            issuer, BigInteger.valueOf(serial.getAndIncrement()),
            Date(now - 86_400_000), Date(if (expired) now - 3_600_000 else now + 86_400_000),
            subject, keys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(
            if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature,
        ))
        if (!ca) {
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            val sans = dnsNames.map { GeneralName(GeneralName.dNSName, it) } +
                listOfNotNull(ip?.let { GeneralName(GeneralName.iPAddress, it) })
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(rootKeys.private)
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
    }
}
