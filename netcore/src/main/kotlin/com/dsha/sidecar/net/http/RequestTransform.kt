/*
 * SPDX-License-Identifier: MPL-2.0
 * Adapted from @blue-frontier/mitmproxy src/lib/proxy/common/util.js:
 * parseHostnameAndPort and the direct-request pure fields of getOptionsFromRequest.
 * Upstream author: docmirror.cn. See docs/migration-wave2/request-transform.md.
 */
package com.dsha.sidecar.net.http

import com.dsha.sidecar.net.dns.IpAddress

/**
 * Pure request options for the no-external-proxy branch of upstream.
 *
 * Headers must already have Node-style lowercase names and string/string-list
 * values; exactly one scalar Host is required. Only origin-form, HTTP(S)
 * absolute-form, and OPTIONS "*" targets are supported. Authorities are strict
 * ASCII DNS names, canonical IPv4, or bracketed IPv6, with an optional 1..65535
 * decimal port. Userinfo, zone IDs, backslashes, invalid escapes and controls
 * are rejected. Raw path/query punctuation is escaped like Node's legacy parser;
 * existing percent escapes are preserved, never decoded.
 *
 * Invalid or unsupported inputs throw [IllegalArgumentException]. All returned
 * containers are fresh copies of the inputs; callers should treat them as read-only.
 * No I/O, agent selection, interceptor rules or socket state is performed.
 */
object RequestTransform {
    data class Result(
        val protocol: String,
        val hostname: String,
        val port: Int,
        val path: String,
        val headers: Map<String, Any>,
    )

    fun transform(
        url: String,
        ssl: Boolean,
        method: String,
        headers: Map<String, Any?>,
    ): Result {
        require(method.isNotEmpty() && method.all(::isTokenChar)) { "Invalid HTTP method" }
        require(method != "CONNECT") { "CONNECT is outside this request transform" }
        val target = parseTarget(url, method)
        val copiedHeaders = copyHeaders(headers)
        val host = copiedHeaders["host"]
        require(host is String) { "Exactly one scalar host header is required" }
        val authority = parseAuthority(host)

        // Upstream trusts Host for routing, not the absolute URL's authority.
        // Only an absolute HTTP URL overrides the incoming SSL flag.
        val actualSsl = ssl && target.scheme != "http"
        val protocol = if (actualSsl) "https:" else "http:"
        copiedHeaders.remove("proxy-connection")
        val connection = copiedHeaders["connection"]
        val connectionText = when (connection) {
            is String -> connection
            is List<*> -> connection.joinToString(",")
            else -> ""
        }
        val wantsClose = connectionText.split(',').any { it.trim().equals("close", ignoreCase = true) }
        if (!wantsClose) copiedHeaders["connection"] = "keep-alive"

        return Result(
            protocol = protocol,
            hostname = authority.hostname,
            port = authority.port ?: if (actualSsl) 443 else 80,
            path = target.path,
            headers = copiedHeaders,
        )
    }

    private data class Authority(val hostname: String, val port: Int?)
    private data class Target(val scheme: String?, val path: String)

    private fun parseTarget(url: String, method: String): Target {
        require(url.isNotEmpty() && url.first() != ' ' && url.last() != ' ') {
            "Request target must be nonempty with no surrounding whitespace"
        }
        require(url.all { it in '\u0020'..'\u007e' && it != '\\' }) {
            "Request target must be ASCII without controls or backslashes"
        }
        validateEscapes(url)
        if (url == "*") {
            require(method == "OPTIONS") { "Asterisk target requires OPTIONS" }
            return Target(null, "*")
        }

        var scheme: String? = null
        val rawPath: String
        if (url.startsWith('/')) {
            require(!url.startsWith("//")) { "Network-path targets are unsupported" }
            rawPath = url
        } else {
            val schemeEnd = url.indexOf("://")
            require(schemeEnd > 0) { "Expected origin-form or HTTP(S) absolute-form target" }
            scheme = url.substring(0, schemeEnd).lowercase()
            require(scheme == "http" || scheme == "https") { "Unsupported absolute URL scheme" }
            val start = schemeEnd + 3
            val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).let {
                if (it < 0) url.length else it
            }
            // Validate even though upstream routes using Host: malformed or
            // credential-bearing absolute authorities must not be ignored.
            parseAuthority(url.substring(start, end))
            val suffix = url.substring(end)
            rawPath = if (suffix.startsWith('/')) suffix else "/$suffix"
        }
        val withoutFragment = rawPath.substringBefore('#')
        // URL.parse's simple origin-path fast path preserves punctuation.
        // An @ in the query does not disable that path, but one before ? does.
        if (scheme == null && '#' !in url && ' ' !in url && '@' !in url.substringBefore('?')) {
            return Target(null, withoutFragment)
        }
        return Target(scheme, escapePath(withoutFragment))
    }

    private fun parseAuthority(text: String): Authority {
        require(text.isNotEmpty() && text.all { it in '\u0021'..'\u007e' }) {
            "Authority must be nonempty printable ASCII without whitespace"
        }
        require(text.none { it in "@/?#\\%" }) { "Invalid authority or unsupported userinfo/zone ID" }
        val hostname: String
        val portText: String?
        if (text.startsWith('[')) {
            val close = text.indexOf(']')
            require(close > 1) { "Malformed bracketed IPv6 authority" }
            hostname = text.substring(0, close + 1)
            require(IpAddress.isIpv6(hostname)) { "Invalid IPv6 literal" }
            val suffix = text.substring(close + 1)
            require(suffix.isEmpty() || suffix.startsWith(':')) { "Invalid IPv6 authority suffix" }
            portText = if (suffix.isEmpty()) null else suffix.substring(1)
        } else {
            require('[' !in text && ']' !in text && text.count { it == ':' } <= 1) {
                "IPv6 authorities require one pair of brackets"
            }
            val colon = text.indexOf(':')
            hostname = if (colon < 0) text else text.substring(0, colon)
            portText = if (colon < 0) null else text.substring(colon + 1)
            validateHostname(hostname)
        }
        val port = portText?.let {
            require(it.isNotEmpty() && it.all { char -> char in '0'..'9' }) {
                "Port must be a complete decimal integer"
            }
            val number = it.toIntOrNull()
            require(number != null && number in 1..65535) { "Port must be in 1..65535" }
            number
        }
        return Authority(hostname, port)
    }

    private fun validateHostname(hostname: String) {
        if (hostname.isNotEmpty() && hostname.all { it in '0'..'9' || it == '.' }) {
            require(IpAddress.isIpv4(hostname)) { "Numeric host must be canonical dotted IPv4" }
            return
        }
        val name = hostname.removeSuffix(".")
        require(name.length in 1..253) { "Invalid DNS name length" }
        for (label in name.split('.')) {
            require(label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            ) { "Invalid ASCII DNS label" }
        }
    }

    private fun copyHeaders(headers: Map<String, Any?>): MutableMap<String, Any> {
        val result = linkedMapOf<String, Any>()
        for ((name, value) in headers) {
            val bareName = name.removePrefix(":")
            require(bareName.isNotEmpty() && bareName.all(::isTokenChar) && name == name.lowercase()) {
                "Header names must be lowercase HTTP tokens (optionally pseudo headers)"
            }
            val copiedValue = when (value) {
                is String -> validateHeaderValue(value)
                is List<*> -> value.map {
                    require(it is String) { "Header lists must contain only strings" }
                    validateHeaderValue(it)
                }
                else -> throw IllegalArgumentException("Header values must be strings or string lists")
            }
            if (!name.startsWith(':')) result[name] = copiedValue
        }
        return result
    }

    private fun validateHeaderValue(value: String): String {
        require(value.all { it == '\t' || it in '\u0020'..'\u007e' || it in '\u0080'..'\u00ff' }) {
            "Header value contains invalid controls or non-byte characters"
        }
        return value
    }

    private fun validateEscapes(text: String) {
        var index = 0
        while (index < text.length) {
            if (text[index] == '%') {
                require(index + 2 < text.length && isHex(text[index + 1]) && isHex(text[index + 2])) {
                    "Malformed percent escape"
                }
                index += 3
            } else {
                index++
            }
        }
    }

    private fun escapePath(path: String): String = buildString {
        for (char in path) {
            append(when (char) {
                ' ' -> "%20"
                '"' -> "%22"
                '\'' -> "%27"
                '<' -> "%3C"
                '>' -> "%3E"
                '`' -> "%60"
                '{' -> "%7B"
                '}' -> "%7D"
                '|' -> "%7C"
                '^' -> "%5E"
                else -> char.toString()
            })
        }
    }

    private fun isHex(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

    private fun isTokenChar(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "!#$%&'*+-.^_`|~"
}
