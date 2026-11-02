package com.dsha.sidecar.net.proxy.tls

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.spec.RSAPrivateKeySpec
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import javax.net.ssl.X509KeyManager
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.GeneralSubtree
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.NameConstraints
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.junit.Assert.*
import org.junit.Test

class MitmCertificateAuthorityTest {
    private val now = EphemeralTestCa.referenceTime
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test fun `imports both RSA PEM formats without modifying global providers`() {
        val before = Security.getProviders().toList()
        val pkcs8 = fixture.authority(clock)
        val pkcs1 = MitmCertificateAuthority.fromPem(fixture.certificatePem, fixture.privateKeyPem(true), clock)
        assertEquals(pkcs8.identity, pkcs1.identity)
        assertEquals(64, pkcs8.identity.length)
        pkcs8.issue("localhost").sslContext()
        assertEquals(before, Security.getProviders().toList())
    }

    @Test fun `rejects malformed unsupported encrypted or multiple PEM blocks`() {
        val invalid = listOf(
            "", "not PEM", "prefix\n${fixture.privateKeyPem()}", fixture.privateKeyPem() + fixture.privateKeyPem(),
            fixture.privateKeyPem().replace("PRIVATE KEY", "ENCRYPTED PRIVATE KEY"),
            fixture.certificatePem,
        )
        invalid.forEach { key ->
            assertThrows(Exception::class.java) {
                MitmCertificateAuthority.fromPem(fixture.certificatePem, key, clock)
            }
        }
        listOf("", "garbage", fixture.certificatePem + fixture.certificatePem, fixture.privateKeyPem()).forEach { cert ->
            assertThrows(Exception::class.java) {
                MitmCertificateAuthority.fromPem(cert, fixture.privateKeyPem(), clock)
            }
        }
    }

    @Test fun `rejects key with different modulus`() {
        assertThrows(IllegalArgumentException::class.java) {
            MitmCertificateAuthority.fromPem(fixture.certificatePem, otherFixture.privateKeyPem(), clock)
        }
    }

    @Test fun `proves private key possession beyond modulus equality`() {
        val privateKey = keys.private as RSAPrivateKey
        val badPrivateKey = KeyFactory.getInstance("RSA", EphemeralTestCa.provider).generatePrivate(
            RSAPrivateKeySpec(privateKey.modulus, privateKey.privateExponent.add(BigInteger.TWO)),
        )
        val badKeys = java.security.KeyPair(keys.public, badPrivateKey)
        val invalid = EphemeralTestCa.create(keys = badKeys, signingKeys = keys)
        assertThrows(Exception::class.java) { invalid.authority(clock) }
    }

    @Test fun `requires CA constraints and explicit certificate signing usage`() {
        listOf(
            EphemeralTestCa.create(keys, ca = false),
            EphemeralTestCa.create(keys, keyUsage = null),
            EphemeralTestCa.create(keys, keyUsage = KeyUsage.digitalSignature),
            EphemeralTestCa.create(keys, basicCritical = false),
        ).forEach { ca ->
            assertThrows(IllegalArgumentException::class.java) { ca.authority(clock) }
        }
    }

    @Test fun `rejects expired future and exact expiry CAs`() {
        listOf(
            EphemeralTestCa.create(keys, notAfter = now.minusSeconds(1)),
            EphemeralTestCa.create(keys, notBefore = now.plusSeconds(1)),
            EphemeralTestCa.create(keys, notAfter = now),
        ).forEach { ca -> assertThrows(Exception::class.java) { ca.authority(clock) } }
    }

    @Test fun `rejects intermediate or incorrectly self signed CAs`() {
        assertThrows(IllegalArgumentException::class.java) {
            EphemeralTestCa.create(keys, issuer = X500Name("CN=Another issuer")).authority(clock)
        }
        assertThrows(Exception::class.java) {
            EphemeralTestCa.create(keys, signingKeys = otherKeys).authority(clock)
        }
    }

    @Test fun `rejects weak RSA and weak self signatures`() {
        assertThrows(IllegalArgumentException::class.java) {
            EphemeralTestCa.create(EphemeralTestCa.keyPair(1024)).authority(clock)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EphemeralTestCa.create(keys, signatureAlgorithm = "SHA1withRSA").authority(clock)
        }
    }

    @Test fun `rejects non RSA PKCS8 key and certificate`() {
        val ecKeys = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val ecCa = EphemeralTestCa.create(ecKeys, signingKeys = keys)
        assertThrows(IllegalArgumentException::class.java) { ecCa.authority(clock) }
        assertThrows(IllegalArgumentException::class.java) {
            MitmCertificateAuthority.fromPem(fixture.certificatePem, ecCa.privateKeyPem(), clock)
        }
    }

    @Test fun `requires CA EKU to allow TLS servers if present`() {
        assertThrows(IllegalArgumentException::class.java) {
            EphemeralTestCa.create(keys, eku = arrayOf(KeyPurposeId.id_kp_codeSigning)).authority(clock)
        }
        EphemeralTestCa.create(keys, eku = arrayOf(KeyPurposeId.id_kp_serverAuth)).authority(clock)
        EphemeralTestCa.create(keys, eku = arrayOf(KeyPurposeId.anyExtendedKeyUsage)).authority(clock)
    }

    @Test fun `rejects unsupported critical extensions and unenforced name constraints`() {
        assertThrows(IllegalArgumentException::class.java) {
            EphemeralTestCa.create(keys) {
                it.addExtension(ASN1ObjectIdentifier("1.2.3.4.5"), true, DERNull.INSTANCE)
            }.authority(clock)
        }
        for (critical in listOf(true, false)) {
            assertThrows(IllegalArgumentException::class.java) {
                EphemeralTestCa.create(keys) {
                    it.addExtension(
                        Extension.nameConstraints, critical,
                        NameConstraints(arrayOf(GeneralSubtree(GeneralName(GeneralName.dNSName, "example.test"))), null),
                    )
                }.authority(clock)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            EphemeralTestCa.create(keys) {
                it.addExtension(Extension.inhibitAnyPolicy, false, org.bouncycastle.asn1.ASN1Integer(0))
            }.authority(clock)
        }
    }

    @Test fun `leaf contains narrow X509 usage identifiers and a verifiable chain`() {
        val ca = fixture.authority(clock)
        val leaf = ca.issue("LOCALHOST.")
        val cert = leaf.certificate
        assertEquals("localhost", leaf.host)
        assertEquals(ca.identity, leaf.authorityIdentity)
        assertEquals(listOf(cert, ca.certificate), leaf.certificateChain)
        cert.verify(ca.certificate.publicKey)
        assertEquals(ca.certificate.subjectX500Principal, cert.issuerX500Principal)
        assertEquals(-1, cert.basicConstraints)
        assertTrue(cert.keyUsage[0])
        assertTrue(cert.keyUsage[2])
        assertFalse(cert.keyUsage[5])
        assertEquals(listOf(KeyPurposeId.id_kp_serverAuth.id), cert.extendedKeyUsage)
        assertTrue(cert.criticalExtensionOIDs.containsAll(listOf(Extension.basicConstraints.id, Extension.keyUsage.id)))
        assertEquals(listOf(listOf(2, "localhost")), cert.subjectAlternativeNames.toList())
        val aki = AuthorityKeyIdentifier.getInstance(JcaX509ExtensionUtils.parseExtensionValue(
            cert.getExtensionValue(Extension.authorityKeyIdentifier.id),
        ))
        val issuerSki = SubjectKeyIdentifier.getInstance(JcaX509ExtensionUtils.parseExtensionValue(
            ca.certificate.getExtensionValue(Extension.subjectKeyIdentifier.id),
        ))
        assertArrayEquals(issuerSki.keyIdentifier, aki.keyIdentifier)
        val ski = SubjectKeyIdentifier.getInstance(JcaX509ExtensionUtils.parseExtensionValue(
            cert.getExtensionValue(Extension.subjectKeyIdentifier.id),
        ))
        assertArrayEquals(JcaX509ExtensionUtils().createSubjectKeyIdentifier(cert.publicKey).keyIdentifier, ski.keyIdentifier)
    }

    @Test fun `AKI uses declared issuer SKI and derives it when absent`() {
        val customSki = byteArrayOf(1, 2, 3, 4, 5)
        val custom = EphemeralTestCa.create(keys) {
            it.replaceExtension(Extension.subjectKeyIdentifier, false, SubjectKeyIdentifier(customSki))
        }.authority(clock)
        val absent = EphemeralTestCa.create(keys) {
            it.removeExtension(Extension.subjectKeyIdentifier)
        }.authority(clock)
        for ((ca, expected) in listOf(
            custom to customSki,
            absent to JcaX509ExtensionUtils().createSubjectKeyIdentifier(keys.public).keyIdentifier,
        )) {
            val aki = AuthorityKeyIdentifier.getInstance(JcaX509ExtensionUtils.parseExtensionValue(
                ca.issue("localhost").certificate.getExtensionValue(Extension.authorityKeyIdentifier.id),
            ))
            assertArrayEquals(expected, aki.keyIdentifier)
        }
    }

    @Test fun `SANs distinguish DNS IPv4 IPv6 and IPv4 mapped IPv6`() {
        val ca = fixture.authority(clock)
        val dns = ca.issue("EXAMPLE.test.")
        assertEquals("example.test", dns.host)
        assertEquals(listOf(listOf(2, "example.test")), dns.certificate.subjectAlternativeNames.toList())
        for ((host, expectedBytes) in listOf("127.0.0.1" to 4, "::1" to 16, "::ffff:127.0.0.1" to 16)) {
            val leaf = ca.issue(host)
            val names = GeneralNames.getInstance(JcaX509ExtensionUtils.parseExtensionValue(
                leaf.certificate.getExtensionValue(Extension.subjectAlternativeName.id),
            )).names
            assertEquals(1, names.size)
            assertEquals(GeneralName.iPAddress, names[0].tagNo)
            assertEquals(expectedBytes, (names[0].name as ASN1OctetString).octets.size)
        }
    }

    @Test fun `DNS parser supports localhost A labels and long SAN identities`() {
        assertEquals("localhost", CertificateHost.parse("LocalHost.").canonicalName)
        assertEquals("xn--bcher-kva.test", CertificateHost.parse("XN--BCHER-KVA.test").canonicalName)
        val longest = List(3) { "a".repeat(63) }.plus("b".repeat(61)).joinToString(".")
        assertEquals(253, longest.length)
        assertEquals(longest, fixture.authority(clock).issue(longest).host)
        assertEquals(
            CertificateHost.parse("2001:db8::1").canonicalName,
            CertificateHost.parse("2001:0DB8:0:0:0:0:0:1").canonicalName,
        )
    }

    @Test fun `rejects invalid ambiguous URL wildcard Unicode and scoped identities`() {
        val invalid = listOf(
            "", " ", " localhost", "localhost ", "local\nhost", "https://example.test", "example.test:443",
            "*.example.test", ".example.test", "example..test", "example.test..", "-bad.test", "bad-.test",
            "bad_name.test", "b\u00fccher.test", "a".repeat(64) + ".test", "a.".repeat(128),
            "[::1]", "::1%1", "fe80::1%eth0", ":::1", "2001:db8::g", "::ffff:127.00.0.1",
            "127.1", "127.0.0.01", "256.1.1.1", "127.0.0.1.", "123", "example/test", "user@example.test",
        )
        val ca = fixture.authority(clock)
        invalid.forEach { host ->
            assertThrows("Accepted invalid identity", IllegalArgumentException::class.java) { ca.issue(host) }
        }
    }

    @Test fun `random serials and leaf keys are fresh positive and bounded`() {
        val ca = fixture.authority(clock)
        val leaves = List(3) { ca.issue("localhost") }
        assertEquals(3, leaves.map { it.certificate.serialNumber }.toSet().size)
        assertEquals(3, leaves.map { it.certificate.publicKey.encoded.toList() }.toSet().size)
        leaves.forEach {
            assertTrue(it.certificate.serialNumber.signum() > 0)
            assertTrue(it.certificate.serialNumber.toByteArray().size <= 20)
        }
    }

    @Test fun `leaf KeyManager matches public key and contexts are cached`() {
        val leaf = fixture.authority(clock).issue("localhost")
        val manager = leaf.keyManagers().filterIsInstance<X509KeyManager>().single()
        val alias = manager.getServerAliases("RSA", null).single()
        val bytes = "proof of leaf key".toByteArray()
        val proof = Signature.getInstance("SHA256withRSA").run {
            initSign(manager.getPrivateKey(alias))
            update(bytes)
            sign()
        }
        assertTrue(Signature.getInstance("SHA256withRSA").run {
            initVerify(leaf.certificate.publicKey)
            update(bytes)
            verify(proof)
        })
        assertArrayEquals(leaf.certificateChain.toTypedArray(), manager.getCertificateChain(alias))
        assertSame(leaf.sslContext, leaf.sslContext())
        val managers = leaf.keyManagers()
        managers[0] = object : javax.net.ssl.KeyManager {}
        assertSame(manager, leaf.keyManagers()[0])
        val engine = leaf.newServerEngine()
        assertFalse(engine.useClientMode)
        assertEquals(setOf("TLSv1.2", "TLSv1.3"), engine.enabledProtocols.toSet())
        assertFalse(engine.needClientAuth)
    }

    @Test fun `leaf dates stay within issuer bounds and configured lifetime`() {
        val ca = EphemeralTestCa.create(keys, notBefore = now, notAfter = now.plusSeconds(120)).authority(clock)
        val cert = ca.issue("localhost").certificate
        assertEquals(now, cert.notBefore.toInstant())
        assertEquals(now.plusSeconds(120), cert.notAfter.toInstant())
        val short = fixture.authority(clock, leafLifetime = Duration.ofMinutes(10), backdate = Duration.ZERO)
            .issue("localhost").certificate
        assertEquals(now, short.notBefore.toInstant())
        assertEquals(now.plusSeconds(600), short.notAfter.toInstant())
        val defaultLeaf = fixture.authority(clock).issue("localhost").certificate
        assertEquals(now.minusSeconds(300), defaultLeaf.notBefore.toInstant())
        assertEquals(now.plus(Duration.ofHours(24)), defaultLeaf.notAfter.toInstant())
    }

    @Test fun `issuer time checked at issuance after import and subsecond expiry rejected`() {
        val mutable = MutableCertificateClock(now)
        val ca = EphemeralTestCa.create(keys, notAfter = now.plusSeconds(1)).authority(mutable)
        mutable.current = now.plusSeconds(1)
        assertThrows(Exception::class.java) { ca.issue("localhost") }
        mutable.current = now.plusMillis(500)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.authority(mutable, leafLifetime = Duration.ofMillis(100)).issue("localhost")
        }
    }

    @Test fun `invalid leaf policy durations rejected before issuance`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(8)).forEach {
            assertThrows(IllegalArgumentException::class.java) { fixture.authority(clock, leafLifetime = it) }
        }
        listOf(Duration.ofSeconds(-1), Duration.ofMinutes(6)).forEach {
            assertThrows(IllegalArgumentException::class.java) { fixture.authority(clock, backdate = it) }
        }
    }

    companion object {
        private val keys by lazy { EphemeralTestCa.keyPair() }
        private val otherKeys by lazy { EphemeralTestCa.keyPair() }
        private val fixture by lazy { EphemeralTestCa.create(keys) }
        private val otherFixture by lazy { EphemeralTestCa.create(otherKeys) }
    }
}
