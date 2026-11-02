// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.EchConfig
import com.dsha.sidecar.net.dns.DnsProviderJson
import java.io.IOException

data class NativeEchOptions(
    val enabled: Boolean = true,
    val use: Boolean = true,
    val dns: String? = null,
    val tryAllProviders: Boolean = true,
    val publicName: String? = "cloudflare-ech.com",
    val emptyTtlMillis: Long = 600_000,
    val minTtlMillis: Long = 60_000,
    val maxTtlMillis: Long = 3_600_000,
    val parallelDelayMillis: Long = 200,
) {
    init {
        require(emptyTtlMillis in 0..86_400_000 && minTtlMillis in 0..86_400_000)
        require(maxTtlMillis in 1..86_400_000 && minTtlMillis <= maxTtlMillis)
        require(parallelDelayMillis in 0..60_000)
        publicName?.takeIf { it.isNotEmpty() }?.let(NativeDnsWire::name)
    }

    companion object {
        /** Decoded raw ech object. No provider is created by these defaults. */
        fun fromRaw(raw: Map<String, Any?>): NativeEchOptions {
            val input = DnsProviderJson.copyObject(raw)
            fun text(key: String, fallback: String?): String? {
                val value = input[key] ?: return fallback
                require(value is String) { "ech.$key must be text" }
                return value
            }
            fun number(key: String, fallback: Long): Long {
                val rawValue = input[key] ?: return fallback
                val value = rawValue.toString().toDoubleOrNull()
                require(value != null && value.isFinite() && value % 1.0 == 0.0 &&
                    value in 0.0..86_400_000.0) { "Invalid ech.$key" }
                return value.toLong().takeIf { it > 0 || key == "parallelDelay" } ?: fallback
            }
            return NativeEchOptions(
                input["enabled"] != false, input["use"] != false, text("dns", null),
                input["tryAllProviders"] != false, text("publicName", "cloudflare-ech.com"),
                number("emptyTtl", 600_000), number("minTtl", 60_000), number("maxTtl", 3_600_000),
                number("parallelDelay", 200),
            )
        }
    }
}

data class NativeEchLimits(
    val maxEntries: Int = 512,
    val maxFlights: Int = 16,
    val maxWaiters: Int = 64,
    val maxProviders: Int = 16,
    val maxAliasDepth: Int = 3,
    val maxRecords: Int = 32,
    val maxConfigBytes: Int = 65535,
    val lookupTimeoutMillis: Long = 8_000,
) {
    init {
        require(maxEntries in 1..4096 && maxFlights in 1..128 && maxWaiters in 1..256)
        require(maxProviders in 1..64 && maxAliasDepth in 0..8 && maxRecords in 1..128)
        require(maxConfigBytes in 2..65535 && lookupTimeoutMillis in 1..120_000)
    }
}

/** A metadata result suitable for TypedEchConfigList.parse(echConfigList), not proof of ECH acceptance. */
class NativeEchResult internal constructor(
    val hostname: String,
    val queriedName: String,
    val providerKey: String,
    val providerName: String,
    val providerTransport: String,
    wire: ByteArray,
    configs: List<EchConfig>,
    val selectedConfig: EchConfig,
    val ttlMillis: Long,
    val expiresAtMillis: Long,
    val shared: Boolean,
    val priority: Int,
    val target: String,
) {
    private val bytes = wire.copyOf()
    private val parsed = configs.toList()
    val echConfigList: ByteArray get() = bytes.copyOf()
    val configs: List<EchConfig> get() = parsed.toList()
    val publicName: String get() = requireNotNull(selectedConfig.contents).publicName
}

class NativeEchConfigException(message: String) : IOException(message)
class NativeEchLookupException(val failures: List<Throwable>) : IOException(
    "ECH lookup failed (${failures.size} provider errors)", failures.firstOrNull(),
)
