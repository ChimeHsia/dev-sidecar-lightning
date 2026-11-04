// SPDX-License-Identifier: MPL-2.0
// Adapted from @blue-frontier/mitmproxy src/lib/dns/{index,nat64}.js.
package com.dsha.sidecar.net.dns

/**
 * An inert constructor description, before transport/BaseDNS defaults are applied.
 * [key] is "provider:<configured name>" or "builtin:PreSet"/"builtin:Nat64".
 * [family], [port] and [cacheSize] retain JSON numbers/strings without parseInt.
 * No resolver, cache, socket, timer, logger or runtime provider is created.
 */
data class DnsProviderDescriptor(
    val key: String,
    val name: String,
    val transport: String,
    val server: String? = null,
    val port: Any? = null,
    val family: Any? = null,
    val cacheSize: Any? = null,
    val sni: String? = null,
    val echOptions: Map<String, Any?>? = null,
    val nat64Options: Map<String, Any?>? = null,
)

/** Constructor order and final upstream dnsMap bindings, including ForSNI. */
data class DnsProviderCatalog(
    val descriptors: List<DnsProviderDescriptor>,
    val bindings: Map<String, String>,
) {
    fun providerKey(name: String): String? = bindings[name]
}

data class DnsProviderDohEntry(val url: String, val sni: String = "")

/**
 * Pure configuration boundary for decoded JSON maps/lists. Object iteration order
 * follows JavaScript (array-index keys first, then insertion order).
 *
 * Text fields accept strings/null; family/port/cacheSize accept numbers/strings/null.
 * NAT64 doh accepts strings, string lists or null. Unsupported coercions fail with
 * IllegalArgumentException. Each input tree is limited to depth 64 (root is zero)
 * and 100,000 nodes; cycles are rejected. See docs/migration-wave3/dns-config.md.
 */
object DnsProviderConfig {
    fun normalize(
        providers: Map<String, Any?> = emptyMap(),
        options: Map<String, Any?> = emptyMap(),
    ): DnsProviderCatalog {
        val inputs = DnsProviderJson.copyObject(providers)
        val opts = DnsProviderJson.copyObject(options)
        val ech = DnsProviderJson.objectOrEmpty(opts["ech"], "ech")
        val nat64 = DnsProviderJson.objectOrEmpty(opts["nat64"], "nat64")
        val descriptors = mutableListOf<DnsProviderDescriptor>()
        val bindings = linkedMapOf<String, String>()
        for (name in DnsProviderJson.keys(inputs)) {
            val conf = DnsProviderJson.objectRequired(inputs[name], "provider $name")
            var server = DnsProviderJson.text(conf["server"], "server")
                ?.takeIf { it.isNotEmpty() } ?: DnsProviderJson.text(conf["host"], "host")
            server = server?.let(DnsProviderJson::removeFirstWhitespace)
            if (server.isNullOrEmpty()) continue
            val configuredType = DnsProviderJson.text(conf["type"], "type")
            val type = if (configuredType == null) {
                when {
                    server.startsWith("https://") || server.startsWith("http://") -> "https"
                    server.startsWith("tls://") || server.startsWith("dot://") -> "tls"
                    server.startsWith("tcp://") -> "tcp"
                    "://" in server && !server.startsWith("udp://") ->
                        throw IllegalArgumentException("Unknown type DNS: $server, provider: $name")
                    else -> "udp"
                }
            } else {
                DnsProviderJson.removeFirstWhitespace(configuredType).lowercase()
            }
            val transport = when (type) {
                "https", "doh", "dns-over-https" -> "https"
                "tls", "dot", "dns-over-tls" -> "tls"
                "tcp" -> "tcp"
                else -> "udp"
            }
            var port = DnsProviderJson.scalar(conf["port"], "port")
            if (transport == "https") {
                if ('/' !in server) server = "https://$server/dns-query"
            } else {
                if ("://" in server) server = server.split("://")[1]
                if (port == null && ':' in server) {
                    if ("]:" in server) {
                        val parts = server.split("]:")
                        server = parts[0].drop(1)
                        port = parts[1]
                    } else if (!isUpstreamIpv6(server)) {
                        val parts = server.split(':')
                        server = parts[0]
                        port = parts[1]
                    }
                }
                server = server.replace("[", "").replace("]", "")
            }
            val sni = if (transport == "https" || transport == "tls") {
                DnsProviderJson.text(conf["sni"], "sni")?.takeIf { it.isNotEmpty() }
                    ?: DnsProviderJson.text(conf["servername"], "servername")
            } else {
                null
            }
            val key = "provider:$name"
            descriptors += DnsProviderDescriptor(
                key, name, transport, server, port = if (transport == "https") null else port,
                family = DnsProviderJson.scalar(conf["family"], "family"),
                cacheSize = DnsProviderJson.scalar(conf["cacheSize"], "cacheSize"),
                sni = sni,
                echOptions = ech + ("enabled" to (ech["enabled"] != false && conf["ech"] != false)),
            )
            bindings[name] = key
            if (DnsProviderJson.truthy(conf["forSNI"]) || DnsProviderJson.truthy(conf["forSni"])) {
                bindings["ForSNI"] = key
            }
        }
        descriptors += DnsProviderDescriptor("builtin:PreSet", "PreSet", "preset")
        bindings["PreSet"] = "builtin:PreSet"
        if (bindings["ForSNI"] == null) bindings["ForSNI"] = "builtin:PreSet"
        if (nat64["enabled"] == true) {
            descriptors += DnsProviderDescriptor(
                "builtin:Nat64", "NAT64", "nat64",
                echOptions = ech + ("enabled" to (ech["enabled"] != false && nat64["ech"] != false)),
                nat64Options = nat64 + ("providers" to inputs),
            )
            bindings["Nat64"] = "builtin:Nat64"
        }
        return DnsProviderCatalog(descriptors.toList(), bindings.toMap())
    }

    fun toDohList(doh: Any?): List<String> {
        val input = DnsProviderJson.copyJson(doh)
        val list = when (input) {
            null -> emptyList()
            is String -> input.split(',')
            is List<*> -> input.map {
                require(it is String) { "doh list entries must be strings" }
                it
            }
            else -> throw IllegalArgumentException("doh must be a string, string list or null")
        }
        return list.map(DnsProviderJson::trim).filter { it.isNotEmpty() }
    }

    fun resolveDohList(
        config: Map<String, Any?> = emptyMap(),
        providers: Map<String, Any?> = emptyMap(),
    ): List<DnsProviderDohEntry> {
        val input = DnsProviderJson.copyObject(config)
        val inputs = DnsProviderJson.copyObject(providers)
        val entries = mutableListOf<DnsProviderDohEntry>()
        val name = DnsProviderJson.text(input["dns"], "dns")?.let(DnsProviderJson::trim).orEmpty()
        if (name.isNotEmpty()) {
            val conf = inputs[name]?.let { DnsProviderJson.objectRequired(it, "provider $name") }
            if (conf != null) {
                val server = DnsProviderJson.text(conf["server"], "server")?.takeIf { it.isNotEmpty() }
                    ?: DnsProviderJson.text(conf["host"], "host").orEmpty()
                val cleaned = DnsProviderJson.removeAllWhitespace(server)
                val type = DnsProviderJson.text(conf["type"], "type")
                    ?.let(DnsProviderJson::removeAllWhitespace)?.lowercase()
                val isDoh = if (type == null) {
                    cleaned.startsWith("http://") || cleaned.startsWith("https://")
                } else {
                    type in listOf("https", "doh", "dns-over-https")
                }
                if (cleaned.isNotEmpty() && isDoh) {
                    val url = if ('/' in cleaned) cleaned else "https://$cleaned/dns-query"
                    val sni = DnsProviderJson.text(conf["sni"], "sni")?.takeIf { it.isNotEmpty() }
                        ?: DnsProviderJson.text(conf["servername"], "servername").orEmpty()
                    entries += DnsProviderDohEntry(url, sni)
                }
            }
        }
        entries += toDohList(input["doh"]).map { DnsProviderDohEntry(it) }
        entries += defaultDoh.map { DnsProviderDohEntry(it) }
        return entries.distinctBy { it.url }
    }

    private val defaultDoh = listOf(
        "https://dns.alidns.com/resolve",
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/resolve",
    )

    // initDNS uses util.ip.isIPv6, whose bracket shortcut and narrower scoped
    // syntax differ from the committed strict IpAddress validator.
    private fun isUpstreamIpv6(server: String): Boolean {
        if ('[' in server) return true
        val value = DnsProviderJson.trim(server)
        if ('%' in value) {
            return Regex("^fe80:(?::[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]+$").matches(value)
        }
        return !value.startsWith('[') && !value.endsWith(']') && IpAddress.isIpv6(value)
    }
}

internal object DnsProviderJson {
    // ECMAScript \s / trim whitespace, including BOM but excluding U+0085.
    private val whitespace = Regex("[\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")
    fun removeFirstWhitespace(value: String): String = whitespace.replaceFirst(value, "")
    fun removeAllWhitespace(value: String): String = whitespace.replace(value, "")
    fun trim(value: String): String = value.trim {
        it in '\u0009'..'\u000d' || it == ' ' || it == '\u00a0' || it == '\u1680' ||
            it in '\u2000'..'\u200a' || it in "\u2028\u2029\u202f\u205f\u3000\ufeff"
    }

    fun text(value: Any?, field: String): String? {
        require(value == null || value is String) { "$field must be a string or null" }
        return value as String?
    }

    fun scalar(value: Any?, field: String): Any? {
        require(value == null || value is String || value is Number) {
            "$field must be a number, string or null"
        }
        return value
    }

    fun truthy(value: Any?): Boolean = when (value) {
        null, false -> false
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0 && !value.toDouble().isNaN()
        else -> true
    }

    fun objectOrEmpty(value: Any?, field: String): Map<String, Any?> =
        if (value == null) emptyMap() else objectRequired(value, field)

    fun objectRequired(value: Any?, field: String): Map<String, Any?> {
        require(value is Map<*, *>) { "$field must be an object" }
        return copyObject(value)
    }

    @Suppress("UNCHECKED_CAST")
    fun copyObject(value: Map<*, *>): Map<String, Any?> =
        copyJson(value) as Map<String, Any?>

    fun copyJson(value: Any?): Any? = BoundedCopy().copy(value, 0)

    private class BoundedCopy {
        private var nodes = 0
        private val ancestors = mutableListOf<Any>()

        fun copy(value: Any?, depth: Int): Any? {
            require(depth <= 64) { "JSON depth exceeds 64" }
            require(++nodes <= 100_000) { "JSON node count exceeds 100000" }
            val container = value is Map<*, *> || value is List<*>
            if (container) {
                require(ancestors.none { it === value }) { "Cyclic JSON container" }
                ancestors.add(value!!)
            }
            try {
                return when (value) {
                    is Map<*, *> -> {
                        val result = linkedMapOf<String, Any?>()
                        for ((key, item) in value) {
                            require(key is String) { "JSON keys must be strings" }
                            result[key] = copy(item, depth + 1)
                        }
                        result
                    }
                    is List<*> -> {
                        // Do not preallocate an unbounded source collection's size.
                        val result = mutableListOf<Any?>()
                        for (item in value) result.add(copy(item, depth + 1))
                        result
                    }
                    null, is String, is Boolean -> value
                    is Number -> {
                        require(value.toDouble().isFinite()) { "JSON numbers must be finite" }
                        value
                    }
                    else -> throw IllegalArgumentException("Unsupported JSON value")
                }
            } finally {
                if (container) ancestors.removeAt(ancestors.lastIndex)
            }
        }
    }

    fun keys(value: Map<String, Any?>): List<String> {
        fun index(key: String): Long? = key.toLongOrNull()?.takeIf {
            it in 0..4294967294L && it.toString() == key
        }
        return value.keys.filter { index(it) != null }.sortedBy { index(it) } +
            value.keys.filter { index(it) == null }
    }
}
