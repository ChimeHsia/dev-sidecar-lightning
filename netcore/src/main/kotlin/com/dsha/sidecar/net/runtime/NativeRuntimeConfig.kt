package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.config.ConfigMerge
import com.dsha.sidecar.net.dns.DnsProviderCatalog
import com.dsha.sidecar.net.dns.DnsProviderConfig
import com.dsha.sidecar.net.dns.DnsProviderJson
import com.dsha.sidecar.net.rules.native.IssueKind
import com.dsha.sidecar.net.rules.native.PlannerJson
import com.dsha.sidecar.net.rules.native.RulePlanner
import java.util.Collections

class NativeRuntimeConfigurationException(val problems: List<String>) :
    IllegalArgumentException("Native runtime configuration rejected: " + problems.joinToString("; "))

/**
 * One immutable decoded-JSON epoch. No key files, JSON parser, DNS query or socket is used at load.
 * Repository/persistence adapters can retain this snapshot until a new runtime is explicitly built.
 */
class NativeRuntimeConfig private constructor(
    val decoded: Map<String, Any?>,
    val responseHeadersEnabled: Boolean,
    val diagnostics: List<String>,
    internal val planner: RulePlanner,
    internal val catalog: DnsProviderCatalog,
    internal val dns: Map<String, Any?>,
    internal val presets: Map<String, Any?>,
    val requiresEchAdapter: Boolean,
    val listenPort: Int?,
) {
    companion object {
        fun decode(config: Map<String, Any?>): NativeRuntimeConfig = decodeLayers(listOf(config))

        fun decodeLayers(layers: List<Map<String, Any?>>): NativeRuntimeConfig {
            require(layers.size in 1..64)
            var merged: Map<String, Any?> = emptyMap()
            for (layer in layers) {
                merged = ConfigMerge.merge(merged, PlannerJson.obj(PlannerJson.snapshot(layer)))
            }
            val decoded = PlannerJson.obj(PlannerJson.snapshot(ConfigMerge.sanitize(merged, true)))
            val server = if ("server" in decoded) objectAt(decoded, "server") else decoded
            val setting = objectAt(server, "setting")
            for (name in listOf("whiteList", "intercept", "cloudflareRoute", "compatible")) {
                val value = server[name]
                if (value != null && value !is Map<*, *> && value != emptyList<Any?>()) {
                    throw NativeRuntimeConfigurationException(listOf("server.$name must be an object"))
                }
            }
            val script = objectAt(setting, "script")
            val problems = mutableListOf<String>()
            // The enabled flag gates header interception too; it does not authorize script execution.
            val diagnostics = mutableListOf(
                "Precommit retries exclude failed destinations within each request and never replay unbuffered uploads.",
                "Cache rules use source-compatible 304/header operations only; memory entity storage is disabled.",
                "Response entities stream unless a configured HTML script action needs bounded preparation.",
            )
            for ((key, value) in objectAt(server, "compatible")) {
                if (activeUnboundValue(value)) problems.add("server.compatible.$key is unsupported")
            }
            if (objectAt(server, "cloudflareRoute")["enabled"] == true) {
                problems.add("Enabled cloudflareRoute requires a configured native endpoint adapter")
            }
            for ((key, value) in script) if (key !in setOf("enabled", "defaultDir") && activeUnboundValue(value)) {
                problems.add("setting.script.$key has no runtime binding")
            }
            when (server["enabled"]) {
                null, true -> Unit
                false -> problems.add("server.enabled is false; the native proxy must not start")
                else -> problems.add("server.enabled must be a Boolean")
            }
            for ((key, value) in server) {
                if (key !in setOf("enabled", "dns", "setting", "intercepts", "preSetIpList", "port",
                        "host", "fakeServerMaxLength", "compatible", "cloudflareRoute", "intercept",
                        "whiteList", "app", "plugin", "proxy") &&
                    activeUnboundValue(value)) problems.add("server.$key has no runtime binding")
            }
            for ((key, value) in setting) {
                if (key !in setOf("script", "verifySsl", "NODE_TLS_REJECT_UNAUTHORIZED", "allowTls12",
                        "userBasePath", "rootCaFile", "rootDir", "defaultTimeout", "defaultKeepAliveTimeout",
                        "timeoutMapping", "lowSpeedDelay") && activeUnboundValue(value)) {
                    problems.add("setting.$key has no runtime binding")
                }
            }
            val port = server["port"]?.let {
                val number = (it as? Number)?.toDouble()
                require(number != null && number.isFinite() && number % 1.0 == 0.0 && number in 0.0..65535.0) {
                    "server.port must be an integer in 0..65535"
                }
                number.toInt()
            }
            val headerGate = PlannerJson.truthy(script["enabled"])
            if (headerGate) diagnostics.add("setting.script.enabled enables response actions and bounded HTML injection.")
            for ((host, paths) in objectAt(server, "intercepts")) {
                for ((path, raw) in PlannerJson.obj(paths)) {
                    if (path == "matched") continue
                    val rule = PlannerJson.obj(raw)
                    for ((key, value) in rule) {
                        if (!PlannerJson.truthy(value)) continue
                        if (key == "cacheImmutable" && value == "false" && (PlannerJson.maxAge(rule) ?: 0.0) > 0) {
                            problems.add("$host $path cacheImmutable string false has unsupported legacy coercion; use Boolean false")
                        }
                        if (key == "proxy" && (value as? String)?.startsWith("http://") == true) {
                            diagnostics.add("$host $path: HTTP upstream routes cannot serve HTTPS CONNECT requests.")
                        }
                        if (key in setOf("requestReplace", "responseReplace")) {
                            for ((header, _) in objectAt(PlannerJson.obj(value), "headers")) {
                                if (header.lowercase(java.util.Locale.ROOT) in setOf(
                                        "content-length", "transfer-encoding", "content-encoding", "connection",
                                        "upgrade", "expect", "sec-websocket-key", "sec-websocket-accept")) {
                                    problems.add("$host $path $key.headers.$header changes framing/negotiation")
                                }
                            }
                        }
                    }
                    val local = (rule["success"] ?: rule["abort"]) as? Map<*, *>
                    local?.values?.filterIsInstance<String>()?.forEach {
                        if (it.toByteArray(Charsets.UTF_8).size > 65_536) problems.add("$host $path local body exceeds 64 KiB")
                    }
                }
            }
            val rawDns = objectAt(server, "dns")
            val acceptedDnsKeys = setOf("providers", "mapping", "familyMapping", "ech", "nat64", "preSetIpList", "speedTest")
            for ((key, value) in rawDns) if (key !in acceptedDnsKeys && activeUnboundValue(value)) {
                problems.add("dns.$key has no runtime binding")
            }
            // initDNS expects options.js's effective NAT64 gate, not the raw enabled checkbox.
            val nat64 = objectAt(rawDns, "nat64")
            val declared = nat64["enabled"] == true &&
                (nat64["domains"] as? List<*>)?.isNotEmpty() == true
            val prefixValue = nat64["prefix"]
            if (declared && prefixValue != null && prefixValue !is String) {
                problems.add("dns.nat64.prefix must be a string")
            }
            val prefix = DnsProviderJson.trim((prefixValue as? String).orEmpty())
            val nat64Enabled = declared && prefix.isNotEmpty()
            if (!nat64Enabled && declared) diagnostics.add("dns.nat64 is inactive: the configured prefix is blank.")
            val mapping = normalizeDnsMapping(rawDns, problems)
            val dns = PlannerJson.obj(PlannerJson.snapshot(rawDns + mapOf(
                "mapping" to mapping, "nat64" to (nat64 + ("enabled" to nat64Enabled)))))
            val effectiveServer = server + ("dns" to dns) + if (objectAt(server, "intercept")["enabled"] == false)
                mapOf("intercepts" to emptyMap<String, Any?>()) else emptyMap()
            val effectiveConfig = if ("server" in decoded) decoded + ("server" to effectiveServer) else effectiveServer
            val planner = RulePlanner(listOf(effectiveConfig))
            problems.addAll(planner.audit().issues.filterNot { boundIssue(it) }
                .map { "${it.hostnamePattern ?: "*"} ${it.pathPattern ?: "*"} ${it.action}: ${it.message}" })
            val providers = objectAt(dns, "providers")
            for ((name, raw) in providers) {
                val provider = PlannerJson.obj(raw)
                val type = (provider["type"] as? String)?.lowercase(java.util.Locale.ROOT)
                if (type != null && type !in setOf("udp", "tcp", "https", "doh", "dns-over-https",
                        "tls", "dot", "dns-over-tls")) problems.add("dns.providers.$name.type is unsupported")
            }
            val ech = objectAt(dns, "ech")
            com.dsha.sidecar.net.dns.native.NativeEchOptions.fromRaw(ech)
            val requiresEch = ech["enabled"] != false && ech["use"] != false &&
                when (val domains = ech["domains"]) {
                    is List<*> -> domains.isNotEmpty()
                    is Map<*, *> -> domains.any { it.value != false && it.value != null }
                    else -> false
                }
            if (problems.isNotEmpty()) throw NativeRuntimeConfigurationException(problems.distinct())
            val catalog = com.dsha.sidecar.net.dns.native.NativeNat64Config.normalizeCatalog(providers, dns)
            return NativeRuntimeConfig(decoded, headerGate, Collections.unmodifiableList(diagnostics), planner,
                catalog, dns, objectAt(server, "preSetIpList").ifEmpty { objectAt(dns, "preSetIpList") },
                requiresEch, port)
        }

        internal fun boundIssue(issue: com.dsha.sidecar.net.rules.native.PlanningIssue): Boolean =
            issue.kind == IssueKind.GLOBAL_SCRIPT_MIDDLEWARE ||
                issue.kind == IssueKind.UNSUPPORTED_ACTION && issue.action in setOf("script", "tampermonkeyScript")

        internal fun objectAt(map: Map<String, Any?>, name: String): Map<String, Any?> {
            val value = map[name] ?: return emptyMap()
            require(value is Map<*, *>) { "$name must be an object" }
            return PlannerJson.obj(value)
        }

        private fun activeUnboundValue(value: Any?): Boolean = when (value) {
            is Map<*, *> -> value["enabled"] != false && value.values.any(::activeUnboundValue)
            is List<*> -> value.isNotEmpty()
            else -> PlannerJson.truthy(value)
        }

        /** options.js handleDnsMapping: only string mappings consume an exact-key family override. */
        private fun normalizeDnsMapping(dns: Map<String, Any?>, problems: MutableList<String>): Map<String, Any?> {
            val families = objectAt(dns, "familyMapping")
            val result = linkedMapOf<String, Any?>()
            for ((host, value) in objectAt(dns, "mapping")) {
                when (value) {
                    null -> Unit
                    is String -> {
                        val family = families[host]
                        if (family is Map<*, *> || family is List<*>) {
                            problems.add("dns.familyMapping.$host must be a scalar")
                        } else {
                            result[host] = mapOf("dnsName" to value, "family" to if (parseIntIsSix(family)) 6 else 4)
                        }
                    }
                    is Map<*, *> -> {
                        val entry = PlannerJson.obj(value)
                        if (entry["dnsName"] !is String) {
                            problems.add("dns.mapping.$host requires a string dnsName")
                        } else {
                            for ((key, item) in entry) {
                                if (key !in setOf("dnsName", "family") && activeUnboundValue(item)) {
                                    problems.add("dns.mapping.$host.$key has no runtime binding")
                                }
                            }
                            if (entry["family"]?.toString() !in setOf(null, "0", "0.0", "4", "4.0", "6", "6.0")) {
                                problems.add("dns.mapping.$host.family is unsupported")
                            }
                            result[host] = entry
                        }
                    }
                    else -> problems.add("dns.mapping.$host must be a provider name or mapping object")
                }
            }
            return result
        }

        /** Compare the parseInt prefix without overflowing on arbitrarily long decimal/hex strings. */
        private fun parseIntIsSix(value: Any?): Boolean {
            val raw = when (value) {
                is String -> value
                is Number -> {
                    val number = value.toDouble()
                    if (number != 0.0 && kotlin.math.abs(number) < 0.000001) number.toString()
                    else java.math.BigDecimal.valueOf(number).toPlainString()
                }
                else -> return false
            }
            val text = DnsProviderJson.trim(raw).removePrefix("+")
            if (text.startsWith("-")) return false
            val hex = text.startsWith("0x", true)
            val digits = if (hex) text.substring(2).takeWhile { it in "0123456789abcdefABCDEF" }
                else text.takeWhile { it in '0'..'9' }
            return digits.trimStart('0') == "6"
        }
    }
}
