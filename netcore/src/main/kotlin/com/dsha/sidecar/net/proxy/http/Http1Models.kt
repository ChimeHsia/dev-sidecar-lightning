package com.dsha.sidecar.net.proxy.http

import java.io.IOException
import java.net.URI

class Http1Exception(message: String) : IOException(message)

data class Http1Limits(
    val maxHeadBytes: Int = 32 * 1024,
    val maxLineBytes: Int = 8 * 1024,
    val maxHeaderCount: Int = 100,
    val maxTrailerBytes: Int = 16 * 1024,
    val maxTrailerCount: Int = 50,
    val maxBodyBytes: Long = 64L * 1024 * 1024,
    val bufferBytes: Int = 8192,
) {
    init {
        require(maxHeadBytes >= 2 && maxLineBytes in 1..(Int.MAX_VALUE - 2) && maxHeaderCount >= 0)
        require(maxTrailerBytes >= 2 && maxTrailerCount >= 0 && maxBodyBytes >= 0)
        require(bufferBytes in 1..(1024 * 1024))
    }
}

/** The exact field name and bytes after the colon, represented as ISO-8859-1. */
data class Http1Header(val name: String, val rawValue: String) {
    val value: String get() = rawValue.trim(' ', '\t')
}

class Http1Headers(fields: List<Http1Header>) {
    val fields: List<Http1Header> = java.util.Collections.unmodifiableList(ArrayList(fields))

    fun values(name: String): List<String> =
        fields.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

    /** Parses a comma-separated token field; malformed or empty elements are rejected. */
    fun tokens(name: String): List<String> = values(name).flatMap { value ->
        value.split(',').map {
            val token = it.trim(' ', '\t')
            if (!isToken(token)) throw Http1Exception("Invalid $name token")
            token
        }
    }

    fun hasToken(name: String, token: String): Boolean =
        tokens(name).any { it.equals(token, ignoreCase = true) }
}

sealed class Http1Target {
    abstract val raw: String

    data class Origin(override val raw: String) : Http1Target()
    data class Absolute(override val raw: String, val authority: String, val originForm: String) : Http1Target()
    data class Authority(override val raw: String) : Http1Target()
    data object Asterisk : Http1Target() {
        override val raw: String = "*"
    }
}

data class Http1RequestHead(
    val method: String,
    val target: Http1Target,
    val version: String,
    val headers: Http1Headers,
)

data class Http1ResponseHead(
    val version: String,
    val status: Int,
    val reason: String,
    val headers: Http1Headers,
)

sealed class Http1BodyFraming {
    data object None : Http1BodyFraming()
    data class Fixed(val length: Long) : Http1BodyFraming()
    data object Chunked : Http1BodyFraming()
    data object CloseDelimited : Http1BodyFraming()
    data object Handoff : Http1BodyFraming()
}

data class Http1Request(val head: Http1RequestHead, val framing: Http1BodyFraming)
data class Http1Response(val head: Http1ResponseHead, val framing: Http1BodyFraming)

internal fun isToken(value: String): Boolean = value.isNotEmpty() && value.all {
    it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it in "!#$%&'*+-.^_`|~"
}

internal fun validFieldValue(value: String): Boolean = value.all {
    it == '\t' || it.code in 0x20..0x7e || it.code in 0x80..0xff
}

internal fun parseTarget(method: String, raw: String): Http1Target {
    if (raw.isEmpty() || raw.any { it.code !in 0x21..0x7e } || '#' in raw) {
        throw Http1Exception("Invalid request target")
    }
    if (raw == "*") {
        if (method != "OPTIONS") throw Http1Exception("Asterisk target requires OPTIONS")
        return Http1Target.Asterisk
    }
    if (method == "CONNECT") {
        val uri = try {
            URI("http://$raw")
        } catch (_: Exception) {
            throw Http1Exception("Invalid CONNECT authority")
        }
        if (uri.host == null || uri.port !in 1..65535 || uri.rawUserInfo != null ||
            !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawAuthority != raw
        ) throw Http1Exception("Invalid CONNECT authority")
        return Http1Target.Authority(raw)
    }
    if (raw.startsWith("/")) {
        try {
            // A leading double slash is still an origin path, not a URI authority.
            URI("http://http1.invalid$raw")
        } catch (_: java.net.URISyntaxException) {
            throw Http1Exception("Invalid origin target")
        }
        return Http1Target.Origin(raw)
    }
    val uri = try {
        URI(raw)
    } catch (_: java.net.URISyntaxException) {
        throw Http1Exception("Invalid absolute target")
    }
    if ((!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) ||
        uri.host == null || uri.rawUserInfo != null || uri.rawFragment != null ||
        uri.port > 65535 || uri.rawAuthority.isNullOrEmpty()
    ) throw Http1Exception("Invalid absolute target")
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return Http1Target.Absolute(raw, uri.rawAuthority, path + query)
}
