package com.dsha.sidecar.net.config.native

import com.dsha.sidecar.net.config.ConfigMerge
import java.io.File
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

data class ConfigDocuments(
    val defaults: String,
    val user: String? = null,
    val personal: String? = null,
    val shared: String? = null,
)

enum class ConfigLayer { USER, PERSONAL, SHARED, DEFAULT }

/** No raw input, path, token or URL is included in provenance/error metadata. */
data class ConfigProvenance(val layer: ConfigLayer, val sha256: String, val bytes: Int, val androidSeed: Boolean)

class DecodedServerSnapshot internal constructor(
    val revision: Long,
    val configuration: Map<String, Any?>,
    val provenance: List<ConfigProvenance>,
    val remoteAddresses: List<RemoteConfigAddress>,
) {
    /** Direct decoded-map input for RulePlanner(listOf(server)) or DNS/HTTP adapters. */
    val server: Map<String, Any?> get() = objectValue(configuration, "server")
    /** NativeRuntimeConfig.decode(configuration) consumes the full root. */
    val dns: Map<String, Any?> get() = objectValue(server, "dns")
    val intercepts: Map<String, Any?> get() = objectValue(server, "intercepts")
    val setting: Map<String, Any?> get() = objectValue(server, "setting")
}

sealed class ConfigUpdate {
    data class Applied(val snapshot: DecodedServerSnapshot) : ConfigUpdate()
    data class Rejected(val previous: DecodedServerSnapshot?, val error: ConfigError, val offset: Int) : ConfigUpdate()
}

/**
 * Synchronous, serialized writers and lock-free readers. All parsing, merging,
 * bootstrap policy and optional runtime validation finish before a single atomic
 * publish. Any rejected input preserves the exact previous snapshot/revision.
 * No Node, eval, transport, logging, disk mutation or background scheduler.
 */
class NativeConfigRepository(
    private val limits: ConfigLimits = ConfigLimits(),
    remoteFetchDefaults: RemoteFetchDefaults? = null,
    private val validate: (DecodedServerSnapshot) -> Unit = {},
) {
    private val current = AtomicReference<DecodedServerSnapshot?>()
    private val parser = BoundedJson5(limits)
    private val fetchDefaults = remoteFetchDefaults?.copy(
        presetIpLists = TreeBudget(limits).objectCopy(remoteFetchDefaults.presetIpLists),
    )
    private val writer = Any()
    private var updating = false

    fun current(): DecodedServerSnapshot? = current.get()

    fun reload(documents: ConfigDocuments): ConfigUpdate = update {
        prepare(documents.bytes(limits))
    }

    /**
     * Optional files: config.json wins over legacy config.json5; remote caches use
     * remote_config_personal.json5 / remote_config.json5. An invalid present layer
     * rejects the whole transaction instead of silently treating it as empty.
     */
    fun reloadFiles(defaults: String, directory: File): ConfigUpdate = update {
        val files = ConfigFileStore(directory, limits)
        prepare(linkedMapOf(
            ConfigLayer.DEFAULT to textBytes(defaults, limits),
            ConfigLayer.USER to files.user(),
            ConfigLayer.PERSONAL to files.read("remote_config_personal.json5"),
            ConfigLayer.SHARED to files.read("remote_config.json5"),
        ))
    }

    /**
     * Explicit future transport seam. Requests are validated HTTPS and bounded;
     * no remote fetch occurs in reload/reloadFiles. Both responses and the final
     * configuration are validated before publishing. No partial cache writes.
     */
    fun reloadWithFetcher(documents: ConfigDocuments, fetcher: RemoteConfigFetcher): ConfigUpdate = update {
        val inputs = documents.bytes(limits)
        val initial = prepare(inputs)
        val remote = objectValue(objectValue(initial.configuration, "app"), "remoteConfig")
        if (remote["enabled"] != true) return@update initial
        for (address in initial.remoteAddresses) {
            val bytes = try {
                fetcher.fetch(address, limits.maxDocumentBytes)
            } catch (_: Exception) {
                reject(ConfigError.FETCH)
            }
            if (bytes.size > limits.maxDocumentBytes) reject(ConfigError.LIMIT)
            inputs[if (address.channel == RemoteChannel.PERSONAL) ConfigLayer.PERSONAL else ConfigLayer.SHARED] =
                bytes.copyOf()
        }
        val final = prepare(inputs)
        // Remote layers cannot silently retarget the two endpoints in this transaction.
        if (final.remoteAddresses.map { it.channel to it.uri } != initial.remoteAddresses.map { it.channel to it.uri }) {
            reject(ConfigError.URL)
        }
        final
    }

    private fun update(build: () -> DecodedServerSnapshot): ConfigUpdate = synchronized(writer) {
        if (updating) return@synchronized ConfigUpdate.Rejected(current.get(), ConfigError.VALIDATION, -1)
        updating = true
        try {
            val candidate = build()
            try {
                validate(candidate)
            } catch (_: Exception) {
                reject(ConfigError.VALIDATION)
            }
            current.set(candidate)
            ConfigUpdate.Applied(candidate)
        } catch (failure: ConfigRejected) {
            ConfigUpdate.Rejected(current.get(), failure.code, failure.offset)
        } catch (_: Exception) {
            // Do not retain or print a third-party exception containing config contents.
            ConfigUpdate.Rejected(current.get(), ConfigError.VALIDATION, -1)
        } finally {
            updating = false
        }
    }

    private fun prepare(inputs: Map<ConfigLayer, ByteArray?>): DecodedServerSnapshot {
        val source = inputs.toMutableMap()
        val seeded = source[ConfigLayer.USER] == null
        if (seeded) source[ConfigLayer.USER] = ANDROID_SEED.toByteArray(Charsets.UTF_8)
        if (source.values.sumOf { it?.size?.toLong() ?: 0 } > limits.maxTotalBytes) reject(ConfigError.LIMIT)
        val inputBudget = TreeBudget(limits)
        val layers = linkedMapOf<ConfigLayer, Map<String, Any?>>()
        val provenance = mutableListOf<ConfigProvenance>()
        for (layer in listOf(ConfigLayer.USER, ConfigLayer.PERSONAL, ConfigLayer.SHARED, ConfigLayer.DEFAULT)) {
            val bytes = source[layer]
            val value = if (bytes == null) emptyMap() else parser.parseObject(bytes)
            layers[layer] = inputBudget.objectCopy(value)
            if (bytes != null) provenance += ConfigProvenance(layer, sha256(bytes), bytes.size, seeded && layer == ConfigLayer.USER)
        }
        val user = layers.getValue(ConfigLayer.USER)
        val personal = layers.getValue(ConfigLayer.PERSONAL)
        val shared = layers.getValue(ConfigLayer.SHARED)
        var merged = user
        // First pass reserves property positions; second pass restores precedence.
        for (layer in listOf(personal, shared, layers.getValue(ConfigLayer.DEFAULT), shared, personal, user)) {
            merged = ConfigMerge.merge(merged, layer)
        }
        val sanitized = ConfigMerge.sanitize(merged, preserveArraySlots = true).toMutableMap()
        // Native Android never launches desktop plugins or changes system proxy settings,
        // even if a remote/default layer tries to enable them.
        replaceObject(sanitized, "proxy") { it["enabled"] = false }
        replaceObject(sanitized, "plugin") { plugins ->
            for (name in plugins.keys.toList()) {
                val plugin = objectValue(plugins, name).toMutableMap()
                plugin["enabled"] = false
                plugins[name] = plugin
            }
        }
        val addresses = RemoteConfigPolicy.normalize(sanitized)
        RemoteConfigPolicy.applyFetchRules(sanitized, addresses, fetchDefaults)
        // Validate structural boundaries consumed directly by the native adapters.
        val server = objectValue(sanitized, "server")
        for (name in listOf("dns", "intercepts", "setting", "preSetIpList")) objectValue(server, name)
        val dns = objectValue(server, "dns")
        for (name in listOf("providers", "mapping", "ech", "nat64")) objectValue(dns, name)
        val immutable = TreeBudget(limits).objectCopy(sanitized)
        return DecodedServerSnapshot(
            (current.get()?.revision ?: 0) + 1, immutable,
            Collections.unmodifiableList(provenance.toList()),
            Collections.unmodifiableList(addresses.toList()),
        )
    }

    private companion object {
        const val ANDROID_SEED = """{"app":{"closeStrategy":2},"proxy":{"enabled":false},"plugin":{"node":{"enabled":false},"git":{"enabled":false},"pip":{"enabled":false},"share":{"enabled":false},"free_eye":{"enabled":false},"overwall":{"enabled":false}},"server":{"enabled":true,"port":31181}}"""
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

private fun textBytes(text: String, limits: ConfigLimits): ByteArray {
    if (text.length > limits.maxDocumentBytes) reject(ConfigError.LIMIT)
    val bytes = try {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: java.nio.charset.CharacterCodingException) {
        reject(ConfigError.UTF8)
    }
    return bytes.also { if (it.size > limits.maxDocumentBytes) reject(ConfigError.LIMIT) }
}

private fun ConfigDocuments.bytes(limits: ConfigLimits): MutableMap<ConfigLayer, ByteArray?> =
    linkedMapOf(ConfigLayer.DEFAULT to textBytes(defaults, limits),
        ConfigLayer.USER to user?.let { textBytes(it, limits) },
        ConfigLayer.PERSONAL to personal?.let { textBytes(it, limits) },
        ConfigLayer.SHARED to shared?.let { textBytes(it, limits) })

/** Defensive, deeply read-only Java collections, not Kotlin read-only views. */
private class TreeBudget(private val limits: ConfigLimits) {
    private var nodes = 0
    private var keys = 0
    private val ancestors = IdentityHashMap<Any, Boolean>()

    fun objectCopy(value: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return copy(value, 0) as Map<String, Any?>
    }

    private fun copy(value: Any?, depth: Int): Any? {
        if (depth > limits.maxDepth || ++nodes > limits.maxNodes) reject(ConfigError.LIMIT)
        return when (value) {
            is Map<*, *> -> {
                if (ancestors.put(value, true) != null) reject(ConfigError.SHAPE)
                val result = linkedMapOf<String, Any?>()
                for ((key, item) in value) {
                    if (++keys > limits.maxKeys) reject(ConfigError.LIMIT)
                    if (key !is String || key.length > limits.maxStringChars) reject(ConfigError.SHAPE)
                    result[key] = copy(item, depth + 1)
                }
                ancestors.remove(value)
                Collections.unmodifiableMap(jsKeyOrder(result))
            }
            is List<*> -> {
                if (ancestors.put(value, true) != null) reject(ConfigError.SHAPE)
                val result = value.map { copy(it, depth + 1) }
                ancestors.remove(value)
                Collections.unmodifiableList(result)
            }
            null, is Boolean -> value
            is String -> value.also { if (it.length > limits.maxStringChars) reject(ConfigError.LIMIT) }
            is Number -> value.toDouble().also { if (!it.isFinite()) reject(ConfigError.UNSUPPORTED) }
            else -> reject(ConfigError.SHAPE)
        }
    }
}
