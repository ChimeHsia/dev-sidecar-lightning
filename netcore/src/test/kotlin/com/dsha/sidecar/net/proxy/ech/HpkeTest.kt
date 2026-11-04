package com.dsha.sidecar.net.proxy.ech

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HpkeTest {
    private val v = fixture("cfrg-base-x25519-aes128")
    private val empty = ByteArray(0)

    private fun sender(sequence: Long = 0): Hpke.Sender = Hpke.setupBaseSenderForTesting(
        v.bytes("pkRm"), v.bytes("info"), v.bytes("skEm"), sequence
    )

    private fun recipient(): Hpke.Recipient =
        Hpke.setupBaseRecipient(v.bytes("enc"), v.bytes("skRm"), v.bytes("info"))

    @Test
    fun officialCfrg257SealAndOpenKnownAnswers() {
        sender().use { sender ->
            recipient().use { recipient ->
                assertArrayEquals(v.bytes("enc"), sender.enc)
                val count = v.getProperty("encryption_count").toInt()
                assertEquals(257, count)
                repeat(count) { index ->
                    val prefix = "encryption.$index."
                    val ciphertext = sender.seal(v.bytes(prefix + "aad"), v.bytes(prefix + "pt"))
                    assertArrayEquals("official seal $index", v.bytes(prefix + "ct"), ciphertext)
                    assertArrayEquals(
                        "official open $index", v.bytes(prefix + "pt"),
                        recipient.open(v.bytes(prefix + "aad"), v.bytes(prefix + "ct"))
                    )
                    assertArrayEquals(
                        "official nonce $index", v.bytes(prefix + "nonce"),
                        NativeTlsCrypto.buildNonce(v.bytes("base_nonce"), index.toLong())
                    )
                }
                assertEquals(count.toLong(), sender.sequenceNumber)
                assertEquals(count.toLong(), recipient.sequenceNumber)
            }
        }
    }

    @Test
    fun officialLabeledKeyScheduleAndDomainSeparation() {
        val context = byteArrayOf(0) +
            Hpke.labeledExtract(Hpke.Domain.HPKE, empty, "psk_id_hash", empty) +
            Hpke.labeledExtract(Hpke.Domain.HPKE, empty, "info_hash", v.bytes("info"))
        assertArrayEquals(v.bytes("key_schedule_context"), context)
        val secret = Hpke.labeledExtract(Hpke.Domain.HPKE, v.bytes("shared_secret"), "secret", empty)
        assertArrayEquals(v.bytes("secret"), secret)
        assertArrayEquals(v.bytes("key"), Hpke.labeledExpand(Hpke.Domain.HPKE, secret, "key", context, 16))
        assertArrayEquals(v.bytes("base_nonce"), Hpke.labeledExpand(Hpke.Domain.HPKE, secret, "base_nonce", context, 12))
        assertFalse(
            Hpke.labeledExtract(Hpke.Domain.KEM, empty, "info_hash", v.bytes("info"))
                .contentEquals(Hpke.labeledExtract(Hpke.Domain.HPKE, empty, "info_hash", v.bytes("info")))
        )
    }

    @Test
    fun ciphertextAadAndTagTamperingAuthenticateBeforeReturningAndDoNotAdvance() {
        recipient().use { recipient ->
            val ciphertext = v.bytes("encryption.0.ct")
            val aad = v.bytes("encryption.0.aad")
            for (offset in listOf(0, ciphertext.lastIndex)) {
                val damaged = ciphertext.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
                assertThrows(AuthenticationException::class.java) { recipient.open(aad, damaged) }
                assertEquals(0L, recipient.sequenceNumber)
            }
            assertThrows(AuthenticationException::class.java) { recipient.open(aad + 0, ciphertext) }
            assertThrows(AuthenticationException::class.java) { recipient.open(aad, ByteArray(15)) }
            assertThrows(AuthenticationException::class.java) {
                recipient.open(v.bytes("encryption.1.aad"), v.bytes("encryption.1.ct"))
            }
            assertEquals(0L, recipient.sequenceNumber)
            assertArrayEquals(v.bytes("encryption.0.pt"), recipient.open(aad, ciphertext))
            assertThrows(AuthenticationException::class.java) { recipient.open(aad, ciphertext) }
            assertEquals(1L, recipient.sequenceNumber)
        }
    }

    @Test
    fun wrongRecipientKeyInfoAndEncFailAuthentication() {
        val wrongKey = v.bytes("skRm").apply { this[2] = (this[2].toInt() xor 1).toByte() }
        val wrongEnc = v.bytes("enc").apply { this[4] = (this[4].toInt() xor 1).toByte() }
        val recipients = listOf(
            Hpke.setupBaseRecipient(v.bytes("enc"), wrongKey, v.bytes("info")),
            Hpke.setupBaseRecipient(v.bytes("enc"), v.bytes("skRm"), v.bytes("info") + 0),
            Hpke.setupBaseRecipient(wrongEnc, v.bytes("skRm"), v.bytes("info"))
        )
        recipients.forEach { recipient ->
            recipient.use {
                assertThrows(AuthenticationException::class.java) {
                    it.open(v.bytes("encryption.0.aad"), v.bytes("encryption.0.ct"))
                }
            }
        }
    }

    @Test
    fun invalidKeyLengthsAndLowOrderPublicKeysAreRejected() {
        for (size in listOf(0, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) {
                Hpke.setupBaseSender(ByteArray(size), empty)
            }
            assertThrows(IllegalArgumentException::class.java) {
                Hpke.setupBaseRecipient(v.bytes("enc"), ByteArray(size), empty)
            }
            assertThrows(IllegalArgumentException::class.java) {
                Hpke.setupBaseRecipient(ByteArray(size), v.bytes("skRm"), empty)
            }
        }
        // Zero, one, and p-1 are low-order coordinates; include RFC 7748 high-bit masking.
        val lowOrder = listOf(
            ByteArray(32),
            ByteArray(32).apply { this[0] = 1 },
            hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            ByteArray(32).apply { this[31] = 0x80.toByte() }
        )
        lowOrder.forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { Hpke.setupBaseSender(key, empty) }
            assertThrows(IllegalArgumentException::class.java) {
                Hpke.setupBaseRecipient(key, v.bytes("skRm"), empty)
            }
        }
    }

    @Test
    fun inputAndEncArraysAreDetached() {
        val publicKey = v.bytes("pkRm")
        val info = v.bytes("info")
        val ephemeral = v.bytes("skEm")
        val privateKey = v.bytes("skRm")
        val enc = v.bytes("enc")
        Hpke.setupBaseSenderForTesting(publicKey, info, ephemeral).use { sender ->
            Hpke.setupBaseRecipient(enc, privateKey, info).use { recipient ->
                for (input in listOf(publicKey, info, ephemeral, privateKey, enc, sender.enc)) input.fill(0)
                assertArrayEquals(v.bytes("enc"), sender.enc)
                val ciphertext = sender.seal(v.bytes("encryption.0.aad"), v.bytes("encryption.0.pt"))
                assertArrayEquals(v.bytes("encryption.0.ct"), ciphertext)
                assertArrayEquals(v.bytes("encryption.0.pt"), recipient.open(v.bytes("encryption.0.aad"), ciphertext))
                ciphertext.fill(0)
                assertArrayEquals(
                    v.bytes("encryption.1.ct"),
                    sender.seal(v.bytes("encryption.1.aad"), v.bytes("encryption.1.pt"))
                )
            }
        }
    }

    @Test
    fun exhaustionDoesNotWrapOrEmitAnotherCiphertext() {
        sender(Long.MAX_VALUE - 1).use { sender ->
            val aad = v.bytes("encryption.0.aad")
            val plaintext = v.bytes("encryption.0.pt")
            val expected = NativeTlsCrypto.aeadSeal(
                TlsAead.AES_128_GCM, v.bytes("key"),
                NativeTlsCrypto.buildNonce(v.bytes("base_nonce"), Long.MAX_VALUE - 1), aad, plaintext
            )
            assertArrayEquals(expected, sender.seal(aad, plaintext))
            repeat(3) { assertThrows(IllegalStateException::class.java) { sender.seal(aad, plaintext) } }
            assertEquals(Long.MAX_VALUE, sender.sequenceNumber)
        }
        sender(Long.MAX_VALUE).use {
            assertThrows(IllegalStateException::class.java) { it.seal(empty, empty) }
        }
        assertThrows(IllegalArgumentException::class.java) { sender(-1) }
    }

    @Test
    fun concurrentSealUsesEachSequenceExactlyOnce() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            sender().use { sender ->
                val count = 512
                val aad = byteArrayOf(1, 2, 3)
                val plaintext = byteArrayOf(4, 5, 6)
                val results = executor.invokeAll(List(count) { Callable { sender.seal(aad, plaintext).toList() } })
                    .map { it.get(10, TimeUnit.SECONDS) }.toSet()
                assertEquals(count, results.size)
                val expected = (0 until count).map { sequence ->
                    NativeTlsCrypto.aeadSeal(
                        TlsAead.AES_128_GCM, v.bytes("key"),
                        NativeTlsCrypto.buildNonce(v.bytes("base_nonce"), sequence.toLong()), aad, plaintext
                    ).toList()
                }.toSet()
                assertEquals(expected, results)
                assertEquals(count.toLong(), sender.sequenceNumber)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentExhaustionAllowsOnlyOneFinalSeal() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            sender(Long.MAX_VALUE - 1).use { sender ->
                val results = executor.invokeAll(List(32) {
                    Callable {
                        try {
                            sender.seal(empty, empty)
                            true
                        } catch (_: IllegalStateException) {
                            false
                        }
                    }
                }).map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it })
                assertEquals(Long.MAX_VALUE, sender.sequenceNumber)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun randomSenderRoundTripsAndUsesFreshEncapsulation() {
        Hpke.setupBaseSender(v.bytes("pkRm"), v.bytes("info")).use { first ->
            Hpke.setupBaseSender(v.bytes("pkRm"), v.bytes("info")).use { second ->
                assertFalse(first.enc.contentEquals(second.enc))
                Hpke.setupBaseRecipient(first.enc, v.bytes("skRm"), v.bytes("info")).use { receiver ->
                    assertArrayEquals(empty, receiver.open(empty, first.seal(empty, empty)))
                }
            }
        }
    }

    @Test
    fun closedContextsRejectOperationsAndCloseIsIdempotent() {
        val sender = sender()
        val recipient = recipient()
        repeat(2) { sender.close(); recipient.close() }
        assertThrows(IllegalStateException::class.java) { sender.seal(empty, empty) }
        assertThrows(IllegalStateException::class.java) { recipient.open(empty, empty) }
    }
}
