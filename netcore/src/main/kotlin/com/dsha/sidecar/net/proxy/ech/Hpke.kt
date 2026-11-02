package com.dsha.sidecar.net.proxy.ech

import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.Closeable
import java.security.SecureRandom

/**
 * Candidate RFC 9180 base-mode primitive for the upstream ECH profile only.
 * KEM 0x0020, KDF 0x0001, AEAD 0x0001. No ECH engine or TLS handshake.
 */
object Hpke {
    const val KEM_ID = 0x0020
    const val KDF_ID = 0x0001
    const val AEAD_ID = 0x0001

    enum class Domain {
        KEM,
        HPKE
    }

    private val random = SecureRandom()
    private val empty = ByteArray(0)

    private fun suite(domain: Domain): ByteArray = when (domain) {
        Domain.KEM -> NativeTlsCrypto.ascii("KEM") + NativeTlsCrypto.uint16(KEM_ID)
        Domain.HPKE -> NativeTlsCrypto.ascii("HPKE") + NativeTlsCrypto.uint16(KEM_ID) +
            NativeTlsCrypto.uint16(KDF_ID) + NativeTlsCrypto.uint16(AEAD_ID)
    }

    fun labeledExtract(domain: Domain, salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        NativeTlsCrypto.hkdfExtract(
            TlsHash.SHA256, salt,
            NativeTlsCrypto.ascii("HPKE-v1") + suite(domain) + NativeTlsCrypto.ascii(label) + ikm.copyOf()
        )

    fun labeledExpand(
        domain: Domain,
        prk: ByteArray,
        label: String,
        info: ByteArray,
        length: Int
    ): ByteArray = NativeTlsCrypto.hkdfExpand(
        TlsHash.SHA256, prk,
        NativeTlsCrypto.uint16(length) + NativeTlsCrypto.ascii("HPKE-v1") + suite(domain) +
            NativeTlsCrypto.ascii(label) + info.copyOf(),
        length
    )

    fun setupBaseSender(recipientPublicKey: ByteArray, info: ByteArray): Sender {
        val privateKey = ByteArray(X25519.SCALAR_SIZE)
        X25519.generatePrivateKey(random, privateKey)
        return try {
            setupSender(recipientPublicKey, info, privateKey, 0)
        } finally {
            privateKey.fill(0)
        }
    }

    /** Raw 32-byte X25519 recipient private key; caller retains ownership of its input. */
    fun setupBaseRecipient(enc: ByteArray, recipientPrivateKey: ByteArray, info: ByteArray): Recipient {
        val ephemeral = publicKeyCopy(enc)
        require(recipientPrivateKey.size == X25519.SCALAR_SIZE) { "X25519 private key must be 32 bytes" }
        val privateKey = recipientPrivateKey.copyOf()
        val publicKey = ByteArray(X25519.POINT_SIZE)
        try {
            X25519.generatePublicKey(privateKey, 0, publicKey, 0)
            val material = deriveMaterial(privateKey, ephemeral, ephemeral + publicKey, info.copyOf())
            return Recipient(material)
        } finally {
            privateKey.fill(0)
        }
    }

    internal fun setupBaseSenderForTesting(
        recipientPublicKey: ByteArray,
        info: ByteArray,
        ephemeralPrivateKey: ByteArray,
        initialSequence: Long = 0
    ): Sender = setupSender(recipientPublicKey, info, ephemeralPrivateKey, initialSequence)

    private fun setupSender(
        recipientPublicKey: ByteArray,
        info: ByteArray,
        ephemeralPrivateKey: ByteArray,
        initialSequence: Long
    ): Sender {
        require(initialSequence >= 0) { "Negative sequence" }
        val publicKey = publicKeyCopy(recipientPublicKey)
        require(ephemeralPrivateKey.size == X25519.SCALAR_SIZE) { "X25519 private key must be 32 bytes" }
        val privateKey = ephemeralPrivateKey.copyOf()
        val enc = ByteArray(X25519.POINT_SIZE)
        try {
            X25519.generatePublicKey(privateKey, 0, enc, 0)
            return Sender(enc, deriveMaterial(privateKey, publicKey, enc + publicKey, info.copyOf()), initialSequence)
        } finally {
            privateKey.fill(0)
        }
    }

    private fun publicKeyCopy(publicKey: ByteArray): ByteArray {
        require(publicKey.size == X25519.POINT_SIZE) { "X25519 public key must be 32 bytes" }
        // RFC 7748 decoding (including high-bit masking and noncanonical coordinates) is BC's job.
        // Low-order inputs are rejected by the all-zero agreement check below.
        return publicKey.copyOf()
    }

    private fun deriveMaterial(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        kemContext: ByteArray,
        info: ByteArray
    ): Material {
        val dh = ByteArray(X25519.POINT_SIZE)
        var eaePrk = empty
        var shared = empty
        var secret = empty
        try {
            require(X25519.calculateAgreement(privateKey, 0, peerPublicKey, 0, dh, 0)) {
                "Invalid X25519 public key: all-zero agreement"
            }
            eaePrk = labeledExtract(Domain.KEM, empty, "eae_prk", dh)
            shared = labeledExpand(Domain.KEM, eaePrk, "shared_secret", kemContext, 32)
            val context = byteArrayOf(0) +
                labeledExtract(Domain.HPKE, empty, "psk_id_hash", empty) +
                labeledExtract(Domain.HPKE, empty, "info_hash", info)
            secret = labeledExtract(Domain.HPKE, shared, "secret", empty)
            return Material(
                labeledExpand(Domain.HPKE, secret, "key", context, 16),
                labeledExpand(Domain.HPKE, secret, "base_nonce", context, 12)
            )
        } finally {
            dh.fill(0)
            eaePrk.fill(0)
            shared.fill(0)
            secret.fill(0)
        }
    }

    internal class Material(val key: ByteArray, val nonce: ByteArray) {
        fun clear() {
            key.fill(0)
            nonce.fill(0)
        }
    }

    /**
     * One unidirectional context, serialized across seal/close. No reset or clone.
     * Sequence [0, Long.MAX_VALUE) is supported; the smaller-than-RFC limit prevents wrap.
     */
    class Sender internal constructor(enc: ByteArray, private val material: Material, initialSequence: Long) : Closeable {
        private val encapsulatedKey = enc.copyOf()
        private var sequence = initialSequence
        private var closed = false

        val enc: ByteArray
            get() = encapsulatedKey.copyOf()

        val sequenceNumber: Long
            @Synchronized get() = sequence

        @Synchronized
        fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray {
            check(!closed) { "HPKE context closed" }
            check(sequence < Long.MAX_VALUE) { "HPKE sequence exhausted" }
            val nonce = NativeTlsCrypto.buildNonce(material.nonce, sequence)
            // Reserve before encryption; any failure poisons the context, preventing nonce retry.
            sequence++
            try {
                return NativeTlsCrypto.aeadSeal(TlsAead.AES_128_GCM, material.key, nonce, aad, plaintext)
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        @Synchronized
        override fun close() {
            closed = true
            material.clear()
        }
    }

    /** Open errors do not advance sequence; only authenticated plaintext is returned. */
    class Recipient internal constructor(private val material: Material) : Closeable {
        private var sequence = 0L
        private var closed = false

        val sequenceNumber: Long
            @Synchronized get() = sequence

        @Synchronized
        fun open(aad: ByteArray, ciphertext: ByteArray): ByteArray {
            check(!closed) { "HPKE context closed" }
            check(sequence < Long.MAX_VALUE) { "HPKE sequence exhausted" }
            val plaintext = NativeTlsCrypto.aeadOpen(
                TlsAead.AES_128_GCM, material.key,
                NativeTlsCrypto.buildNonce(material.nonce, sequence), aad, ciphertext
            )
            sequence++
            return plaintext
        }

        @Synchronized
        override fun close() {
            closed = true
            material.clear()
        }
    }
}
