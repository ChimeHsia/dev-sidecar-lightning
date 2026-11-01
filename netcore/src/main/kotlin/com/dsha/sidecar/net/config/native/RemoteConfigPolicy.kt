package com.dsha.sidecar.net.config.native

import java.net.URI
import java.util.Locale

enum class RemoteChannel { SHARED, PERSONAL }

data class RemoteConfigAddress(val channel: RemoteChannel, val uri: URI, val upgradedFromHttp: Boolean)

/**
 * Caller-supplied public bootstrap policy. No real service URL, IP, user URL or
 * token is embedded by this module. This describes future HTTPS transport only.
 */
data class RemoteFetchDefaults(
    val dohUrl: String,
    val providerName: String,
    val providerHostMatch: String,
    val presetIpLists: Map<String, Any?> = emptyMap(),
)

/** No built-in HTTP client, scheduler or Node fallback. Responses are rechecked by the repository. */
fun interface RemoteConfigFetcher {
    fun fetch(address: RemoteConfigAddress, maximumBytes: Int): ByteArray
}

internal fun objectValue(parent: Map<String, Any?>, key: String): Map<String, Any?> {
    val value = parent[key] ?: return emptyMap()
    if (value !is Map<*, *> || value.keys.any { it !is String }) reject(ConfigError.SHAPE)
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
}

internal fun replaceObject(parent: MutableMap<String, Any?>, key: String, block: (MutableMap<String, Any?>) -> Unit) {
    val copy = objectValue(parent, key).toMutableMap()
    parent[key] = copy
    block(copy)
}

object RemoteConfigPolicy {
    /**
     * Matches upstream's one-time leading-whitespace/case-insensitive http rewrite.
     * Strict native boundary then requires HTTPS, a host, no credentials/fragment,
     * ASCII wire form and a valid port. It never fetches or writes user files.
     */
    fun address(channel: RemoteChannel, value: Any?): RemoteConfigAddress? {
        if (value == null || value == "") return null
        if (value !is String || value.length > 8192) reject(ConfigError.URL)
        val leadingTrimmed = value.dropWhile(::whitespace)
        val upgrade = leadingTrimmed.startsWith("http://", ignoreCase = true)
        val normalized = if (upgrade) "https://" + value.trim(::whitespace).substring(7) else value
        if (normalized.any { it.code !in 33..126 || it == '\\' }) reject(ConfigError.URL)
        val uri = try { URI(normalized) } catch (_: Exception) { reject(ConfigError.URL) }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.isOpaque ||
            uri.port != -1 && uri.port !in 1..65535 || uri.rawAuthority.endsWith(':')
        ) reject(ConfigError.URL)
        // URI accepts malformed percent sequences neither here nor during a future redirect.
        return RemoteConfigAddress(channel, uri, upgrade)
    }

    internal fun normalize(config: MutableMap<String, Any?>): List<RemoteConfigAddress> {
        val app = objectValue(config, "app").toMutableMap()
        val remote = objectValue(app, "remoteConfig").toMutableMap()
        if (remote.isEmpty()) return emptyList()
        val addresses = mutableListOf<RemoteConfigAddress>()
        for ((key, channel) in listOf("url" to RemoteChannel.SHARED, "personalUrl" to RemoteChannel.PERSONAL)) {
            val current = address(channel, remote[key])
            if (current != null) {
                // Existing HTTPS spelling remains unchanged; only the upstream HTTP rule rewrites.
                remote[key] = current.uri.toString()
                addresses += current
            }
        }
        app["remoteConfig"] = remote
        config["app"] = app
        return addresses
    }

    /**
     * Data counterpart of remote-config-rule.js plus its server-module application.
     * DNS mappings overwrite existing entries (the actual source behavior, despite
     * its "fill missing" comment); intercepts and preset IPs only fill missing hosts.
     */
    internal fun applyFetchRules(
        config: MutableMap<String, Any?>,
        addresses: List<RemoteConfigAddress>,
        defaults: RemoteFetchDefaults?,
    ) {
        val remote = objectValue(objectValue(config, "app"), "remoteConfig")
        if (remote["enabled"] != true) return
        val policy = defaults ?: reject(ConfigError.VALIDATION)
        if (policy.providerName.isBlank() || policy.providerHostMatch.isBlank()) reject(ConfigError.VALIDATION)
        val bootstrapAddress = address(RemoteChannel.SHARED, policy.dohUrl) ?: reject(ConfigError.URL)
        if (bootstrapAddress.upgradedFromHttp) reject(ConfigError.URL)
        val override = objectValue(remote, "fetchRule")
        val sni = override["sni"]
        if (sni != null && (sni !is String || sni.isBlank() || sni.length > 253 ||
                    sni.any { it.code !in 33..126 })) reject(ConfigError.SHAPE)
        val selectedDns = override["dns"]
        if (selectedDns != null && (selectedDns !is String || selectedDns.isBlank())) reject(ConfigError.SHAPE)
        val presets = if ("preSetIpList" in override) objectValue(override, "preSetIpList") else policy.presetIpLists
        replaceObject(config, "server") { server ->
            replaceObject(server, "dns") { dns ->
                val providers = objectValue(dns, "providers").toMutableMap()
                val chosen = if (selectedDns is String) selectedDns else {
                    providers.entries.firstOrNull {
                        val provider = it.value as? Map<*, *>
                        (provider?.get("server") as? String)?.contains(policy.providerHostMatch) == true
                    }?.key ?: policy.providerName
                }
                if (providers[chosen] == null) providers[chosen] = mapOf("server" to policy.dohUrl)
                dns["providers"] = providers
                val mapping = objectValue(dns, "mapping").toMutableMap()
                val intercepts = objectValue(server, "intercepts").toMutableMap()
                val presetResult = objectValue(server, "preSetIpList").toMutableMap()
                for (item in addresses) {
                    val hostname = item.uri.host.lowercase(Locale.ROOT)
                    if (sni != null && intercepts[hostname] == null) {
                        intercepts[hostname] = mapOf(".*" to mapOf(
                            "sni" to sni,
                            "remark" to "\u5185\u7f6e\uff1a\u8fdc\u7a0b\u914d\u7f6e\u62c9\u53d6\uff08\u9ed8\u8ba4\u5bf9\u6297\u7b56\u7565\uff09",
                        ))
                    }
                    mapping[hostname] = chosen
                    val preset = objectValue(presets, hostname)
                    if (preset.isNotEmpty() && presetResult[hostname] == null) presetResult[hostname] = preset
                }
                dns["mapping"] = mapping
                server["intercepts"] = intercepts
                server["preSetIpList"] = presetResult
            }
        }
    }
}
