// SPDX-License-Identifier: MPL-2.0
// Based on @blue-frontier/mitmproxy 2.3.1 src/lib/dns/util.ip.js.
package com.dsha.sidecar.net.dns

/**
 * Literal-only address helpers: no name service or network access.
 *
 * Unlike upstream util.ip.js, all helpers validate the complete input and reject
 * surrounding whitespace, partial brackets and malformed addresses. IPv4 uses
 * four decimal octets without leading zeros. IPv6 may have paired brackets and
 * one raw scope ID containing ASCII letters, digits, underscores, dots or hyphens.
 * URI escapes are not decoded. A scope never makes an invalid address valid.
 */
object IpAddress {
    fun isIpv4(address: String?): Boolean = parseIpv4(address) != null

    fun isIpv6(address: String?): Boolean = parseIpv6(address) != null

    fun isIpLiteral(address: String?): Boolean = isIpv4(address) || isIpv6(address)

    /** Only a successfully parsed address whose every bit is zero qualifies. */
    fun isZeroIp(address: String?): Boolean {
        val groups = parseIpv4(address) ?: parseIpv6(address) ?: return false
        return groups.all { it == 0 }
    }

    internal fun parseIpv4(address: String?): IntArray? {
        if (address == null || address.length !in 7..15) return null
        val parts = address.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.length !in 1..3 || part.any { it !in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            octets[index] = value
        }
        return octets
    }

    /** Returns eight fresh 16-bit groups, including for an IPv4 tail. */
    internal fun parseIpv6(address: String?): IntArray? {
        if (address.isNullOrEmpty()) return null
        var text = address
        if (text.startsWith('[') || text.endsWith(']')) {
            if (!text.startsWith('[') || !text.endsWith(']')) return null
            text = text.substring(1, text.length - 1)
        }
        val scopeIndex = text.indexOf('%')
        if (scopeIndex >= 0) {
            val scope = text.substring(scopeIndex + 1)
            if (scope.isEmpty() || scope.any {
                    it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' &&
                        it != '_' && it != '.' && it != '-'
                }
            ) return null
            text = text.substring(0, scopeIndex)
        }
        if (text.length !in 2..45) return null

        if ('.' in text) {
            val separator = text.lastIndexOf(':')
            if (separator < 0) return null
            val octets = parseIpv4(text.substring(separator + 1)) ?: return null
            val high = (octets[0] shl 8) or octets[1]
            val low = (octets[2] shl 8) or octets[3]
            text = text.substring(0, separator + 1) + high.toString(16) + ":" + low.toString(16)
        }

        val compression = text.indexOf("::")
        if (compression < 0) {
            val groups = parseGroups(text) ?: return null
            return groups.takeIf { it.size == 8 }
        }
        if (compression != text.lastIndexOf("::")) return null
        val head = parseGroups(text.substring(0, compression)) ?: return null
        val tail = parseGroups(text.substring(compression + 2)) ?: return null
        // "::" must replace at least one group, never just a separator.
        if (head.size + tail.size >= 8) return null
        return IntArray(8).also {
            head.copyInto(it)
            tail.copyInto(it, 8 - tail.size)
        }
    }

    private fun parseGroups(text: String): IntArray? {
        if (text.isEmpty()) return IntArray(0)
        val parts = text.split(':')
        if (parts.size > 8) return null
        val groups = IntArray(parts.size)
        for ((index, part) in parts.withIndex()) {
            if (part.length !in 1..4 || part.any {
                    it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F'
                }
            ) return null
            groups[index] = part.toInt(16)
        }
        return groups
    }
}
