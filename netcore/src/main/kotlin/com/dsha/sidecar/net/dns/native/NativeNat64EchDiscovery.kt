// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderCatalog
import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.dns.DnsProviderJson
import com.dsha.sidecar.net.dns.DnsProviderSelection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NAT64-domain ECH discovery through the frozen NativeEchResolver.
 *
 * The private catalog projection grants eligibility only. The paired transport
 * restores the exact NAT64 descriptor before any actual query. No ordinary DNS
 * provider is eligible for this route, including shared-name or AliasMode queries.
 */
class NativeNat64EchDiscovery private constructor(
    private val domainSelection: DnsProviderSelection,
    private val descriptor: DnsProviderDescriptor?,
    private val transport: NativeNat64Transport,
    clock: NativeDnsClock,
    options: NativeEchOptions,
    limits: NativeEchLimits,
    private val ownsTransport: Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val resolver: NativeEchResolver?

    init {
        resolver = descriptor?.let { original ->
            // This descriptor is inert outside the paired resolver/transport.
            // It has no invented server, URL, bootstrap, or verification identity.
            val eligible = original.copy(transport = "https")
            val projected = DnsProviderCatalog(listOf(eligible),
                mapOf("Nat64" to original.key, "ForSNI" to original.key))
            val restoringTransport = object : HttpsRecordTransport {
                override fun queryHttps(query: HttpsRecordQuery): CompletableFuture<HttpsRecordAnswer> {
                    if (query.provider != eligible) {
                        return failed(IllegalArgumentException("Unexpected projected NAT64 ECH provider"))
                    }
                    return transport.queryHttps(query.copy(provider = original))
                }
            }
            NativeEchResolver(projected, restoringTransport, clock,
                options.copy(dns = "Nat64"), limits = limits, ownsTransport = false)
        }
    }

    /**
     * Null means inactive/nonmatching/disabled or clean ECH absence, never ECH
     * handshake success. Failure remains failure, with no ordinary-provider retry.
     */
    fun resolve(hostname: String): CompletableFuture<NativeEchResult?> {
        val host = NativeDnsWire.name(hostname.trim())
        if (closed.get()) return failed(NativeDnsClosedException())
        val active = resolver
        if (descriptor == null || active == null || !domainSelection.isNat64Domain(host)) {
            return CompletableFuture.completedFuture(null)
        }
        val source = active.resolve(host)
        val result = object : CompletableFuture<NativeEchResult?>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                val canceled = super.cancel(mayInterruptIfRunning)
                if (canceled) source.cancel(mayInterruptIfRunning)
                return canceled
            }
        }
        source.whenComplete { value, error ->
            if (error != null) result.completeExceptionally(error)
            else if (value == null) result.complete(null)
            else {
                // Undo the private eligibility projection in externally visible metadata.
                result.complete(NativeEchResult(
                    value.hostname, value.queriedName, descriptor.key, descriptor.name, descriptor.transport,
                    value.echConfigList, value.configs, value.selectedConfig, value.ttlMillis,
                    value.expiresAtMillis, value.shared, value.priority, value.target,
                ))
            }
        }
        return result
    }

    /** Includes ECH dependency invalidation and this wrapper's lower transport state. */
    fun invalidate(hostname: String? = null, providerKey: String? = null) {
        resolver?.invalidate(hostname, providerKey)
        val key = descriptor?.key ?: return
        // Never clear ordinary provider data in a borrowed transport.
        if (providerKey == null || providerKey == key) transport.invalidate(hostname, key)
    }

    fun state(): NativeEchResolver.State = resolver?.state()
        ?: NativeEchResolver.State(0, 0, 0, closed.get())

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resolver?.close()
        if (ownsTransport) transport.close()
    }

    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        return resolver?.awaitTermination(timeoutMillis) ?: true
    }

    companion object {
        /**
         * Raw DNS options contain ech/nat64 objects; provider names are the same
         * names passed to the accepted provider config helper. Snapshot once.
         *
         * Construction and inactive queries do no I/O. By default this borrows the
         * NAT64 wrapper, allowing address and ECH resolution to share its bounds.
         */
        fun create(
            providers: Map<String, Any?>,
            dnsOptions: Map<String, Any?>,
            transport: NativeNat64Transport,
            clock: NativeDnsClock,
            limits: NativeEchLimits = NativeEchLimits(),
            ownsTransport: Boolean = false,
        ): NativeNat64EchDiscovery {
            val providerCopy = DnsProviderJson.copyObject(providers)
            val optionCopy = DnsProviderJson.copyObject(dnsOptions)
            val nat64 = DnsProviderJson.objectOrEmpty(optionCopy["nat64"], "nat64")
            val ech = DnsProviderJson.objectOrEmpty(optionCopy["ech"], "ech")
            val options = NativeEchOptions.fromRaw(ech)
            val catalog = NativeNat64Config.normalizeCatalog(providerCopy, optionCopy)
            val key = catalog.providerKey("Nat64")
            val descriptor = catalog.descriptors.singleOrNull { it.key == key && it.transport == "nat64" }
            val selection = DnsProviderSelection(catalog, nat64Domains = nat64["domains"])
            if (descriptor != null) {
                // Unlike the frozen generic helper, an explicitly selected missing
                // NAT64 provider must not silently turn into public/default DNS.
                val configured = DnsProviderJson.text(nat64["dns"], "nat64.dns")
                    ?.takeIf { it.isNotEmpty() }
                if (configured != null) {
                    val provider = DnsProviderJson.objectRequired(providerCopy[configured], "NAT64 DNS $configured")
                    require(provider["ech"] != false) { "Explicit NAT64 DoH provider has ECH disabled" }
                    val selectedCatalog = com.dsha.sidecar.net.dns.DnsProviderConfig.normalize(
                        mapOf(configured to provider))
                    require(selectedCatalog.descriptors.any {
                        it.key == selectedCatalog.providerKey(configured) && it.transport == "https"
                    }) { "Explicit NAT64 DNS provider must be DoH" }
                    val plan = NativeNat64Config.plan(descriptor, NativeNat64Limits())
                    val selectedServer = selectedCatalog.descriptors.single {
                        it.key == selectedCatalog.providerKey(configured)
                    }.server
                    require(plan.routes.any { it.provider.server == selectedServer }) {
                        "Explicit NAT64 DoH provider has no IPv4 bootstrap"
                    }
                } else {
                    NativeNat64Config.plan(descriptor, NativeNat64Limits())
                }
                options.dns?.takeIf { it.isNotEmpty() }?.let { dedicated ->
                    require(catalog.providerKey(dedicated) == key || dedicated == configured) {
                        "NAT64 ECH DNS must be Nat64 or match the explicit nat64.dns service"
                    }
                }
            }
            return NativeNat64EchDiscovery(selection, descriptor, transport, clock,
                options, limits, ownsTransport)
        }

        private fun <T> failed(error: Throwable) = CompletableFuture<T>().apply { completeExceptionally(error) }
    }
}
