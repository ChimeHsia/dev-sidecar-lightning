package com.dsha.sidecar.net.proxy.ech

import org.junit.Assert.*
import org.junit.Test

class NativeTlsCryptoTest {
    private val empty = ByteArray(0)
    private val hash = TlsHash.SHA256
    private val oracle = fixture("upstream")

    @Test
    fun rfc5869Sha256Case1() {
        val prk = NativeTlsCrypto.hkdfExtract(
            hash, hex("000102030405060708090a0b0c"), ByteArray(22) { 0x0b }
        )
        equalHex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk)
        equalHex(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            NativeTlsCrypto.hkdfExpand(hash, prk, hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        )
    }

    @Test
    fun rfc5869Sha256Case2() {
        val prk = NativeTlsCrypto.hkdfExtract(
            hash, ByteArray(80) { (0x60 + it).toByte() }, ByteArray(80) { it.toByte() }
        )
        equalHex("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", prk)
        equalHex(
            """
            b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c
            59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71
            cc30c58179ec3e87c14c01d5c1f3434f1d87
            """,
            NativeTlsCrypto.hkdfExpand(hash, prk, ByteArray(80) { (0xb0 + it).toByte() }, 82)
        )
    }

    @Test
    fun rfc5869Sha256Case3EmptySaltAndInfo() {
        val prk = NativeTlsCrypto.hkdfExtract(hash, empty, ByteArray(22) { 0x0b })
        equalHex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", prk)
        equalHex(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            NativeTlsCrypto.hkdfExpand(hash, prk, empty, 42)
        )
    }

    @Test
    fun rfc8448Section3ClientHandshakeSecretKeyAndIv() {
        val secret = NativeTlsCrypto.deriveSecretFromHash(
            hash,
            hex("1dc826e93606aa6fdc0aadc12f741b01046aa6b99f691ed221a9f0ca043fbeac"),
            "c hs traffic",
            hex("860c06edc07858ee8e78f0e7428c58edd6b43f2ca3e6e95f02ed063cf0e1cad8")
        )
        equalHex("b3eddb126e067f35a780b3abf45e2d8f3b1a950738f52e9600746a0e27a55a21", secret)
        equalHex("dbfaa693d1762c5b666af5d950258d01", NativeTlsCrypto.hkdfExpandLabel(hash, secret, "key", empty, 16))
        equalHex("5bd3c71b836e0b76bb73265f", NativeTlsCrypto.hkdfExpandLabel(hash, secret, "iv", empty, 12))
    }

    @Test
    fun upstreamSha256AndSha384HkdfAndAcceptConfirmationHelpers() {
        for ((hash, name) in listOf(TlsHash.SHA256 to "sha256", TlsHash.SHA384 to "sha384")) {
            val secret = oracle.bytes("$name.secret")
            val messages = oracle.bytes("$name.transcript")
            assertArrayEquals(oracle.bytes("$name.digest"), NativeTlsCrypto.hash(hash, messages))
            assertArrayEquals(
                oracle.bytes("$name.extract"),
                NativeTlsCrypto.hkdfExtract(hash, oracle.bytes("$name.salt"), secret)
            )
            assertArrayEquals(
                oracle.bytes("$name.expand"), NativeTlsCrypto.hkdfExpand(hash, secret, messages, hash.size + 17)
            )
            assertArrayEquals(
                oracle.bytes("$name.derive"), NativeTlsCrypto.deriveSecret(hash, secret, "c hs traffic", messages)
            )
            assertArrayEquals(
                oracle.bytes("$name.derive"),
                NativeTlsCrypto.deriveSecretFromHash(hash, secret, "c hs traffic", oracle.bytes("$name.digest"))
            )
            assertArrayEquals(
                oracle.bytes("$name.key"),
                NativeTlsCrypto.hkdfExpandLabel(hash, secret, "key", empty, if (hash == TlsHash.SHA256) 16 else 32)
            )
            assertArrayEquals(
                oracle.bytes("$name.iv"), NativeTlsCrypto.hkdfExpandLabel(hash, secret, "iv", empty, 12)
            )
            val extracted = NativeTlsCrypto.hkdfExtract(hash, ByteArray(hash.size), ByteArray(32) { 0x42 })
            assertArrayEquals(
                oracle.bytes("$name.accept_confirmation"),
                NativeTlsCrypto.hkdfExpandLabel(
                    hash, extracted, "ech accept confirmation", oracle.bytes("$name.digest"), 8
                )
            )
        }
    }

    @Test
    fun upstreamAllThreeAeadsWithNonzeroHighSequence() {
        for ((algorithm, name) in listOf(
            TlsAead.AES_128_GCM to "aes-128-gcm",
            TlsAead.AES_256_GCM to "aes-256-gcm",
            TlsAead.CHACHA20_POLY1305 to "chacha20-poly1305"
        )) {
            val nonce = NativeTlsCrypto.buildNonce(oracle.bytes("$name.iv"), oracle.getProperty("sequence").toLong())
            assertArrayEquals(oracle.bytes("$name.nonce"), nonce)
            val ciphertext = NativeTlsCrypto.aeadSeal(
                algorithm, oracle.bytes("$name.key"), nonce, oracle.bytes("aad"), oracle.bytes("plaintext")
            )
            assertArrayEquals(oracle.bytes("$name.ct"), ciphertext)
            assertArrayEquals(
                oracle.bytes("plaintext"),
                NativeTlsCrypto.aeadOpen(algorithm, oracle.bytes("$name.key"), nonce, oracle.bytes("aad"), ciphertext)
            )
        }
    }

    @Test
    fun rfc8439Section282ChaCha20Poly1305KnownAnswer() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray(Charsets.US_ASCII)
        val expected = hex(
            """
            d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6
            3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36
            92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3
            ff4def08e4b7a9de576d26586cec64b6116
            1ae10b594f09e26a7e902ecbd0600691
            """
        )
        assertArrayEquals(expected, NativeTlsCrypto.aeadSeal(TlsAead.CHACHA20_POLY1305, key, nonce, aad, plaintext))
        assertArrayEquals(plaintext, NativeTlsCrypto.aeadOpen(TlsAead.CHACHA20_POLY1305, key, nonce, aad, expected))
    }

    @Test
    fun allAeadsRejectWrongKeyNonceAadCiphertextTagAndTruncation() {
        for (algorithm in TlsAead.entries) {
            val key = ByteArray(algorithm.keySize)
            val nonce = ByteArray(12)
            val aad = byteArrayOf(1, 2)
            val ciphertext = NativeTlsCrypto.aeadSeal(algorithm, key, nonce, aad, byteArrayOf(3, 4))
            assertThrows(AuthenticationException::class.java) {
                NativeTlsCrypto.aeadOpen(algorithm, key.copyOf().apply { this[0] = 1 }, nonce, aad, ciphertext)
            }
            assertThrows(AuthenticationException::class.java) {
                NativeTlsCrypto.aeadOpen(algorithm, key, nonce.copyOf().apply { this[0] = 1 }, aad, ciphertext)
            }
            assertThrows(AuthenticationException::class.java) {
                NativeTlsCrypto.aeadOpen(algorithm, key, nonce, aad + 0, ciphertext)
            }
            for (offset in listOf(0, ciphertext.lastIndex)) {
                val damaged = ciphertext.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
                assertThrows(AuthenticationException::class.java) {
                    NativeTlsCrypto.aeadOpen(algorithm, key, nonce, aad, damaged)
                }
            }
            for (size in listOf(0, 15, 16, ciphertext.size - 1)) {
                assertThrows(AuthenticationException::class.java) {
                    NativeTlsCrypto.aeadOpen(algorithm, key, nonce, aad, ciphertext.copyOf(size))
                }
            }
        }
    }

    @Test
    fun aeadValidatesKeyNonceAndSupportsEmptyPlaintext() {
        for (algorithm in TlsAead.entries) {
            val key = ByteArray(algorithm.keySize)
            val nonce = ByteArray(12)
            for (size in listOf(0, algorithm.keySize - 1, algorithm.keySize + 1)) {
                assertThrows(IllegalArgumentException::class.java) {
                    NativeTlsCrypto.aeadSeal(algorithm, ByteArray(size), nonce, empty, empty)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    NativeTlsCrypto.aeadOpen(algorithm, ByteArray(size), nonce, empty, ByteArray(16))
                }
            }
            for (size in listOf(0, 11, 13)) {
                assertThrows(IllegalArgumentException::class.java) {
                    NativeTlsCrypto.aeadSeal(algorithm, key, ByteArray(size), empty, empty)
                }
            }
            val sealed = NativeTlsCrypto.aeadSeal(algorithm, key, nonce, empty, empty)
            assertEquals(16, sealed.size)
            assertArrayEquals(empty, NativeTlsCrypto.aeadOpen(algorithm, key, nonce, empty, sealed))
        }
    }

    @Test
    fun hkdfLengthAndTlsLabelEncodingBoundaries() {
        for (hash in TlsHash.entries) {
            val prk = ByteArray(hash.size)
            assertEquals(0, NativeTlsCrypto.hkdfExpand(hash, prk, empty, 0).size)
            assertEquals(255 * hash.size, NativeTlsCrypto.hkdfExpand(hash, prk, empty, 255 * hash.size).size)
            for (length in listOf(-1, 255 * hash.size + 1)) {
                assertThrows(IllegalArgumentException::class.java) {
                    NativeTlsCrypto.hkdfExpand(hash, prk, empty, length)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                NativeTlsCrypto.hkdfExpand(hash, ByteArray(hash.size - 1), empty, 1)
            }
            assertEquals(1, NativeTlsCrypto.hkdfExpandLabel(hash, prk, "x".repeat(249), ByteArray(255), 1).size)
            for (label in listOf("", "x".repeat(250), "\u00e9")) {
                assertThrows(IllegalArgumentException::class.java) {
                    NativeTlsCrypto.hkdfExpandLabel(hash, prk, label, empty, 1)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                NativeTlsCrypto.hkdfExpandLabel(hash, prk, "key", ByteArray(256), 1)
            }
            for (length in listOf(-1, 65536)) {
                assertThrows(IllegalArgumentException::class.java) {
                    NativeTlsCrypto.hkdfExpandLabel(hash, prk, "key", empty, length)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                NativeTlsCrypto.deriveSecretFromHash(hash, prk, "derived", ByteArray(hash.size - 1))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Hpke.labeledExpand(Hpke.Domain.HPKE, ByteArray(32), "key", empty, 8161)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Hpke.labeledExtract(Hpke.Domain.KEM, empty, "\u00e9", empty)
        }
    }

    @Test
    fun nonceXorCarriesHighBitsAndDoesNotMutateIv() {
        val iv = ByteArray(12) { 0xaa.toByte() }
        equalHex("aaaaaaaaaaaaaaaaaaaaaaaa", NativeTlsCrypto.buildNonce(iv, 0))
        equalHex("aaaaaaaaaaaaaaaaaaaaaaab", NativeTlsCrypto.buildNonce(iv, 1))
        equalHex("aaaaaaaaaaaaaaabaaaaaaaa", NativeTlsCrypto.buildNonce(iv, 0x100000000L))
        equalHex("aaaaaaaad555555555555555", NativeTlsCrypto.buildNonce(iv, Long.MAX_VALUE))
        val nonce = NativeTlsCrypto.buildNonce(iv, 256)
        nonce.fill(0)
        equalHex("aaaaaaaaaaaaaaaaaaaaaaaa", iv)
        assertThrows(IllegalArgumentException::class.java) { NativeTlsCrypto.buildNonce(iv, -1) }
        assertThrows(IllegalArgumentException::class.java) { NativeTlsCrypto.buildNonce(ByteArray(11), 0) }
    }

    @Test
    fun primitiveOutputsAreDetachedFromInputsAndLaterCalls() {
        val prk = ByteArray(32) { it.toByte() }
        val info = byteArrayOf(1, 2, 3)
        val first = NativeTlsCrypto.hkdfExpand(hash, prk, info, 32)
        val expected = first.copyOf()
        first.fill(0)
        assertArrayEquals(expected, NativeTlsCrypto.hkdfExpand(hash, prk, info, 32))
        assertArrayEquals(ByteArray(32) { it.toByte() }, prk)
        assertArrayEquals(byteArrayOf(1, 2, 3), info)
        val key = oracle.bytes("aes-128-gcm.key")
        val nonce = oracle.bytes("aes-128-gcm.nonce")
        val aad = oracle.bytes("aad")
        val plaintext = oracle.bytes("plaintext")
        val sealed = NativeTlsCrypto.aeadSeal(TlsAead.AES_128_GCM, key, nonce, aad, plaintext)
        val opened = NativeTlsCrypto.aeadOpen(TlsAead.AES_128_GCM, key, nonce, aad, sealed)
        opened.fill(0)
        assertArrayEquals(oracle.bytes("plaintext"), plaintext)
        assertArrayEquals(oracle.bytes("aes-128-gcm.ct"), sealed)
        assertArrayEquals(oracle.bytes("aes-128-gcm.key"), key)
        assertArrayEquals(oracle.bytes("aes-128-gcm.nonce"), nonce)
        assertArrayEquals(oracle.bytes("aad"), aad)
    }
}
