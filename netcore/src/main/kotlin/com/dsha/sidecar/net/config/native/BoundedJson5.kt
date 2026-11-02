package com.dsha.sidecar.net.config.native

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class ConfigLimits(
    val maxDocumentBytes: Int = 1_048_576,
    val maxTotalBytes: Int = 4_194_304,
    val maxDepth: Int = 48,
    val maxKeys: Int = 20_000,
    val maxNodes: Int = 50_000,
    val maxStringChars: Int = 262_144,
    val maxNumberChars: Int = 128,
) {
    init {
        require(maxDocumentBytes in 2..8_388_608 && maxTotalBytes in maxDocumentBytes..33_554_432)
        require(maxDepth in 1..128 && maxKeys in 1..100_000 && maxNodes in 1..500_000)
        require(maxStringChars in 1..8_388_608 && maxNumberChars in 1..256)
    }
}

enum class ConfigError { SYNTAX, UNSUPPORTED, LIMIT, UTF8, FILE, PATH, SHAPE, URL, FETCH, VALIDATION }

/** Messages never include configuration text, property names, URL values or file paths. */
class ConfigRejected(val code: ConfigError, val offset: Int = -1) :
    IllegalArgumentException("Configuration rejected: $code" + if (offset >= 0) " at offset $offset" else "")

internal fun reject(code: ConfigError): Nothing = throw ConfigRejected(code)

/**
 * Deliberately bounded JSON5 subset, not a lenient JSON parser or a full JSON5 claim.
 * ASCII IdentifierNames; quoted Unicode, comments, trailing commas, escapes, finite
 * decimal/hex numbers. Duplicate/dangerous keys, non-finite numbers, unpaired
 * surrogates and non-ASCII unquoted names fail explicitly. No expression evaluation.
 */
class BoundedJson5(private val limits: ConfigLimits = ConfigLimits()) {
    fun parseObject(text: String): Map<String, Any?> {
        if (text.length > limits.maxDocumentBytes) reject(ConfigError.LIMIT)
        if (text.toByteArray(Charsets.UTF_8).size > limits.maxDocumentBytes) reject(ConfigError.LIMIT)
        return Parser(text).parse()
    }

    fun parseObject(bytes: ByteArray): Map<String, Any?> {
        if (bytes.size > limits.maxDocumentBytes) reject(ConfigError.LIMIT)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            reject(ConfigError.UTF8)
        }
        return parseObject(text)
    }

    private inner class Parser(private val text: String) {
        private var position = 0
        private var nodes = 0
        private var keys = 0
        private fun fail(code: ConfigError = ConfigError.SYNTAX): Nothing = throw ConfigRejected(code, position)
        private fun peek(): Char = text.getOrNull(position) ?: '\u0000'
        private fun take(char: Char): Boolean =
            if (position < text.length && text[position] == char) { position++; true } else false

        fun parse(): Map<String, Any?> {
            skip()
            if (peek() != '{') fail(ConfigError.SHAPE)
            val result = value(0)
            skip()
            if (position != text.length) fail()
            @Suppress("UNCHECKED_CAST")
            return result as Map<String, Any?>
        }

        private fun value(depth: Int): Any? {
            if (depth > limits.maxDepth || ++nodes > limits.maxNodes) fail(ConfigError.LIMIT)
            skip()
            return when (peek()) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '\'', '"' -> string()
                else -> scalar()
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            position++
            skip()
            val result = linkedMapOf<String, Any?>()
            if (take('}')) return result
            while (true) {
                if (++keys > limits.maxKeys) fail(ConfigError.LIMIT)
                val name = if (peek() == '\'' || peek() == '"') string() else identifier()
                if (name in result || name in setOf("__proto__", "prototype", "constructor")) {
                    fail(ConfigError.UNSUPPORTED)
                }
                skip()
                if (!take(':')) fail()
                result[name] = value(depth + 1)
                skip()
                if (take('}')) return jsKeyOrder(result)
                if (!take(',')) fail()
                skip()
                if (take('}')) return jsKeyOrder(result)
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            position++
            skip()
            val result = mutableListOf<Any?>()
            if (take(']')) return result
            while (true) {
                result += value(depth + 1)
                skip()
                if (take(']')) return result
                if (!take(',')) fail()
                skip()
                if (take(']')) return result
            }
        }

        private fun identifier(): String {
            val result = StringBuilder()
            while (position < text.length) {
                val char = if (peek() == '\\') {
                    position++
                    if (!take('u')) fail(ConfigError.UNSUPPORTED)
                    hex(4)
                } else {
                    val next = peek()
                    if (next.code > 127) fail(ConfigError.UNSUPPORTED)
                    if (!(next in 'a'..'z' || next in 'A'..'Z' || next == '_' || next == '$' ||
                                result.isNotEmpty() && next in '0'..'9')) break
                    position++
                    next
                }
                val valid = char in 'a'..'z' || char in 'A'..'Z' || char == '_' || char == '$' ||
                    result.isNotEmpty() && char in '0'..'9'
                if (!valid) fail(ConfigError.UNSUPPORTED)
                result.append(char)
                if (result.length > limits.maxStringChars) fail(ConfigError.LIMIT)
            }
            if (result.isEmpty()) fail()
            return result.toString()
        }

        private fun hex(count: Int): Char {
            if (position + count > text.length) fail()
            var value = 0
            repeat(count) {
                val char = text[position++]
                if (char !in "0123456789abcdefABCDEF") fail()
                val digit = char.digitToInt(16)
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun string(): String {
            val quote = text[position++]
            val result = StringBuilder()
            while (position < text.length) {
                val char = text[position++]
                if (char == quote) {
                    var i = 0
                    while (i < result.length) {
                        val current = result[i++]
                        if (current.isHighSurrogate()) {
                            if (i >= result.length || !result[i++].isLowSurrogate()) fail(ConfigError.UNSUPPORTED)
                        } else if (current.isLowSurrogate()) fail(ConfigError.UNSUPPORTED)
                    }
                    return result.toString()
                }
                if (char == '\n' || char == '\r') fail()
                if (char != '\\') {
                    if (char.code < 32) fail(ConfigError.UNSUPPORTED)
                    result.append(char)
                } else {
                    if (position >= text.length) fail()
                    when (val escaped = text[position++]) {
                        '\n', '\u2028', '\u2029' -> Unit
                        '\r' -> take('\n')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'v' -> result.append('\u000b')
                        '0' -> {
                            if (peek() in '0'..'9') fail()
                            result.append('\u0000')
                        }
                        'x' -> result.append(hex(2))
                        'u' -> result.append(hex(4))
                        in '1'..'9' -> fail()
                        else -> result.append(escaped)
                    }
                }
                if (result.length > limits.maxStringChars) fail(ConfigError.LIMIT)
            }
            fail()
        }

        private fun scalar(): Any? {
            val start = position
            while (position < text.length && !whitespace(peek()) && peek() !in ",}]/") {
                position++
                if (position - start > limits.maxNumberChars) fail(ConfigError.LIMIT)
            }
            val token = text.substring(start, position)
            return when (token) {
                "true" -> true
                "false" -> false
                "null" -> null
                "Infinity", "+Infinity", "-Infinity", "NaN", "+NaN", "-NaN" -> fail(ConfigError.UNSUPPORTED)
                else -> {
                    val number = if (HEX.matches(token)) {
                        val negative = token.startsWith('-')
                        val digits = token.removePrefix("+").removePrefix("-").substring(2)
                        java.math.BigInteger(digits, 16).toDouble() * if (negative) -1.0 else 1.0
                    } else {
                        if (!DECIMAL.matches(token)) fail()
                        token.toDoubleOrNull() ?: fail()
                    }
                    if (!number.isFinite()) fail(ConfigError.UNSUPPORTED)
                    number
                }
            }
        }

        private fun skip() {
            while (position < text.length) {
                if (whitespace(peek())) {
                    position++
                } else if (text.startsWith("//", position)) {
                    position += 2
                    while (position < text.length && peek() !in "\n\r\u2028\u2029") position++
                } else if (text.startsWith("/*", position)) {
                    val end = text.indexOf("*/", position + 2)
                    if (end < 0) fail()
                    position = end + 2
                } else return
            }
        }
    }

    private companion object {
        val DECIMAL = Regex("[+-]?(?:(?:0|[1-9][0-9]*)(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        val HEX = Regex("[+-]?0[xX][0-9a-fA-F]+")
    }
}

internal fun whitespace(char: Char): Boolean =
    char in "\t\u000b\u000c \u00a0\ufeff\n\r\u2028\u2029" ||
        Character.getType(char) == Character.SPACE_SEPARATOR.toInt()

/** ECMAScript own-key order, including numeric property names in rule maps. */
internal fun jsKeyOrder(value: Map<String, Any?>): LinkedHashMap<String, Any?> {
    fun index(key: String): Long? = key.toLongOrNull()?.takeIf {
        it in 0..4_294_967_294L && it.toString() == key
    }
    val result = linkedMapOf<String, Any?>()
    value.keys.mapNotNull { key -> index(key)?.let { it to key } }.sortedBy { it.first }
        .forEach { (_, key) -> result[key] = value[key] }
    value.forEach { (key, item) -> if (index(key) == null) result[key] = item }
    return result
}
