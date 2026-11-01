// SPDX-License-Identifier: MPL-2.0
// Ported from @blue-frontier/mitmproxy 2.3.1 src/utils/util.match.js.
package com.dsha.sidecar.net.rules

/**
 * Matches a dev-sidecar hostname map using its single-result precedence:
 * exact hostname, apex wildcard shortcuts, then wildcard/regex insertion order.
 *
 * A leading dot is an alias for "*."; both include the apex domain. Patterns
 * starting with "^" are regular expressions, all other "*" characters are
 * domain wildcards. Matching is case-sensitive, as in the upstream utility.
 *
 * Invalid regexes are ignored rather than failing a request. Map lookups also
 * avoid upstream JavaScript object-prototype matches. This implements
 * matchHostname, not matchHostnameAll's configuration merge/capture semantics.
 */
class HostnameMatcher<T : Any>(entries: Map<String, T?>) {

    private val origin = LinkedHashMap<String, T?>()
    private val patterns: List<Pair<Regex, T?>>

    init {
        val expressions = LinkedHashMap<String, T?>()
        for ((key, value) in entries) {
            val domain = if (key.startsWith('.')) {
                if (entries["*$key"] != null) continue
                "*$key"
            } else {
                key
            }
            if ('*' in domain || domain.startsWith('^')) {
                val expression = if (domain.startsWith('^')) {
                    domain
                } else {
                    "^${domain.replace(".", "\\.").replace("*", ".*")}$"
                }
                expressions[expression] = value
                if (domain.startsWith('*') && domain.lastIndexOf('*') == 0) {
                    origin[domain] = value
                }
            } else {
                origin[domain] = value
            }
        }
        patterns = expressions.mapNotNull { (expression, value) ->
            try {
                Regex(expression) to value
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun match(hostname: String): T? {
        origin[hostname]?.let { return it }
        origin["*.$hostname"]?.let { return it }
        origin["*$hostname"]?.let { return it }
        for ((pattern, value) in patterns) {
            if (pattern.containsMatchIn(hostname)) return value
        }
        return null
    }
}
