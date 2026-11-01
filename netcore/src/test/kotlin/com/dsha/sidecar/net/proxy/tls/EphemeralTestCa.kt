package com.dsha.sidecar.net.proxy.tls

import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter

/**
 * Reusable from sibling JVM proxy tests: EphemeralTestCa.create().authority().
 * Everything is generated in process memory; no application CA, resource, file, or trust-store mutation.
 */
internal class EphemeralTestCa private constructor(
    val certificate: X509Certificate,
    private val key: PrivateKey,
) {
    val certificatePem: String get() = pem("CERTIFICATE", certificate.encoded)

    fun privateKeyPem(pkcs1: Boolean = false): String = if (pkcs1) {
        pem("RSA PRIVATE KEY", PrivateKeyInfo.getInstance(key.encoded).parsePrivateKey().toASN1Primitive().encoded)
    } else {
        pem("PRIVATE KEY", key.encoded)
    }

    fun authority(
        clock: Clock = Clock.systemUTC(),
        leafLifetime: Duration = Duration.ofHours(24),
        backdate: Duration = Duration.ofMinutes(5),
    ): MitmCertificateAuthority = MitmCertificateAuthority.fromPem(
        certificatePem, privateKeyPem(), clock, leafLifetime, backdate,
    )

    companion object {
        val provider = BouncyCastleProvider()
        val referenceTime: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        private val serial = AtomicLong(1)

        fun keyPair(bits: Int = 2048): KeyPair = KeyPairGenerator.getInstance("RSA", provider).apply {
            initialize(bits)
        }.generateKeyPair()

        fun create(
            keys: KeyPair = keyPair(),
            notBefore: Instant = referenceTime.minus(Duration.ofDays(1)),
            notAfter: Instant = referenceTime.plus(Duration.ofDays(30)),
            ca: Boolean = true,
            keyUsage: Int? = KeyUsage.keyCertSign or KeyUsage.cRLSign,
            basicCritical: Boolean = true,
            issuer: X500Name = X500Name("CN=Ephemeral test root"),
            signingKeys: KeyPair = keys,
            eku: Array<KeyPurposeId>? = null,
            signatureAlgorithm: String = "SHA256withRSA",
            extensions: (JcaX509v3CertificateBuilder) -> Unit = {},
        ): EphemeralTestCa {
            val builder = JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(serial.getAndIncrement()), Date.from(notBefore), Date.from(notAfter),
                X500Name("CN=Ephemeral test root"), keys.public,
            )
            builder.addExtension(Extension.basicConstraints, basicCritical, if (ca) BasicConstraints(0) else BasicConstraints(false))
            keyUsage?.let { builder.addExtension(Extension.keyUsage, true, KeyUsage(it)) }
            eku?.let { builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(it)) }
            builder.addExtension(
                Extension.subjectKeyIdentifier, false, JcaX509ExtensionUtils().createSubjectKeyIdentifier(keys.public),
            )
            extensions(builder)
            val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
                builder.build(JcaContentSignerBuilder(signatureAlgorithm).setProvider(provider).build(signingKeys.private)),
            )
            return EphemeralTestCa(certificate, keys.private)
        }

        private fun pem(type: String, bytes: ByteArray): String {
            val output = StringWriter()
            PemWriter(output).use { it.writeObject(PemObject(type, bytes)) }
            return output.toString()
        }
    }
}

internal class MutableCertificateClock(initial: Instant) : Clock() {
    @Volatile var current: Instant = initial
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
}
