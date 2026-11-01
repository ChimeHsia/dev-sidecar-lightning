package com.dsha.sidecar.net.proxy.ech

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.AEADCipher
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.GeneralSecurityException

enum class TlsHash(val size: Int) {
    SHA256(32),
    SHA384(48);

    internal fun digest(): Digest = when (this) {
        SHA256 -> SHA256Digest()
        SHA384 -> SHA384Digest()
    }
}

enum class TlsAead(val keySize: Int) {
    AES_128_GCM(16),
    AES_256_GCM(32),
    CHACHA20_POLY1305(32)
}

class AuthenticationException internal constructor() : GeneralSecurityException("AEAD authentication failed")

/**
 * Byte-oriented primitives only. BC lightweight operations are used explicitly, without global
 * provider registration. No handshake, record framing, certificate verification or network I/O.
 */
object NativeTlsCrypto {
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    fun hash(hash: TlsHash, input: ByteArray): ByteArray {
        val digest = hash.digest()
        val copy = input.copyOf()
        digest.update(copy, 0, copy.size)
        return ByteArray(hash.size).also { digest.doFinal(it, 0) }
    }

    fun hkdfExtract(hash: TlsHash, salt: ByteArray, ikm: ByteArray): ByteArray =
        HKDFBytesGenerator(hash.digest()).extractPRK(salt.copyOf(), ikm.copyOf())

    fun hkdfExpand(hash: TlsHash, prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(prk.size >= hash.size) { "HKDF PRK must be at least Hash.length bytes" }
        require(length in 0..255 * hash.size) { "HKDF output exceeds RFC 5869 bound" }
        val generator = HKDFBytesGenerator(hash.digest())
        generator.init(HKDFParameters.skipExtractParameters(prk.copyOf(), info.copyOf()))
        return ByteArray(length).also { if (length != 0) generator.generateBytes(it, 0, length) }
    }

    fun hkdfExpandLabel(
        hash: TlsHash,
        secret: ByteArray,
        label: String,
        context: ByteArray,
        length: Int
    ): ByteArray {
        val fullLabel = ascii("tls13 " + label)
        require(fullLabel.size in 7..255) { "TLS label must contain 1..249 ASCII bytes" }
        require(context.size <= 255) { "TLS context exceeds uint8 length" }
        val encoded = uint16(length) + byteArrayOf(fullLabel.size.toByte()) + fullLabel +
            byteArrayOf(context.size.toByte()) + context.copyOf()
        return hkdfExpand(hash, secret, encoded, length)
    }

    /** RFC 8446 Derive-Secret: messages are the encoded handshake transcript, not a digest. */
    fun deriveSecret(hash: TlsHash, secret: ByteArray, label: String, messages: ByteArray): ByteArray =
        deriveSecretFromHash(hash, secret, label, hash(hash, messages))

    /** Explicit prehashed variant for callers matching the upstream Node helper. */
    fun deriveSecretFromHash(
        hash: TlsHash,
        secret: ByteArray,
        label: String,
        transcriptHash: ByteArray
    ): ByteArray {
        require(transcriptHash.size == hash.size) { "Transcript hash has incorrect length" }
        return hkdfExpandLabel(hash, secret, label, transcriptHash, hash.size)
    }

    /** Left-zero-padded sequence XOR base IV. Restricted to nonnegative signed Long values. */
    fun buildNonce(iv: ByteArray, sequence: Long): ByteArray {
        require(iv.size == NONCE_SIZE) { "AEAD IV must be 12 bytes" }
        require(sequence >= 0) { "Negative sequence" }
        val nonce = iv.copyOf()
        for (index in 0 until 8) {
            val offset = nonce.lastIndex - index
            nonce[offset] = (nonce[offset].toInt() xor (sequence ushr (index * 8)).toInt()).toByte()
        }
        return nonce
    }

    /** Stateless AEAD; the caller MUST ensure key/nonce uniqueness and enforce usage limits. */
    fun aeadSeal(
        algorithm: TlsAead,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray
    ): ByteArray = aead(true, algorithm, key, nonce, aad, plaintext)

    /** Returns plaintext only after the full tag authenticates. */
    fun aeadOpen(
        algorithm: TlsAead,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray
    ): ByteArray = aead(false, algorithm, key, nonce, aad, ciphertext)

    private fun aead(
        encrypt: Boolean,
        algorithm: TlsAead,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray
    ): ByteArray {
        require(key.size == algorithm.keySize) { "Incorrect AEAD key length" }
        require(nonce.size == NONCE_SIZE) { "AEAD nonce must be 12 bytes" }
        if (!encrypt && input.size < TAG_SIZE) throw AuthenticationException()
        val cipher: AEADCipher = when (algorithm) {
            TlsAead.AES_128_GCM, TlsAead.AES_256_GCM -> GCMBlockCipher.newInstance(AESEngine.newInstance())
            TlsAead.CHACHA20_POLY1305 -> ChaCha20Poly1305()
        }
        cipher.init(encrypt, AEADParameters(KeyParameter(key.copyOf()), 128, nonce.copyOf(), aad.copyOf()))
        val data = input.copyOf()
        val output = ByteArray(cipher.getOutputSize(data.size))
        try {
            val count = cipher.processBytes(data, 0, data.size, output, 0)
            val total = count + cipher.doFinal(output, count)
            return output.copyOf(total)
        } catch (_: InvalidCipherTextException) {
            throw AuthenticationException()
        } finally {
            output.fill(0)
            data.fill(0)
        }
    }

    internal fun ascii(value: String): ByteArray {
        require(value.all { it.code in 0..127 }) { "Label must be ASCII" }
        return value.toByteArray(Charsets.US_ASCII)
    }

    internal fun uint16(value: Int): ByteArray {
        require(value in 0..65535) { "Value exceeds uint16 length" }
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }
}
