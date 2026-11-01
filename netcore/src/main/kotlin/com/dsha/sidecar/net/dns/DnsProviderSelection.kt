// SPDX-License-Identifier: MPL-2.0
// Adapted from @blue-frontier/mitmproxy src/lib/dns/index.js.
package com.dsha.sidecar.net.dns

import com.dsha.sidecar.net.rules.HostnameMatcher

/** Null family means no mapping override; provider family stays in its descriptor. */
data class DnsProviderChoice(val providerKey: String, val family: Any? = null)

/**
 * Snapshot of the supported JSON configuration used by getDNSAndFamily.
 * Domain inputs are raw arrays/maps, before upstream domainMapRegexply.
 * Matching reuses HostnameMatcher's exact/apex/insertion-order precedence.
 * No default DNS is invented for an absent or unknown hostname mapping.
 */
class DnsProviderSelection(
    catalog: DnsProviderCatalog,
    mapping: Map<String, Any?> = emptyMap(),
    preSetIpList: Map<String, Any?> = emptyMap(),
    ech: Map<String, Any?>? = null,
    nat64Domains: Any? = null,
    echDomains: Any? = ech?.get("domains"),
    echPreSetIpDomains: Any? = ech?.get("preSetIpDomains"),
) {
    private val bindings = catalog.bindings.toMap()
    private val mappingMatcher = HostnameMatcher(DnsProviderJson.copyObject(mapping))
    private val preSetMatcher = HostnameMatcher(DnsProviderJson.copyObject(preSetIpList))
    private val echOptions = ech?.let(DnsProviderJson::copyObject)
    private val nat64Matcher = domainMatcher(nat64Domains)
    private val echMatcher = domainMatcher(echDomains)
    private val echPreSetMatcher = domainMatcher(echPreSetIpDomains)

    fun isNat64Domain(hostname: String): Boolean =
        bindings["Nat64"] != null && hostname.isNotEmpty() && nat64Matcher.match(hostname) != null

    fun isEchDomain(hostname: String): Boolean =
        isNat64Domain(hostname) || (echOptions != null && echOptions["enabled"] != false &&
            echOptions["use"] != false && hostname.isNotEmpty() && echMatcher.match(hostname) != null)

    fun isEchIgnorePreSetIp(hostname: String): Boolean =
        isNat64Domain(hostname) || echOptions == null ||
            hostname.isEmpty() || echPreSetMatcher.match(hostname) == null

    fun getEchProviderKey(): String? {
        val name = DnsProviderJson.text(echOptions?.get("dns"), "ech.dns")
        return if (name.isNullOrEmpty()) null else bindings[name]
    }

    fun select(hostname: String): DnsProviderChoice? {
        val isEch = isEchDomain(hostname)
        if (isNat64Domain(hostname)) return choice("Nat64")
        if (!isEch || !isEchIgnorePreSetIp(hostname)) {
            if (DnsProviderJson.truthy(preSetMatcher.match(hostname))) return choice("PreSet")
        }
        if (isEch) getEchProviderKey()?.let { return DnsProviderChoice(it) }
        val data = mappingMatcher.match(hostname)
        if (!DnsProviderJson.truthy(data)) return null
        val conf = DnsProviderJson.objectRequired(data, "mapping entry")
        val name = DnsProviderJson.text(conf["dnsName"], "dnsName") ?: return null
        val key = if (name == "usa" && bindings["usa"] == null && bindings["cloudflare"] != null) {
            bindings["cloudflare"]
        } else {
            bindings[name]
        }
        return key?.let { DnsProviderChoice(it, DnsProviderJson.scalar(conf["family"], "family")) }
    }

    private fun choice(name: String): DnsProviderChoice? =
        bindings[name]?.let { DnsProviderChoice(it) }

    private fun domainMatcher(domains: Any?): HostnameMatcher<Boolean> {
        val map = linkedMapOf<String, Boolean>()
        when (val input = DnsProviderJson.copyJson(domains)) {
            null -> Unit
            is List<*> -> for (domain in input) {
                if (domain is String && domain.isNotEmpty()) map[domain] = true
            }
            is Map<*, *> -> for ((key, value) in input) {
                require(key is String) { "Domain map keys must be strings" }
                if (value != false && value != null) map[key] = true
            }
            else -> throw IllegalArgumentException("Domains must be a list, object or null")
        }
        return HostnameMatcher(map)
    }
}
