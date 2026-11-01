// SPDX-License-Identifier: MPL-2.0
// Based on @blue-frontier/mitmproxy src/lib/dns/{util.ip,base}.js and lib/speed/SpeedTester.js.
package com.dsha.sidecar.net.dns

/**
 * Pure upstream candidate policy. These compatibility classifiers are deliberately
 * not literal validators; use [IpAddress] when a complete, strict address is needed.
 * Hostname matching, DNS, speed deduplication and runtime integration belong to callers.
 */
object IpCandidatePolicy {
    class PresetSelection internal constructor(addresses: List<String>) {
        private val storedAddresses = addresses.toList()
        val addresses: List<String> get() = storedAddresses.toList()
        val isPreset: Boolean get() = true
    }

    /**
     * Accepts an already matched JSON preset: null, a string list, or an insertion-ordered
     * string-keyed map. Null means resolve normally. Only the Boolean true bypasses a preset.
     * Object values are enable flags/opaque metadata, never retained by this result.
     */
    fun selectPreset(preset: Any?, ignorePreset: Boolean = false): PresetSelection? {
        if (ignorePreset || preset == null) return null
        val addresses = when (preset) {
            is List<*> -> preset.map {
                require(it is String) { "Preset arrays must contain strings" }
                it
            }
            is Map<*, *> -> {
                // Upstream treats a length property as array-like. That is outside this schema.
                require(!preset.containsKey("length")) { "Array-like preset objects are unsupported" }
                val keys = preset.keys.map {
                    require(it is String) { "Preset object keys must be strings" }
                    it
                }
                // JS enumerates array-index keys first, numerically, then other own keys.
                val (indices, others) = keys.partition { arrayIndex(it) != null }
                (indices.sortedBy { arrayIndex(it) } + others).mapNotNull { key ->
                    if (isEnabled(preset[key])) key else null
                }
            }
            else -> throw IllegalArgumentException("Preset must be null, a string list or an object")
        }
        return addresses.takeIf { it.isNotEmpty() }?.let(::PresetSelection)
    }

    /** BaseDNS.lookup's filter and fallback append. Zero addresses stay in DNS results. */
    fun dnsCandidates(candidates: List<String>?, hostname: String, ipv6Available: Boolean): List<String> {
        val result = ArrayList<String>()
        for (candidate in candidates.orEmpty()) {
            if (ipv6Available || !isIpv6(candidate)) result.add(candidate)
        }
        result.add(hostname)
        return result
    }

    /** The zero-address filter used for both fresh and historical speed candidates. */
    fun speedCandidates(candidates: List<String>): List<String> = candidates.filterNot(::isZeroIp)

    /**
     * Upstream's trim/outer-bracket adapter, followed by the existing strict parser.
     * Dotted IPv6 tails and scoped zero addresses are NOT zero in upstream's group test.
     */
    fun isZeroIp(input: Any?): Boolean {
        if (input !is String) return false
        val value = trimEcmaScript(input).removePrefix("[").removeSuffix("]")
        if (value == "0.0.0.0") return true
        return value.none { it == '.' || it == '%' || it == '[' || it == ']' } &&
            IpAddress.isIpv6(value) && IpAddress.isZeroIp(value)
    }

    /** Mirrors util.ip.js, including the unconditional '[' shortcut and loose regex. */
    fun isIpv6(input: String): Boolean = '[' in input || ipv6Pattern.matches(trimEcmaScript(input))

    private fun isEnabled(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty() && value != "false" && value != "0"
        is Byte, is Short, is Int, is Long, is Float, is Double -> {
            val number = (value as Number).toDouble()
            require(number.isFinite()) { "Preset numbers must be finite JSON numbers" }
            number != 0.0
        }
        is List<*>, is Map<*, *> -> true
        else -> throw IllegalArgumentException("Preset flags must be JSON values")
    }

    private fun arrayIndex(key: String): Long? = key.toLongOrNull()?.takeIf {
        it in 0..4294967294L && it.toString() == key
    }

    private fun trimEcmaScript(value: String): String = value.trim {
        it in '\u0009'..'\u000D' || it == ' ' || it == '\u00A0' || it == '\u1680' ||
            it in '\u2000'..'\u200A' || it == '\u2028' || it == '\u2029' ||
            it == '\u202F' || it == '\u205F' || it == '\u3000' || it == '\uFEFF'
    }

    // Keep this as a compatibility classifier, not a second structural address parser.
    private val ipv6Pattern = Regex(
        """^(?:(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|(?:[0-9a-fA-F]{1,4}:){1,7}:|(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|(?:[0-9a-fA-F]{1,4}:){1,5}(?::[0-9a-fA-F]{1,4}){1,2}|(?:[0-9a-fA-F]{1,4}:){1,4}(?::[0-9a-fA-F]{1,4}){1,3}|(?:[0-9a-fA-F]{1,4}:){1,3}(?::[0-9a-fA-F]{1,4}){1,4}|(?:[0-9a-fA-F]{1,4}:){1,2}(?::[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:(?::[0-9a-fA-F]{1,4}){1,6}|:(?:(?::[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(?::[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]+|::(?:ffff(?::0{1,4})?:)?(?:(?:25[0-5]|(?:2[0-4]|1?\d)?\d)\.){3}(?:25[0-5]|(?:2[0-4]|1?\d)?\d)|(?:[0-9a-fA-F]{1,4}:){1,4}:(?:(?:25[0-5]|(?:2[0-4]|1?\d)?\d)\.){3}(?:25[0-5]|(?:2[0-4]|1?\d)?\d))$"""
    )
}
