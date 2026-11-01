/*
 * SPDX-License-Identifier: MPL-2.0
 * Adapted from @blue-frontier/mitmproxy/src/lib/proxy/mitmproxy/
 * createRequestHandler.js, canRetryWithBody (docmirror.cn).
 */
package com.dsha.sidecar.net.http

/**
 * Request-data eligibility only. This neither enables retries nor guarantees that
 * a body has been buffered. See docs/migration-wave3/http-policy.md for the JSON
 * boundary and the separate caller responsibilities in retry.js.
 */
object RequestRetryPolicy {
    /**
     * [methods] is the caller's already normalized list, matched case-sensitively.
     * Header lookup uses exact lowercase keys, as in the upstream request object.
     * [maxRetryBodySize] is explicit; the pinned source passes 1,048,576 bytes.
     *
     * Unsupported JSON/coercion inputs throw [IllegalArgumentException], rather
     * than silently deciding that a request is eligible or ineligible.
     */
    fun canRetryWithBody(
        method: Any?,
        headers: Map<String, Any?>,
        methods: List<String>,
        maxRetryBodySize: Int,
    ): Boolean {
        require(maxRetryBodySize >= 0) { "Retry body threshold must be nonnegative" }
        val copiedMethod = HttpPolicyJson.copy(method)
        val copiedHeaders = HttpPolicyJson.headers(headers)
        methods.forEach(HttpPolicyJson::ascii)
        val requestMethod = if (HttpPolicyJson.truthy(copiedMethod)) {
            require(copiedMethod is String) { "Truthy request method must be a string" }
            HttpPolicyJson.ascii(copiedMethod)
            copiedMethod.uppercase()
        } else {
            "GET"
        }
        if (requestMethod !in methods) return false
        if (HttpPolicyJson.truthy(copiedHeaders["transfer-encoding"])) return false

        val expect = copiedHeaders["expect"]
        if (HttpPolicyJson.truthy(expect)) {
            require(expect is String) { "Truthy Expect must be a string" }
            if (expect.lowercase().contains("100-continue")) return false
        }

        if (requestMethod in listOf("POST", "PUT", "PATCH", "DELETE")) {
            val contentLength = parseInt10(copiedHeaders["content-length"])
            if (!contentLength.isFinite() || contentLength > maxRetryBodySize) return false
        }
        return true
    }

    private fun parseInt10(value: Any?): Double = when (value) {
        is String -> {
            var index = 0
            while (index < value.length && HttpPolicyJson.whitespace(value[index])) index++
            var sign = 1.0
            if (index < value.length && (value[index] == '-' || value[index] == '+')) {
                if (value[index] == '-') sign = -1.0
                index++
            }
            val start = index
            var number = 0.0
            while (index < value.length && value[index] in '0'..'9') {
                number = number * 10 + (value[index] - '0')
                index++
            }
            if (index == start) Double.NaN else sign * number
        }
        is Number -> {
            val number = value.toDouble()
            val magnitude = kotlin.math.abs(number)
            // JS switches Number.toString to exponential notation outside this
            // range. Portable shortest-number formatting is outside this port.
            require(magnitude == 0.0 || magnitude >= 0.000001 && magnitude < 1e21) {
                "Exponential numeric Content-Length is outside the boundary; pass a string"
            }
            if (number < 0) kotlin.math.ceil(number) else kotlin.math.floor(number)
        }
        // Array.toString joins with commas: only the first element can supply
        // the leading decimal digits, including recursively nested arrays.
        is List<*> -> parseInt10(value.firstOrNull())
        else -> Double.NaN
    }
}
