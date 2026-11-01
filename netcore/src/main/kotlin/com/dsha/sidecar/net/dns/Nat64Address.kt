// SPDX-License-Identifier: MPL-2.0
// Based on @blue-frontier/mitmproxy 2.3.1 src/lib/dns/nat64.js.
package com.dsha.sidecar.net.dns

/**
 * Pure IPv4 embedding with an explicitly supplied NAT64 /96 network.
 *
 * Unlike upstream nat64.js, malformed prefixes are never padded or truncated.
 * Only /96 is supported: a bare IPv6 network implies /96, or it may end in
 * exactly "/96". The low 32 bits must be zero, and the network must be nonzero.
 * Brackets, scopes and whitespace are rejected in prefixes. Invalid input
 * returns null. There is no default prefix, gateway, discovery or DNS lookup.
 */
object Nat64Address {
    /** Returns eight lowercase, unpadded hex groups, matching upstream output. */
    fun toNat64Address(prefix: String?, ipv4: String?): String? {
        if (prefix.isNullOrEmpty()) return null
        if (prefix.any { it == '[' || it == ']' || it == '%' }) return null
        val network = if ('/' in prefix) {
            if (!prefix.endsWith("/96")) return null
            prefix.removeSuffix("/96")
        } else {
            prefix
        }
        val groups = IpAddress.parseIpv6(network) ?: return null
        if (groups[6] != 0 || groups[7] != 0 || groups.all { it == 0 }) return null
        val octets = IpAddress.parseIpv4(ipv4) ?: return null
        groups[6] = (octets[0] shl 8) or octets[1]
        groups[7] = (octets[2] shl 8) or octets[3]
        return groups.joinToString(":") { it.toString(16) }
    }
}
