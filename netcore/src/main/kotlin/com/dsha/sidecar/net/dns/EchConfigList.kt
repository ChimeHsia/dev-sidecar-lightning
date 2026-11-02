package com.dsha.sidecar.net.dns

/** HPKE identifiers are retained as unsigned 16-bit integers, including unknown algorithms. */
data class EchCipherSuite(val kdfId: Int, val aeadId: Int)

class EchConfigExtension internal constructor(val type: Int, data: ByteArray) {
    private val bytes = data.copyOf()
    val data: ByteArray get() = bytes.copyOf()
    val isMandatory: Boolean get() = type and 0x8000 != 0
}

class EchConfigContents internal constructor(
    val configId: Int,
    val kemId: Int,
    publicKey: ByteArray,
    cipherSuites: List<EchCipherSuite>,
    val maximumNameLength: Int,
    val publicName: String,
    extensions: List<EchConfigExtension>,
) {
    private val keyBytes = publicKey.copyOf()
    private val suites = cipherSuites.toList()
    private val extensionValues = extensions.toList()
    val publicKey: ByteArray get() = keyBytes.copyOf()
    val cipherSuites: List<EchCipherSuite> get() = suites.toMutableList()
    val extensions: List<EchConfigExtension> get() = extensionValues.toMutableList()
}

/** Unknown versions have opaque [raw] contents and a null [contents]. */
class EchConfig internal constructor(
    val versionCode: Int,
    raw: ByteArray,
    val contents: EchConfigContents?,
) {
    private val bytes = raw.copyOf()
    val length: Int get() = bytes.size
    /** Contents only, matching upstream `raw`; excludes the version/length header. */
    val raw: ByteArray get() = bytes.copyOf()
}

enum class EchConfigErrorCode {
    INPUT_TOO_LARGE,
    TRUNCATED,
    LENGTH_MISMATCH,
    EMPTY_VECTOR,
    INVALID_CIPHER_VECTOR,
    INVALID_PUBLIC_KEY_LENGTH,
    INVALID_PUBLIC_NAME,
    DUPLICATE_EXTENSION,
}

/** [offset] is an absolute byte offset into the supplied ECHConfigList. */
data class EchConfigError(val code: EchConfigErrorCode, val offset: Int, val field: String)

sealed class EchConfigListResult {
    class Success internal constructor(configs: List<EchConfig>) : EchConfigListResult() {
        private val values = configs.toList()
        val configs: List<EchConfig> get() = values.toMutableList()
    }

    data class Failure(val error: EchConfigError) : EchConfigListResult()
}

/**
 * Offline, platform-independent ECHConfigList parsing and metadata selection.
 *
 * No TLS, HPKE, key validation, DNS lookup, or handshake capability is provided.
 * A failure rejects the entire list; no partially parsed config can be selected.
 */
object EchConfigList {
    const val VERSION_FE0D = 0xfe0d
    const val KEM_X25519_HKDF_SHA256 = 0x0020
    const val MAX_WIRE_BYTES = 65537

    /**
     * Accepts exactly one length-prefixed list, bounded to 65535 content bytes.
     * Unknown versions remain opaque. Known-version nested vectors must be exact.
     * This byte-only API deliberately leaves string/base64 decoding to the caller.
     */
    fun parseEchConfigList(data: ByteArray): EchConfigListResult {
        if (data.size > MAX_WIRE_BYTES) {
            return EchConfigListResult.Failure(
                EchConfigError(EchConfigErrorCode.INPUT_TOO_LARGE, 0, "list"),
            )
        }
        return try {
            val reader = Cursor(data.copyOf(), 0, data.size)
            val length = reader.u16("list.length")
            if (length != reader.remaining) {
                reader.fail(EchConfigErrorCode.LENGTH_MISMATCH, "list.length", 0)
            }
            if (length == 0) reader.fail(EchConfigErrorCode.EMPTY_VECTOR, "list", 2)
            val configs = mutableListOf<EchConfig>()
            while (reader.remaining > 0) {
                val version = reader.u16("config.version")
                val contentLength = reader.u16("config.length")
                val content = reader.child(contentLength, "config.contents")
                val contents = if (version == VERSION_FE0D) parseContents(content) else null
                configs.add(EchConfig(version, content.raw(), contents))
            }
            EchConfigListResult.Success(configs)
        } catch (failure: WireFailure) {
            EchConfigListResult.Failure(failure.error)
        }
    }

    /**
     * Upstream-like DNS choice: prefer the first X25519 config, otherwise the first
     * candidate, preserving wire order. Unknown algorithms may be chosen.
     *
     * Candidates must have understood, structurally valid contents and no unknown
     * mandatory extensions. This is deliberately stricter than upstream's picker.
     * A returned config is a metadata choice, not proof of cryptographic usability.
     */
    fun pickEchConfig(configs: List<EchConfig>): EchConfig? {
        val candidates = configs.filter(::isCandidate)
        return candidates.firstOrNull { it.contents?.kemId == KEM_X25519_HKDF_SHA256 }
            ?: candidates.firstOrNull()
    }

    /**
     * Applies the upstream ECH caller's ID profile: FE0D, X25519, and an advertised
     * (HKDF-SHA256, AES-128-GCM) pair. No cryptographic operation is attempted.
     * Unknown optional extensions are preserved; mandatory ones exclude a config.
     */
    fun selectEchConfig(configs: List<EchConfig>): EchConfig? = configs.firstOrNull {
        isCandidate(it) &&
            it.contents?.kemId == KEM_X25519_HKDF_SHA256 &&
            it.contents.cipherSuites.any { suite -> suite.kdfId == 1 && suite.aeadId == 1 }
    }

    private fun isCandidate(config: EchConfig): Boolean =
        config.versionCode == VERSION_FE0D &&
            config.contents != null &&
            config.contents.extensions.none { it.isMandatory }

    private fun parseContents(reader: Cursor): EchConfigContents {
        val configId = reader.u8("config_id")
        val kemId = reader.u16("kem_id")
        val keyLengthOffset = reader.position
        val keyLength = reader.u16("public_key.length")
        val key = reader.child(keyLength, "public_key").raw()
        val expectedKeyLength = when (kemId) {
            0x0010 -> 65
            0x0011 -> 97
            0x0012 -> 133
            0x0020 -> 32
            0x0021 -> 56
            else -> null
        }
        if (keyLength == 0 || (expectedKeyLength != null && keyLength != expectedKeyLength)) {
            reader.fail(EchConfigErrorCode.INVALID_PUBLIC_KEY_LENGTH, "public_key", keyLengthOffset)
        }
        val suitesLengthOffset = reader.position
        val suitesLength = reader.u16("cipher_suites.length")
        if (suitesLength == 0 || suitesLength % 4 != 0) {
            reader.fail(EchConfigErrorCode.INVALID_CIPHER_VECTOR, "cipher_suites", suitesLengthOffset)
        }
        val suiteReader = reader.child(suitesLength, "cipher_suites")
        val suites = mutableListOf<EchCipherSuite>()
        while (suiteReader.remaining > 0) {
            suites.add(EchCipherSuite(suiteReader.u16("kdf_id"), suiteReader.u16("aead_id")))
        }
        val maximumNameLength = reader.u8("maximum_name_length")
        val nameLength = reader.u8("public_name.length")
        val nameOffset = reader.position
        val nameBytes = reader.child(nameLength, "public_name").raw()
        val publicName = buildString {
            for (byte in nameBytes) append((byte.toInt() and 0xff).toChar())
        }
        if (!isPublicName(publicName)) {
            reader.fail(EchConfigErrorCode.INVALID_PUBLIC_NAME, "public_name", nameOffset)
        }
        val extensionsLength = reader.u16("extensions.length")
        val extensionReader = reader.child(extensionsLength, "extensions")
        val extensions = mutableListOf<EchConfigExtension>()
        val seenTypes = mutableSetOf<Int>()
        while (extensionReader.remaining > 0) {
            val typeOffset = extensionReader.position
            val type = extensionReader.u16("extension.type")
            val length = extensionReader.u16("extension.length")
            val value = extensionReader.child(length, "extension.data").raw()
            if (!seenTypes.add(type)) {
                extensionReader.fail(EchConfigErrorCode.DUPLICATE_EXTENSION, "extension.type", typeOffset)
            }
            extensions.add(EchConfigExtension(type, value))
        }
        if (reader.remaining != 0) {
            reader.fail(EchConfigErrorCode.LENGTH_MISMATCH, "config.contents", reader.position)
        }
        return EchConfigContents(configId, kemId, key, suites, maximumNameLength, publicName, extensions)
    }

    // A deliberately conservative ASCII hostname policy, without normalization.
    private fun isPublicName(name: String): Boolean {
        if (name.isEmpty() || name.length > 253) return false
        return name.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }

    private class WireFailure(val error: EchConfigError) : Exception()

    private class Cursor(
        private val data: ByteArray,
        private val start: Int,
        private val end: Int,
    ) {
        var position: Int = start
            private set
        val remaining: Int get() = end - position

        fun u8(field: String): Int {
            requireBytes(1, field)
            return data[position++].toInt() and 0xff
        }

        fun u16(field: String): Int {
            requireBytes(2, field)
            return (u8(field) shl 8) or u8(field)
        }

        fun child(length: Int, field: String): Cursor {
            requireBytes(length, field)
            val child = Cursor(data, position, position + length)
            position += length
            return child
        }

        fun raw(): ByteArray = data.copyOfRange(start, end)

        private fun requireBytes(length: Int, field: String) {
            if (length > remaining) fail(EchConfigErrorCode.TRUNCATED, field, position)
        }

        fun fail(code: EchConfigErrorCode, field: String, offset: Int): Nothing =
            throw WireFailure(EchConfigError(code, offset, field))
    }
}
