/*
 * SPDX-License-Identifier: MPL-2.0
 * Adapted from @blue-frontier/mitmproxy/src/lib/interceptor/impl/
 * req/requestReplace.js, res/responseReplace.js, res/AfterOPTIONSHeaders.js
 * (docmirror.cn).
 */
package com.dsha.sidecar.net.http

/**
 * Data that upstream would mutate or pass to res.setHeader. Raw headers retain
 * their alternating name/value layout, original name casing and duplicates.
 * A null [previousHeaders] means no replacement/addition was recorded.
 */
data class ResponseHeadersResult(
    val rawHeaders: List<Any?>,
    val setHeaders: Map<String, Any?>,
    val previousHeaders: Map<String, Any?>?,
    val remainingReplacements: Map<String, Any?>,
)

/** Pure transforms over the bounded JSON model documented in http-policy.md. */
object HeaderTransforms {
    fun replaceRequestHeaders(
        headers: Map<String, Any?>,
        replacements: Map<String, Any?>,
    ): Map<String, Any?> {
        val result = HttpPolicyJson.headers(headers)
        val changes = HttpPolicyJson.headers(replacements)
        for ((key, original) in changes) {
            val value = if (original == "[remove]") null else original
            val lower = key.lowercase()
            if (HttpPolicyJson.truthy(value)) {
                result[lower] = value
            } else if (HttpPolicyJson.truthy(result[lower])) {
                result.remove(lower)
            }
        }
        return HttpPolicyJson.ordered(result)
    }

    fun replaceResponseHeaders(
        rawHeaders: List<Any?>,
        replacements: Map<String, Any?>,
    ): ResponseHeadersResult {
        val raw = HttpPolicyJson.rawHeaders(rawHeaders)
        val changes = HttpPolicyJson.headers(replacements)
        // JS for-in snapshots the keys; insertion during normalization must not
        // revisit new keys or reorder case collisions.
        for (key in changes.keys.toList()) {
            if (!changes.containsKey(key) || key == key.lowercase()) continue
            val value = changes.remove(key)
            changes[key.lowercase()] = value
        }
        val previous = linkedMapOf<String, Any?>()
        val consumed = mutableListOf<String>()
        for (index in raw.indices step 2) {
            val key = (raw[index] as String).lowercase()
            val replacement = changes[key]
            if (replacement != null) {
                if (!HttpPolicyJson.strictEquals(replacement, raw[index + 1])) {
                    previous[key] = raw[index + 1]
                    raw[index + 1] = if (replacement == "[remove]") "" else replacement
                }
                consumed.add(key)
            }
        }
        consumed.forEach(changes::remove)
        val added = linkedMapOf<String, Any?>()
        for (key in HttpPolicyJson.keys(changes)) {
            val value = changes[key]
            if (value == null || value == "[remove]") continue
            added[key] = value
            previous[key] = null
        }
        return ResponseHeadersResult(
            raw, added, previous.takeIf { it.isNotEmpty() }?.let(HttpPolicyJson::ordered), changes,
        )
    }

    /**
     * Data portion of AfterOPTIONSHeaders.responseIntercept. Callers decide
     * whether its `options` feature is enabled; there is no feature gate here.
     * The diagnostic DS-AfterOPTIONSHeaders-Interceptor value is returned as
     * data in setHeaders. Nothing is applied to a response.
     */
    fun afterOptionsHeaders(
        method: Any?,
        requestHeaders: Map<String, Any?>,
        rawHeaders: List<Any?>,
        optionsCrossPolicy: Any? = null,
    ): ResponseHeadersResult {
        val copiedMethod = HttpPolicyJson.copy(method)
        val request = HttpPolicyJson.headers(requestHeaders)
        val raw = HttpPolicyJson.rawHeaders(rawHeaders)
        val policy = HttpPolicyJson.copy(optionsCrossPolicy)
        if (copiedMethod == "OPTIONS" || !HttpPolicyJson.truthy(request["origin"])) {
            return ResponseHeadersResult(raw, linkedMapOf(), null, linkedMapOf())
        }

        var vary: Any? = "Origin"
        for (index in raw.indices step 2) {
            if ((raw[index] as String).lowercase() != "vary") continue
            val existing = raw[index + 1]
            if (HttpPolicyJson.truthy(existing)) {
                require(existing is String) { "Truthy Vary must be a string" }
                val parts = existing.split(',')
                val containsOrigin = parts.indices.any { partIndex ->
                    var part = parts[partIndex]
                    if (partIndex > 0) part = part.trimStart(HttpPolicyJson::whitespace)
                    if (partIndex < parts.lastIndex) part = part.trimEnd(HttpPolicyJson::whitespace)
                    part == "Origin"
                }
                vary = if (containsOrigin) existing else "$existing, Origin"
            } else {
                vary = existing
            }
            break
        }
        val changes = linkedMapOf(
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Allow-Origin" to request["origin"],
            "Cross-Origin-Resource-Policy" to if (HttpPolicyJson.truthy(policy)) policy else "cross-origin",
            "Vary" to vary,
        )
        val result = replaceResponseHeaders(raw, changes)
        val setHeaders = result.setHeaders.toMutableMap()
        setHeaders["DS-AfterOPTIONSHeaders-Interceptor"] = if (result.previousHeaders != null) "1" else "0"
        return result.copy(setHeaders = setHeaders)
    }
}

/** Shared portable JSON boundary, deliberately independent of JVM/JS APIs. */
internal object HttpPolicyJson {
    private val prototypeKeys = setOf(
        "__proto__", "constructor", "toString", "toLocaleString", "valueOf",
        "hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable",
        "__defineGetter__", "__defineSetter__", "__lookupGetter__", "__lookupSetter__",
    )

    fun ascii(value: String) {
        require(value.all { it.code <= 127 }) { "Header keys and retry methods must be ASCII" }
    }

    fun whitespace(char: Char): Boolean =
        char in '\u0009'..'\u000d' || char == ' ' || char == '\u00a0' ||
            char == '\u1680' || char in '\u2000'..'\u200a' || char == '\u2028' ||
            char == '\u2029' || char == '\u202f' || char == '\u205f' ||
            char == '\u3000' || char == '\ufeff'

    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        else -> true
    }

    fun strictEquals(left: Any?, right: Any?): Boolean = when {
        left is Number && right is Number -> left.toDouble() == right.toDouble()
        left is Map<*, *> || left is List<*> || right is Map<*, *> || right is List<*> -> left === right
        else -> left == right
    }

    fun headers(value: Map<String, Any?>): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val result = copy(value) as MutableMap<String, Any?>
        result.keys.forEach(::headerKey)
        return result
    }

    fun rawHeaders(value: List<Any?>): MutableList<Any?> {
        @Suppress("UNCHECKED_CAST")
        val result = copy(value) as MutableList<Any?>
        require(result.size % 2 == 0) { "Raw headers must contain complete name/value pairs" }
        for (index in result.indices step 2) {
            val name = result[index]
            require(name is String) { "Raw header names must be strings" }
            headerKey(name)
        }
        return result
    }

    private fun headerKey(key: String) {
        ascii(key)
        require(prototypeKeys.none { it.lowercase() == key.lowercase() }) {
            "Prototype-sensitive header key is outside the JSON boundary"
        }
    }

    // Object.keys enumerates canonical array-index properties before other keys.
    fun keys(value: Map<String, Any?>): List<String> {
        val indexes = value.keys.mapNotNull { key ->
            val number = key.toLongOrNull()
            if (number != null && number >= 0 && number < 4294967295L && number.toString() == key) {
                key to number
            } else null
        }.sortedBy { it.second }.map { it.first }
        val indexKeys = indexes.toSet()
        return indexes + value.keys.filter { it !in indexKeys }
    }

    fun ordered(value: Map<String, Any?>): Map<String, Any?> =
        keys(value).associateWithTo(linkedMapOf()) { value[it] }

    fun copy(value: Any?): Any? = copy(value, mutableListOf())

    private fun copy(value: Any?, ancestors: MutableList<Any>): Any? {
        return when (value) {
            null, is String, is Boolean -> value
            is Byte, is Short, is Int, is Float, is Double, is Long -> {
                val number = (value as Number).toDouble()
                require(number.isFinite()) { "JSON numbers must be finite" }
                if (value is Long) {
                    require(value in -9007199254740991L..9007199254740991L) {
                        "Long JSON numbers must be exactly representable in binary64"
                    }
                }
                value
            }
            is Map<*, *>, is List<*> -> {
                require(ancestors.size < 64) { "JSON nesting exceeds 64 containers" }
                require(ancestors.none { it === value }) { "Cyclic JSON input is unsupported" }
                ancestors.add(value)
                val result = if (value is Map<*, *>) {
                    val checked = linkedMapOf<String, Any?>()
                    for ((key, item) in value) {
                        require(key is String) { "JSON object keys must be strings" }
                        require(key !in prototypeKeys) { "Prototype-sensitive JSON key is unsupported" }
                        checked[key] = item
                    }
                    val copied = linkedMapOf<String, Any?>()
                    for (key in keys(checked)) copied[key] = copy(checked[key], ancestors)
                    copied
                } else {
                    (value as List<*>).mapTo(mutableListOf()) { copy(it, ancestors) }
                }
                ancestors.removeAt(ancestors.lastIndex)
                result
            }
            else -> throw IllegalArgumentException("Unsupported non-JSON value")
        }
    }
}
