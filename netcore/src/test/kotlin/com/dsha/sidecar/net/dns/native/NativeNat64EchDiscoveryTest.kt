// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.Nat64Address
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeNat64EchDiscoveryTest {
    private class Clock(var now: Long = 100) : NativeDnsClock { override fun nowMillis() = now }
    private class Dialer : NativeNat64DohDialer {
        data class Call(val query: NativeNat64DohQuery, val answer: CompletableFuture<NativeNat64DohAnswer>)
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        var ignoreCancel = false
        var closed = 0
        override fun queryNat64Doh(query: NativeNat64DohQuery): CompletableFuture<NativeNat64DohAnswer> {
            val future = if (ignoreCancel) object : CompletableFuture<NativeNat64DohAnswer>() {
                override fun cancel(mayInterruptIfRunning: Boolean) = false
            } else CompletableFuture()
            calls.add(Call(query, future))
            return future
        }
        fun answer(index: Int, vararg records: HttpsServiceRecord) = calls[index].answer.complete(
            NativeNat64DohAnswer.Services(HttpsRecordAnswer(records.toList())))
        override fun close() { closed++ }
    }
    private class Harness : AutoCloseable {
        val clock = Clock()
        val dialer = Dialer()
        var ordinaryCalls = 0
        val socket = SocketDnsTransport(NativeDnsEndpointResolver {
            ordinaryCalls++
            error("An ordinary provider must never be dialed on this route")
        })
        val transport = NativeNat64Transport(socket, clock, dialer,
            NativeNat64Limits(parallelDelayMillis = 0))
        fun discovery(
            nat64: Map<String, Any?> = emptyMap(),
            ech: Map<String, Any?> = emptyMap(),
            providers: Map<String, Any?> = mapOf("local" to mapOf(
                "server" to "https://resolver.test/resolve", "sni" to "cover.test")),
        ): NativeNat64EchDiscovery = NativeNat64EchDiscovery.create(providers,
            options(nat64, ech), transport, clock)
        override fun close() {
            transport.close()
            assertTrue(transport.awaitTermination(5_000))
            assertTrue(socket.awaitTermination(5_000))
            assertEquals(0, socket.state().resources)
        }
    }
    companion object {
        fun options(
            nat64: Map<String, Any?> = emptyMap(),
            ech: Map<String, Any?> = emptyMap(),
        ): Map<String, Any?> = mapOf(
            "nat64" to (mapOf(
                "enabled" to true, "prefix" to "64:ff9b::/96", "domains" to listOf("*.example.test"),
                "dns" to "local", "bootstrap" to mapOf(
                    "resolver.test" to listOf("192.0.2.53"),
                    "dns.alidns.com" to emptyList<String>(), "cloudflare-dns.com" to emptyList<String>(),
                    "dns.google" to emptyList<String>()),
            ) + nat64),
            "ech" to (mapOf("publicName" to "", "minTtl" to 1) + ech),
        )
    }
    private fun <T> CompletableFuture<T>.await() = get(5, TimeUnit.SECONDS)
    private inline fun <reified T : Throwable> failure(future: CompletableFuture<*>) {
        try { future.await(); fail("Expected ${T::class.java.simpleName}") }
        catch (error: ExecutionException) { assertTrue("Unexpected ${error.cause}", error.cause is T) }
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Invalid explicit route accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun matchingDomainUsesRealFrozenResolverAndRestoresNat64ProviderIdentity() {
        Harness().use { h ->
            h.discovery().use { discovery ->
                val pending = discovery.resolve("WWW.EXAMPLE.TEST.")
                val query = h.dialer.calls.single().query
                assertEquals(NativeNat64Question.HTTPS, query.type)
                assertEquals("www.example.test", query.hostname)
                assertEquals("https://resolver.test/resolve", query.provider.server)
                assertEquals("cover.test", query.provider.sni)
                assertEquals(Nat64Address.toNat64Address("64:ff9b::/96", "192.0.2.53"), query.ipv6Destination)
                h.dialer.answer(0, NativeEchTestData.record(owner = "www.example.test", ttl = 3_000))
                val result = pending.await()!!
                assertEquals("nat64", result.providerTransport)
                assertEquals("builtin:Nat64", result.providerKey)
                assertEquals("NAT64", result.providerName)
                assertEquals("public.example", result.publicName)
                assertEquals(3_000L, result.ttlMillis)
                assertArrayEquals(NativeEchTestData.config(), result.echConfigList)
                assertEquals(0, h.ordinaryCalls)
                assertEquals(1, discovery.state().entries)
            }
        }
    }

    @Test fun emptyPrefixAndMissingDomainsAreInactiveEvenWithOrdinaryProvidersAvailable() {
        for (settings in listOf(mapOf("prefix" to ""), mapOf("domains" to emptyList<String>()),
            mapOf("enabled" to false))) {
            Harness().use { h ->
                h.discovery(nat64 = settings).use { discovery ->
                    assertNull(discovery.resolve("www.example.test").await())
                    assertEquals(0, discovery.state().entries)
                    assertTrue(h.dialer.calls.isEmpty())
                    assertEquals(0, h.ordinaryCalls)
                }
            }
        }
    }

    @Test fun unrelatedDomainReturnsNullWithoutQueryingNat64OrOrdinaryProviders() {
        Harness().use { h ->
            h.discovery().use { discovery ->
                assertNull(discovery.resolve("unrelated.test").await())
                assertTrue(h.dialer.calls.isEmpty())
                assertEquals(0, h.ordinaryCalls)
            }
        }
    }

    @Test fun globalEnabledUseAndNat64EchDisabledNeverLaunchNetwork() {
        for ((nat64, ech) in listOf(
            mapOf<String, Any?>("ech" to false) to emptyMap(),
            emptyMap<String, Any?>() to mapOf("enabled" to false),
            emptyMap<String, Any?>() to mapOf("use" to false),
        )) Harness().use { h ->
            h.discovery(nat64, ech).use {
                assertNull(it.resolve("www.example.test").await())
                assertTrue(h.dialer.calls.isEmpty())
                assertEquals(0, h.ordinaryCalls)
            }
        }
    }

    @Test fun disabledExplicitDohProviderFailsClosedWithoutUsingPublicFallbacks() {
        Harness().use { h ->
            invalid { h.discovery(providers = mapOf("local" to mapOf(
                "server" to "https://resolver.test/resolve", "ech" to false))) }
            assertTrue(h.dialer.calls.isEmpty())
            assertEquals(0, h.ordinaryCalls)
        }
    }

    @Test fun explicitMissingNat64ProviderUnknownEchProviderAndDifferentEchOverrideFailClosed() {
        Harness().use { h ->
            invalid { h.discovery(nat64 = mapOf("dns" to "absent")) }
            invalid { h.discovery(ech = mapOf("dns" to "absent")) }
            invalid { h.discovery(ech = mapOf("dns" to "other"),
                providers = mapOf("local" to mapOf("server" to "https://resolver.test/resolve"),
                    "other" to mapOf("server" to "https://other.test/resolve"))) }
            assertTrue(h.dialer.calls.isEmpty())
            assertEquals(0, h.ordinaryCalls)
        }
    }

    @Test fun explicitNonDohProviderAndMissingBootstrapAreNotSilentlyIgnored() {
        Harness().use { h ->
            invalid { h.discovery(providers = mapOf("local" to mapOf("server" to "tls://resolver.test"))) }
            invalid { h.discovery(nat64 = mapOf("bootstrap" to mapOf(
                "resolver.test" to emptyList<String>()))) }
            assertTrue(h.dialer.calls.isEmpty())
        }
    }

    @Test fun invalidNonemptyPrefixFailsBeforeAnyTransportAdmission() {
        Harness().use { h ->
            for (prefix in listOf("::/96", "64:ff9b::/64", "64:ff9b::1", " 64:ff9b::", "[64:ff9b::]")) {
                invalid { h.discovery(nat64 = mapOf("prefix" to prefix)) }
            }
            assertEquals(0, h.transport.state().plans)
            assertTrue(h.dialer.calls.isEmpty())
        }
    }

    @Test fun dedicatedNat64BindingWorksAndNoProvidersAreInventedWhenInactive() {
        Harness().use { h ->
            h.discovery(ech = mapOf("dns" to "Nat64")).use { discovery ->
                val pending = discovery.resolve("example.test")
                h.dialer.answer(0, NativeEchTestData.record())
                assertEquals("nat64", pending.await()!!.providerTransport)
            }
            h.discovery(nat64 = mapOf("prefix" to ""), ech = mapOf("dns" to "unknown"), providers = emptyMap()).use {
                assertNull(it.resolve("example.test").await())
                assertEquals(1, h.dialer.calls.size)
            }
        }
    }

    @Test fun fullMultiConfigPublicNameTtlAndDefensiveCopiesArePreserved() {
        Harness().use { h ->
            h.discovery().use { discovery ->
                val pending = discovery.resolve("example.test")
                val full = NativeEchTestData.config("prefer-x25519-after-p256")
                h.dialer.answer(0, NativeEchTestData.record(full, ttl = 7_000))
                val result = pending.await()!!
                assertEquals(2, result.configs.size)
                assertEquals("public.example", result.publicName)
                assertEquals(10, result.selectedConfig.contents!!.configId)
                assertArrayEquals(full, result.echConfigList)
                result.echConfigList.fill(0)
                assertArrayEquals(full, discovery.resolve("example.test").await()!!.echConfigList)
                assertEquals(7_100L, result.expiresAtMillis)
            }
        }
    }

    @Test fun aliasAndSharedNamesOutsideMatchedDomainsStillUseOnlyNat64() {
        Harness().use { h ->
            h.discovery(ech = mapOf("publicName" to "shared.test")).use { discovery ->
                val pending = discovery.resolve("example.test")
                h.dialer.answer(0, NativeEchTestData.record(null, 0, "alias.test", 2_000))
                assertEquals("alias.test", h.dialer.calls[1].query.hostname)
                h.dialer.answer(1)
                assertEquals("shared.test", h.dialer.calls[2].query.hostname)
                h.dialer.answer(2, NativeEchTestData.record(owner = "shared.test"))
                val result = pending.await()!!
                assertTrue(result.shared)
                assertEquals("public.example", result.publicName)
                assertEquals("shared.test", result.queriedName)
                assertTrue(h.dialer.calls.all { it.query.ipv6Destination.contains(":") })
                assertEquals(0, h.ordinaryCalls)
            }
        }
    }

    @Test fun mappedDomainFailureIsNotRetriedViaSharedNameOrOrdinaryIpv4() {
        Harness().use { h ->
            h.discovery(ech = mapOf("publicName" to "shared.test")).use { discovery ->
                val pending = discovery.resolve("example.test")
                h.dialer.calls.single().answer.completeExceptionally(IOException("NAT64 unavailable"))
                failure<NativeEchLookupException>(pending)
                assertEquals(1, h.dialer.calls.size)
                assertEquals(0, h.ordinaryCalls)
                assertEquals(0, discovery.state().entries)
            }
        }
    }

    @Test fun waitersShareResolverFlightAndCancelOnlyTheirOwnWaiter() {
        Harness().use { h ->
            h.discovery().use { discovery ->
                val first = discovery.resolve("example.test")
                val second = discovery.resolve("example.test")
                first.cancel(true)
                assertEquals(1, discovery.state().waiters)
                assertFalse(h.dialer.calls.single().answer.isCancelled)
                h.dialer.answer(0, NativeEchTestData.record())
                assertNotNull(second.await())
            }
        }
    }

    @Test fun lastWaiterCancellationClosesNat64FlightAndDoesNotRetainResult() {
        Harness().use { h ->
            h.discovery().use { discovery ->
                val pending = discovery.resolve("example.test")
                assertTrue(pending.cancel(true))
                assertTrue(h.dialer.calls.single().answer.isCancelled)
                assertEquals(0, discovery.state().flights)
                assertEquals(0, h.transport.state().flights)
                assertEquals(0, discovery.state().entries)
            }
        }
    }

    @Test fun dependencyInvalidationAndTtlBoundaryRequeryWithoutChangingTransportEpoch() {
        Harness().use { h ->
            h.discovery().use { discovery ->
                val pending = discovery.resolve("example.test")
                h.dialer.answer(0, NativeEchTestData.record(null, 0, "alias.test", 2_000))
                h.dialer.answer(1, NativeEchTestData.record(owner = "alias.test"))
                assertEquals(2_000L, pending.await()!!.ttlMillis)
                h.clock.now += 1_999
                assertNotNull(discovery.resolve("example.test").await())
                assertEquals(2, h.dialer.calls.size)
                h.clock.now++
                val refreshed = discovery.resolve("example.test")
                h.dialer.answer(2, NativeEchTestData.record(ttl = 20_000))
                refreshed.await()
                discovery.invalidate("example.test")
                assertEquals(0, discovery.state().entries)
                discovery.resolve("example.test")
                assertEquals(4, h.dialer.calls.size)
            }
        }
    }

    @Test fun lateCanceledResponsesCannotReviveInvalidatedResolverFlight() {
        Harness().use { h ->
            h.dialer.ignoreCancel = true
            h.discovery().use { discovery ->
                val old = discovery.resolve("example.test")
                discovery.invalidate("example.test")
                failure<NativeDnsInvalidatedException>(old)
                val fresh = discovery.resolve("example.test")
                h.dialer.answer(0, NativeEchTestData.record())
                assertFalse(fresh.isDone)
                h.dialer.answer(1, NativeEchTestData.record())
                assertNotNull(fresh.await())
            }
        }
    }

    @Test fun closeIsIdempotentAndBorrowedTransportRemainsUsableForAddressQueries() {
        Harness().use { h ->
            val discovery = h.discovery()
            val pending = discovery.resolve("example.test")
            discovery.close(); discovery.close()
            failure<NativeDnsClosedException>(pending)
            failure<NativeDnsClosedException>(discovery.resolve("example.test"))
            assertFalse(h.transport.state().closed)
            assertEquals(0, h.dialer.closed)
            assertTrue(discovery.awaitTermination(5_000))
        }
    }

    @Test fun callerInputSnapshotsAndMatchedWildcardApexFollowAcceptedMatcher() {
        Harness().use { h ->
            val domains = mutableListOf("*.example.test")
            val raw = options(nat64 = mapOf("domains" to domains))
            NativeNat64EchDiscovery.create(mapOf("local" to mapOf("server" to "https://resolver.test/resolve")),
                raw, h.transport, h.clock).use { discovery ->
                domains.clear()
                val pending = discovery.resolve("example.test")
                h.dialer.answer(0, NativeEchTestData.record())
                assertNotNull(pending.await())
            }
        }
    }

    @Test fun sourceDohOrderRecentWinnerAndCancelSemanticsStayInFrozenCoordinator() {
        Harness().use { h ->
            // Empty explicit DNS selects only the existing source default routes.
            h.discovery(nat64 = mapOf("dns" to "", "bootstrap" to emptyMap<String, Any?>())).use { discovery ->
                val pending = discovery.resolve("example.test")
                assertEquals(listOf("https://dns.alidns.com/resolve", "https://cloudflare-dns.com/dns-query",
                    "https://dns.google/resolve"), h.dialer.calls.map { it.query.provider.server })
                h.dialer.answer(1, NativeEchTestData.record())
                assertEquals("nat64", pending.await()!!.providerTransport)
                assertTrue(h.dialer.calls[0].answer.isCancelled)
                assertTrue(h.dialer.calls[2].answer.isCancelled)
                val next = discovery.resolve("next.example.test")
                assertEquals("https://cloudflare-dns.com/dns-query", h.dialer.calls[3].query.provider.server)
                h.dialer.answer(3, NativeEchTestData.record(owner = "next.example.test"))
                assertNotNull(next.await())
            }
        }
    }

    @Test fun sameNamedEchAndNat64DohServiceRetainsDedicatedSettingsWithoutOrdinaryBypass() {
        Harness().use { h ->
            h.discovery(ech = mapOf("dns" to "local")).use { discovery ->
                val pending = discovery.resolve("example.test")
                assertEquals("https://resolver.test/resolve", h.dialer.calls.single().query.provider.server)
                assertEquals("cover.test", h.dialer.calls.single().query.provider.sni)
                h.dialer.answer(0, NativeEchTestData.record())
                assertEquals("nat64", pending.await()!!.providerTransport)
                assertEquals(0, h.ordinaryCalls)
            }
        }
    }

    @Test fun owningAdapterClosesBorrowedWrapperExactlyOnceOnlyWhenRequested() {
        Harness().use { h ->
            val adapter = NativeNat64EchDiscovery.create(
                mapOf("local" to mapOf("server" to "https://resolver.test/resolve")),
                options(), h.transport, h.clock, ownsTransport = true)
            val pending = adapter.resolve("example.test")
            adapter.close(); adapter.close()
            failure<NativeDnsClosedException>(pending)
            assertTrue(h.transport.state().closed)
            assertEquals(1, h.dialer.closed)
            assertTrue(adapter.awaitTermination(5_000))
        }
    }
}
