// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderCatalog
import com.dsha.sidecar.net.dns.DnsProviderConfig
import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.dns.DnsProviderJson
import com.dsha.sidecar.net.dns.IpAddress
import com.dsha.sidecar.net.dns.Nat64Address
import java.net.URI
import java.util.Locale

object NativeNat64Config {
    /** Apply the upstream empty-prefix/domain gate BEFORE frozen catalog/provider selection. */
    fun normalizeCatalog(
        providers: Map<String, Any?>,
        options: Map<String, Any?>,
    ): DnsProviderCatalog {
        val copy = DnsProviderJson.copyObject(options)
        val nat64 = DnsProviderJson.objectOrEmpty(copy["nat64"], "nat64")
        val prefix = if (nat64["enabled"] == true)
            DnsProviderJson.text(nat64["prefix"], "nat64.prefix").orEmpty() else ""
        val domains = nat64["domains"]
        val enabled = nat64["enabled"] == true && prefix.isNotEmpty() && domains is List<*> && domains.isNotEmpty()
        if (enabled) require(Nat64Address.toNat64Address(prefix, "0.0.0.1") != null) { "Invalid NAT64 /96 prefix" }
        return DnsProviderConfig.normalize(providers, copy + ("nat64" to (nat64 + ("enabled" to enabled))))
    }

    internal data class Route(val provider: DnsProviderDescriptor, val destination: String)
    internal data class Plan(
        val descriptor: DnsProviderDescriptor,
        val prefix: String,
        val routes: List<Route>,
        val cacheTtl: Long,
        var lastSuccess: String? = null,
    )

    internal fun plan(descriptor: DnsProviderDescriptor, limits: NativeNat64Limits): Plan {
        require(descriptor.transport == "nat64")
        val config = DnsProviderJson.copyObject(descriptor.nat64Options.orEmpty())
        val prefix = DnsProviderJson.text(config["prefix"], "nat64.prefix").orEmpty()
        if (config["enabled"] == false || prefix.isEmpty()) throw NativeNat64DisabledException()
        require(Nat64Address.toNat64Address(prefix, "0.0.0.1") != null) { "Invalid NAT64 /96 prefix" }
        val providers = DnsProviderJson.objectOrEmpty(config["providers"], "nat64.providers")
        val bootstrap = linkedMapOf(
            "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6"),
            "doh.pub" to listOf("1.12.12.12", "120.53.53.53"),
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
        )
        val overrides = DnsProviderJson.objectOrEmpty(config["bootstrap"], "nat64.bootstrap")
        require(overrides.size <= 64) { "Too many NAT64 bootstrap hosts" }
        for ((host, values) in overrides) {
            val name = NativeDnsWire.name(host)
            require(values is List<*> && values.size <= 8 &&
                values.all { it is String && IpAddress.isIpv4(it) }) { "NAT64 bootstrap requires strict IPv4 lists" }
            bootstrap[name] = values.map { it as String }
        }
        val entries = DnsProviderConfig.resolveDohList(config, providers)
        require(entries.size <= limits.maxDohEndpoints) { "Too many NAT64 DoH endpoints" }
        val routes = entries.mapNotNull { entry ->
            val uri = URI(entry.url)
            require(uri.scheme?.lowercase(Locale.ROOT) == "https" && uri.host != null &&
                uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535) &&
                entry.url.length <= 4096) { "NAT64 DoH requires a valid HTTPS URL" }
            val host = uri.host.lowercase(Locale.ROOT)
            val addresses = if (IpAddress.isIpv4(host)) listOf(host) else bootstrap[host]
            val ipv4 = addresses?.firstOrNull() ?: return@mapNotNull null
            val destination = requireNotNull(Nat64Address.toNat64Address(prefix, ipv4))
            Route(DnsProviderDescriptor(
                "nat64-doh:${descriptor.key}:${entry.url}", entry.url, "https", entry.url,
                sni = entry.sni.takeIf { it.isNotEmpty() },
            ), destination)
        }
        require(routes.isNotEmpty()) { "No NAT64 DoH endpoint has a configured IPv4 bootstrap" }
        val rawTtl = config["cacheTtl"]
        val ttl = if (rawTtl == null) 300_000L else {
            val number = rawTtl.toString().toLongOrNull()
            require(number != null && number in 0..86_400_000) { "Invalid NAT64 cacheTtl" }
            number.takeIf { it > 0 } ?: 300_000
        }
        return Plan(descriptor.copy(nat64Options = config), prefix, routes, ttl)
    }
}
