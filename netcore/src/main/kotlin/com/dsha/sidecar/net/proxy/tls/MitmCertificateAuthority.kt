package com.dsha.sidecar.net.proxy.tls

import java.io.StringReader
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * In-memory issuer for a supplied, self-signed RSA root. Does not load files, install trust,
 * register providers, or connect to an upstream server.
 */
class MitmCertificateAuthority private constructor(
    val certificate: X509Certificate,
    private val privateKey: PrivateKey,
    private val clock: Clock,
    private val leafLifetime: Duration,
    private val backdate: Duration,
) {
    /** SHA-256 of the complete issuer certificate, including its validity and constraints. */
    val identity: String = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val random = SecureRandom()

    internal fun now(): Instant = clock.instant()

    internal fun checkValidity() {
        val instant = now()
        certificate.checkValidity(Date.from(instant))
        require(instant.isBefore(certificate.notAfter.toInstant())) { "Issuer has expired" }
    }

    fun issue(host: String): LeafCertificate {
        val parsed = CertificateHost.parse(host)
        checkValidity()
        val keys = KeyPairGenerator.getInstance("RSA", provider).apply {
            initialize(2048, random)
        }.generateKeyPair()
        // Take the time after key generation, which may be slow on a future mobile runtime.
        checkValidity()
        val instant = now()
        val notBefore = maxOf(certificate.notBefore.toInstant(), instant.minus(backdate).truncatedTo(ChronoUnit.SECONDS))
        val notAfter = minOf(certificate.notAfter.toInstant(), instant.plus(leafLifetime).truncatedTo(ChronoUnit.SECONDS))
        require(notAfter.isAfter(instant) && notAfter.isAfter(notBefore)) { "No usable issuer validity remains" }
        // 159 random bits keep the DER INTEGER positive and within RFC 5280's 20-octet limit.
        var serial: BigInteger
        do {
            serial = BigInteger(159, random)
        } while (serial.signum() == 0)
        val extensions = JcaX509ExtensionUtils()
        val builder = JcaX509v3CertificateBuilder(
            certificate, serial, Date.from(notBefore), Date.from(notAfter),
            X500Name("CN=Native MITM leaf"), keys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(parsed.subjectAlternativeName))
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(keys.public))
        builder.addExtension(Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(certificate))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).setSecureRandom(random).build(privateKey)
        val leaf = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
        leaf.verify(certificate.publicKey, provider)
        checkValidity()
        leaf.checkValidity(Date.from(now()))
        return LeafCertificate(parsed.canonicalName, identity, leaf, certificate, keys.private).also {
            check(it.isUsableAt(now())) { "Leaf validity elapsed during generation" }
        }
    }

    companion object {
        // Passing this instance explicitly avoids replacing Android's BC or changing JVM globals.
        private val provider = BouncyCastleProvider()

        @JvmStatic
        @JvmOverloads
        fun fromPem(
            certificatePem: String,
            privateKeyPem: String,
            clock: Clock = Clock.systemUTC(),
            leafLifetime: Duration = Duration.ofHours(24),
            backdate: Duration = Duration.ofMinutes(5),
        ): MitmCertificateAuthority {
            require(!leafLifetime.isNegative && !leafLifetime.isZero && leafLifetime <= Duration.ofDays(7)) {
                "Leaf lifetime must be positive and at most seven days"
            }
            require(!backdate.isNegative && backdate <= Duration.ofMinutes(5)) {
                "Backdate must be between zero and five minutes"
            }
            val holder = parsePem(certificatePem, setOf("CERTIFICATE")) as? X509CertificateHolder
                ?: throw IllegalArgumentException("Expected one X.509 CA certificate")
            val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
            val parsedKey = parsePem(privateKeyPem, setOf("RSA PRIVATE KEY", "PRIVATE KEY"))
            val keyInfo = when (parsedKey) {
                is PEMKeyPair -> parsedKey.privateKeyInfo
                is PrivateKeyInfo -> parsedKey
                else -> throw IllegalArgumentException("Expected an unencrypted RSA PKCS#1 or PKCS#8 key")
            }
            val key = JcaPEMKeyConverter().setProvider(provider).getPrivateKey(keyInfo)
            val publicKey = certificate.publicKey as? RSAPublicKey
                ?: throw IllegalArgumentException("CA must use an RSA public key")
            require(key is RSAPrivateKey && publicKey.modulus.bitLength() >= 2048 &&
                key.modulus == publicKey.modulus) { "CA requires a matching RSA key of at least 2048 bits" }
            require(certificate.version == 3 && certificate.basicConstraints >= 0) { "Certificate is not a v3 CA" }
            require(certificate.subjectX500Principal.encoded.size > 2 &&
                certificate.subjectX500Principal == certificate.issuerX500Principal) { "Expected a self-issued root CA" }
            val usage = certificate.keyUsage
            require(usage != null && usage.size > 5 && usage[5]) { "CA must explicitly permit keyCertSign" }
            require(certificate.criticalExtensionOIDs?.contains(Extension.basicConstraints.id) == true) {
                "CA basicConstraints must be critical"
            }
            require(certificate.sigAlgOID in setOf(
                "1.2.840.113549.1.1.11", "1.2.840.113549.1.1.12", "1.2.840.113549.1.1.13",
            )) { "CA self-signature must use SHA-256/384/512 with RSA" }
            val eku = certificate.extendedKeyUsage
            require(eku == null || KeyPurposeId.id_kp_serverAuth.id in eku || KeyPurposeId.anyExtendedKeyUsage.id in eku) {
                "CA extended key usage does not permit TLS servers"
            }
            val supportedCritical = setOf(
                Extension.basicConstraints.id, Extension.keyUsage.id, Extension.extendedKeyUsage.id,
                Extension.subjectKeyIdentifier.id, Extension.authorityKeyIdentifier.id,
            )
            require(certificate.criticalExtensionOIDs.orEmpty().all { it in supportedCritical }) {
                "Unsupported critical CA extension"
            }
            val unsupportedConstraints = listOf(
                Extension.nameConstraints, Extension.policyConstraints, Extension.policyMappings, Extension.inhibitAnyPolicy,
            )
            require(unsupportedConstraints.none { certificate.getExtensionValue(it.id) != null }) {
                "Constrained CAs require a future path/constraint validation implementation"
            }
            certificate.verify(publicKey, provider)
            val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
            val proof = Signature.getInstance("SHA256withRSA", provider).run {
                initSign(key)
                update(challenge)
                sign()
            }
            require(Signature.getInstance("SHA256withRSA", provider).run {
                initVerify(publicKey)
                update(challenge)
                verify(proof)
            }) { "CA private key does not match certificate" }
            return MitmCertificateAuthority(certificate, key, clock, leafLifetime, backdate).also { it.checkValidity() }
        }

        private fun parsePem(pem: String, types: Set<String>): Any {
            val match = Regex(
                "\\A\\s*-----BEGIN ([A-Z0-9 #]+)-----\\s+([A-Za-z0-9+/=\\r\\n\\t ]+)-----END \\1-----\\s*\\z",
            ).matchEntire(pem)
            require(match != null && match.groupValues[1] in types) { "Expected exactly one supported PEM block" }
            return PEMParser(StringReader(pem)).use {
                val value = it.readObject()
                require(value != null && it.readObject() == null) { "Expected exactly one PEM object" }
                value
            }
        }
    }
}

/** One ephemeral leaf, its issuer chain, and lazily cached downstream JSSE credentials. */
class LeafCertificate internal constructor(
    val host: String,
    val authorityIdentity: String,
    val certificate: X509Certificate,
    private val issuer: X509Certificate,
    private val privateKey: PrivateKey,
) {
    val certificateChain: List<X509Certificate> get() = listOf(certificate, issuer)

    private val managers: Array<KeyManager> by lazy {
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("leaf", privateKey, password, certificateChain.toTypedArray())
        }
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, password)
        }.keyManagers
    }

    fun keyManagers(): Array<KeyManager> = managers.clone()

    /** Treat as shared/read-only: do not reinitialize or use it for upstream trust decisions. */
    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(managers, null, null) }
    }

    fun sslContext(): SSLContext = sslContext

    fun newServerEngine(): SSLEngine = sslContext.createSSLEngine().apply {
        useClientMode = false
        enabledProtocols = downstreamProtocols(supportedProtocols)
    }

    /** Unbound; the caller owns binding, timeouts, accepting connections, and closing. */
    fun newServerSocket(): SSLServerSocket = (sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
        useClientMode = false
        enabledProtocols = downstreamProtocols(supportedProtocols)
    }

    internal fun isUsableAt(instant: Instant, remaining: Duration = Duration.ZERO): Boolean =
        !instant.isBefore(certificate.notBefore.toInstant()) &&
            instant.plus(remaining).isBefore(certificate.notAfter.toInstant()) &&
            !instant.isBefore(issuer.notBefore.toInstant()) && instant.isBefore(issuer.notAfter.toInstant())

    private fun downstreamProtocols(supported: Array<String>): Array<String> =
        arrayOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray().also {
            check(it.isNotEmpty()) { "TLS 1.2 or 1.3 is required" }
        }
}
