// SPDX-License-Identifier: MPL-2.0
// Based on @blue-frontier/mitmproxy src/lib/dns/base.js.
package com.dsha.sidecar.net.dns

/**
 * Immutable, bounded state for one upstream IPv6 availability epoch.
 * The caller supplies interface capability and reports only relevant ENETUNREACH events.
 * No interface enumeration, clock, network access, automatic recovery or success reset.
 */
class Ipv6Availability private constructor(
    val available: Boolean,
    val errorCount: Int,
) {
    fun reportUnreachable(address: String): Ipv6Availability {
        if (!available || !IpCandidatePolicy.isIpv6(address)) return this
        val nextCount = errorCount + 1
        return Ipv6Availability(nextCount < 3, nextCount)
    }

    companion object {
        fun fromInterfaceAvailability(available: Boolean): Ipv6Availability = Ipv6Availability(available, 0)
    }
}
