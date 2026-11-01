// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderConfig
import com.dsha.sidecar.net.dns.DnsProviderDescriptor
import com.dsha.sidecar.net.dns.DnsProviderSelection
import com.dsha.sidecar.net.dns.Nat64Address
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeNat64TransportTest {
    private class Clock(var now: Long = 100) : NativeDnsClock { override fun nowMillis() = now }
    private class Dialer : NativeNat64DohDialer {
        data class Call(val query: NativeNat64DohQuery, val future: CompletableFuture<NativeNat64DohAnswer>)
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        var ignoreCancellation = false
        var closed = 0
        override fun queryNat64Doh(query: NativeNat64DohQuery): CompletableFuture<NativeNat64DohAnswer> {
            val future = if (ignoreCancellation) object : CompletableFuture<NativeNat64DohAnswer>() {
                override fun cancel(mayInterruptIfRunning: Boolean) = false
            } else CompletableFuture()
            calls.add(Call(query, future))
            return future
        }
        fun answer(index: Int, ips: List<String> = listOf("192.0.2.7", "192.0.2.8"), ttl: Long = 120_000) =
            calls[index].future.complete(NativeNat64DohAnswer.Addresses(
                NativeDnsAnswer(ips.map { NativeDnsRecord(it, ttl) })))
        override fun close() { closed++ }
    }
    private val prefix = "64:ff9b::/96"
    private fun provider(
        key: String = "builtin:Nat64",
        options: Map<String, Any?> = emptyMap(),
    ) = DnsProviderDescriptor(key, "NAT64", "nat64",
        nat64Options = mapOf("enabled" to true, "prefix" to prefix) + options)
    private fun query(provider: DnsProviderDescriptor = provider(), host: String = "example.test") =
        NativeDnsQuery(provider, host, NativeDnsFamily.IPV6)
    private fun <T> CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)
    private inline fun <reified T : Throwable> failure(future: CompletableFuture<*>): Throwable {
        try { future.await(); fail("Expected ${T::class.java.simpleName}") }
        catch (error: ExecutionException) {
            assertTrue("Unexpected ${error.cause}", error.cause is T)
            return error.cause!!
        }
        error("No failure")
    }
    private fun transport(dialer: Dialer, clock: Clock = Clock(), limits: NativeNat64Limits =
        NativeNat64Limits(parallelDelayMillis = 0)) =
        NativeNat64Transport(SocketDnsTransport(NativeDnsEndpointResolver { error("No ordinary bootstrap expected") }),
            clock, dialer, limits)

    @Test fun defaultEnabledEmptyPrefixIsRemovedBeforeProviderSelection() {
        val catalog = NativeNat64Config.normalizeCatalog(mapOf("a" to mapOf("server" to "192.0.2.53")),
            mapOf("nat64" to mapOf("enabled" to true, "prefix" to "", "domains" to listOf("example.test"))))
        assertNull(catalog.providerKey("Nat64"))
        val selection = DnsProviderSelection(catalog, mapOf("example.test" to mapOf("dnsName" to "a")),
            nat64Domains = listOf("example.test"))
        assertFalse(selection.isNat64Domain("example.test"))
        assertEquals("provider:a", selection.select("example.test")!!.providerKey)
    }

    @Test fun declaredDomainAndValidPrefixAreRequiredForActiveCatalogEntry() {
        val active = NativeNat64Config.normalizeCatalog(emptyMap(), mapOf("nat64" to mapOf(
            "enabled" to true, "prefix" to prefix, "domains" to listOf("example.test"))))
        assertEquals("builtin:Nat64", active.providerKey("Nat64"))
        val noDomains = NativeNat64Config.normalizeCatalog(emptyMap(), mapOf("nat64" to mapOf(
            "enabled" to true, "prefix" to prefix)))
        assertNull(noDomains.providerKey("Nat64"))
    }

    @Test fun disabledDescriptorAndInvalidPrefixesNeverLaunchDohOrInventGateway() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            failure<NativeNat64DisabledException>(transport.query(query(provider(options = mapOf("prefix" to "")))))
            failure<NativeNat64DisabledException>(transport.query(query(provider(options = mapOf("enabled" to false)))))
            for (invalid in listOf("::/96", "64:ff9b::/64", "64:ff9b::1", "[64:ff9b::]", " 64:ff9b::")) {
                failure<IllegalArgumentException>(transport.query(query(provider(options = mapOf("prefix" to invalid)))))
            }
            assertTrue(dialer.calls.isEmpty())
            assertEquals(0, transport.state().plans)
        }
    }

    @Test fun defaultsUseOnlyActualSourceDohBootstrapAndSynthesizeBothEndpointsAndAnswers() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            val pending = transport.query(query())
            assertEquals(listOf("https://dns.alidns.com/resolve", "https://cloudflare-dns.com/dns-query",
                "https://dns.google/resolve"), dialer.calls.map { it.query.provider.server })
            assertEquals(listOf("223.5.5.5", "1.1.1.1", "8.8.8.8").map {
                Nat64Address.toNat64Address(prefix, it) }, dialer.calls.map { it.query.ipv6Destination })
            assertTrue(dialer.calls.all { it.query.type == NativeNat64Question.A })
            dialer.answer(0)
            assertEquals(listOf("192.0.2.7", "192.0.2.8").map {
                Nat64Address.toNat64Address(prefix, it) }, pending.await().records.map { it.address })
            assertTrue(dialer.calls.drop(1).all { it.future.isCancelled })
        }
    }

    @Test fun namedDohPriorityAndSniRemainServiceIdentityRatherThanDialDestination() {
        val dialer = Dialer()
        val options = mapOf("dns" to "private", "providers" to mapOf(
            "private" to mapOf("server" to "https://resolver.test:8443/resolve", "sni" to "cover.test")),
            "bootstrap" to mapOf("resolver.test" to listOf("192.0.2.53", "192.0.2.54")))
        transport(dialer).use { transport ->
            val pending = transport.query(query(provider(options = options)))
            val first = dialer.calls.first().query
            assertEquals("https://resolver.test:8443/resolve", first.provider.server)
            assertEquals("cover.test", first.provider.sni)
            assertEquals(Nat64Address.toNat64Address(prefix, "192.0.2.53"), first.ipv6Destination)
            dialer.answer(0); pending.await()
        }
    }

    @Test fun literalDohAndConfiguredBootstrapOverridesAreUsedWithoutOsLookup() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            val pending = transport.query(query(provider(options = mapOf(
                "doh" to "https://192.0.2.53/dns-query",
                "bootstrap" to mapOf("dns.google" to listOf("192.0.2.9")),
            ))))
            assertEquals(Nat64Address.toNat64Address(prefix, "192.0.2.53"), dialer.calls[0].query.ipv6Destination)
            assertEquals(Nat64Address.toNat64Address(prefix, "192.0.2.9"), dialer.calls.last().query.ipv6Destination)
            dialer.answer(0); pending.await()
        }
    }

    @Test fun missingBootstrapSkipsThatEndpointAndNoEligibleEndpointFailsExplicitly() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            val pending = transport.query(query(provider(options = mapOf("doh" to "https://no-pins.test/resolve"))))
            assertEquals(3, dialer.calls.size)
            assertTrue(dialer.calls.none { it.query.provider.server!!.contains("no-pins") })
            dialer.answer(0); pending.await()
            failure<IllegalArgumentException>(transport.query(query(provider("no-routes", mapOf("bootstrap" to mapOf(
                "dns.alidns.com" to emptyList<String>(), "cloudflare-dns.com" to emptyList<String>(),
                "dns.google" to emptyList<String>()))))))
        }
    }

    @Test fun ipv4OnlyAndInvalidBootstrapOrPlaintextUrlsAreExplicitFailures() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            failure<UnsupportedOperationException>(transport.query(query().copy(family = NativeDnsFamily.IPV4)))
            failure<IllegalArgumentException>(transport.query(query(provider(options = mapOf(
                "bootstrap" to mapOf("resolver.test" to listOf("::1")))))))
            failure<IllegalArgumentException>(transport.query(query(provider(options = mapOf(
                "doh" to "http://resolver.test/resolve")))))
            assertTrue(dialer.calls.isEmpty())
        }
    }

    @Test fun cachedAnswersAgeTtlsAndExpireAtExactBoundary() {
        val dialer = Dialer(); val clock = Clock()
        transport(dialer, clock).use { transport ->
            val pending = transport.query(query())
            dialer.answer(0, ttl = 100)
            assertEquals(100L, pending.await().records[0].ttlMillis)
            clock.now += 40
            assertEquals(60L, transport.query(query()).await().records[0].ttlMillis)
            assertEquals(3, dialer.calls.size)
            clock.now += 60
            transport.query(query())
            assertEquals(6, dialer.calls.size)
        }
    }

    @Test fun positiveCacheCapZeroTtlAndDedupDoNotExtendDnsLifetime() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            val first = transport.query(query(provider(options = mapOf("cacheTtl" to 50))))
            dialer.answer(0, listOf("192.0.2.7", "192.0.2.7"), 100)
            assertEquals(1, first.await().records.size)
            assertEquals(50, first.await().records.single().ttlMillis)
            transport.invalidate()
            val zero = transport.query(query(provider(options = mapOf("cacheTtl" to 50))))
            dialer.answer(3, ttl = 0); zero.await()
            assertEquals(0, transport.state().entries)
        }
    }

    @Test fun failedCacheReplaysFailureForThirtySecondsRatherThanInventingEmptySuccess() {
        val dialer = Dialer(); val clock = Clock()
        transport(dialer, clock).use { transport ->
            val pending = transport.query(query())
            dialer.calls.toList().forEach { it.future.completeExceptionally(IOException("gateway unavailable")) }
            failure<NativeNat64QueryException>(pending)
            failure<NativeNat64QueryException>(transport.query(query()))
            assertEquals(3, dialer.calls.size)
            clock.now += 30_000
            transport.query(query())
            assertEquals(6, dialer.calls.size)
        }
    }

    @Test fun emptyAndNxdomainAnswersRemainNegativeWithoutIpCandidates() {
        for (code in listOf(NativeDnsAnswerCode.NXDOMAIN, NativeDnsAnswerCode.NODATA)) {
            val dialer = Dialer()
            transport(dialer).use { transport ->
                val pending = transport.query(query())
                dialer.calls[0].future.complete(NativeNat64DohAnswer.Addresses(
                    NativeDnsAnswer(code = code, negativeTtlMillis = 7_000)))
                val result = pending.await()
                assertEquals(code, result.code)
                assertTrue(result.records.isEmpty())
                assertEquals(7_000, result.negativeTtlMillis)
                assertTrue(transport.query(query()).await().records.isEmpty())
            }
        }
    }

    @Test fun malformedOrWrongFamilyAnswerCannotBecomeSuccessfulSynthesizedAddress() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            val pending = transport.query(query())
            dialer.calls.toList().forEach { it.future.complete(NativeNat64DohAnswer.Addresses(
                NativeDnsAnswer(listOf(NativeDnsRecord("::1", 100))))) }
            failure<NativeNat64QueryException>(pending)
        }
    }

    @Test fun lastSuccessfulDohIsPreferredForNextHostAndWinnerOnlyCancelsOwnFlight() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            val one = transport.query(query(host = "one.test"))
            val two = transport.query(query(host = "two.test"))
            dialer.answer(1); one.await()
            assertTrue(dialer.calls.take(3).filterIndexed { index, _ -> index != 1 }.all { it.future.isCancelled })
            assertTrue(dialer.calls.drop(3).all { !it.future.isDone })
            dialer.answer(3); two.await()
            transport.query(query(host = "three.test"))
            assertEquals("https://dns.alidns.com/resolve", dialer.calls[6].query.provider.server)
        }
    }

    @Test fun singleFlightAnyAndIpv6ShareQueryButCancellationDetachesOnlyOneWaiter() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            val one = transport.query(query())
            val two = transport.query(query().copy(family = NativeDnsFamily.ANY))
            assertEquals(3, dialer.calls.size)
            assertEquals(2, transport.state().waiters)
            one.cancel(true)
            assertTrue(dialer.calls.all { !it.future.isCancelled })
            dialer.answer(0)
            assertFalse(two.await().records.isEmpty())
        }
    }

    @Test fun lastWaiterCancellationAndInvalidationRejectLateUncooperativeResults() {
        val dialer = Dialer().apply { ignoreCancellation = true }
        transport(dialer).use { transport ->
            val canceled = transport.query(query()); canceled.cancel(true)
            val invalidated = transport.query(query())
            transport.invalidate("example.test")
            failure<NativeDnsInvalidatedException>(invalidated)
            val fresh = transport.query(query())
            dialer.answer(0); dialer.answer(3)
            assertFalse(fresh.isDone)
            assertEquals(0, transport.state().entries)
            dialer.answer(6)
            assertFalse(fresh.await().records.isEmpty())
        }
    }

    @Test fun planFlightWaiterAndCacheBoundsAreEnforced() {
        val dialer = Dialer()
        transport(dialer, limits = NativeNat64Limits(maxPlans = 1, maxEntries = 1, maxFlights = 1,
            maxWaiters = 1, parallelDelayMillis = 0)).use { transport ->
            val pending = transport.query(query())
            failure<NativeDnsCapacityException>(transport.query(query()))
            failure<NativeDnsCapacityException>(transport.query(query(host = "two.test")))
            failure<NativeDnsCapacityException>(transport.query(query(provider("other"))))
            dialer.answer(0); pending.await()
            val next = transport.query(query(host = "two.test"))
            dialer.answer(3); next.await()
            assertEquals(1, transport.state().entries)
        }
    }

    @Test fun changedConfigurationRequiresNewTransportEpoch() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            val pending = transport.query(query())
            dialer.answer(0); pending.await()
            failure<IllegalArgumentException>(transport.query(query(provider(options = mapOf("prefix" to "2001:db8:64::/96")))))
        }
    }

    @Test fun serviceRecordsUseSameNat64DialRouteWithoutChangingEchBytesAndAreNotCached() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            for ((index, type) in HttpsRecordType.entries.withIndex()) {
                val pending = transport.queryHttps(HttpsRecordQuery(provider(), "example.test", type))
                assertEquals(if (type == HttpsRecordType.HTTPS) NativeNat64Question.HTTPS else NativeNat64Question.SVCB,
                    dialer.calls[index * 3].query.type)
                dialer.calls[index * 3].future.complete(NativeNat64DohAnswer.Services(
                    HttpsRecordAnswer(listOf(NativeEchTestData.record()))))
                assertArrayEquals(NativeEchTestData.config(), pending.await().records.single().service.ech)
                assertEquals(0, transport.state().entries)
            }
        }
    }

    @Test fun closeCancelsAllCallersAndScheduledHedgesAndClosesOwnedResourcesOnce() {
        val dialer = Dialer()
        val transport = transport(dialer, limits = NativeNat64Limits(parallelDelayMillis = 5_000))
        val pending = transport.query(query())
        assertEquals(1, dialer.calls.size)
        transport.close(); transport.close()
        failure<NativeDnsClosedException>(pending)
        failure<NativeDnsClosedException>(transport.query(query()))
        assertTrue(dialer.calls.single().future.isCancelled)
        assertEquals(1, dialer.closed)
        assertTrue(transport.awaitTermination(5_000))
    }

    @Test fun wholeLookupDeadlineBoundsEvenUnresponsiveInjectedDialer() {
        val dialer = Dialer()
        transport(dialer, limits = NativeNat64Limits(queryTimeoutMillis = 100, parallelDelayMillis = 0)).use { transport ->
            failure<java.net.SocketTimeoutException>(transport.query(query()))
            assertTrue(dialer.calls.all { it.future.isCancelled })
            assertEquals(0, transport.state().flights)
        }
    }

    @Test fun staggeredHedgeCanWinAndRecentWinnerStartsFirstNextTime() {
        val hedge = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<NativeNat64DohQuery>())
        val futures = Collections.synchronizedList(mutableListOf<CompletableFuture<NativeNat64DohAnswer>>())
        val dialer = object : NativeNat64DohDialer {
            override fun queryNat64Doh(query: NativeNat64DohQuery): CompletableFuture<NativeNat64DohAnswer> {
                val future = CompletableFuture<NativeNat64DohAnswer>()
                calls.add(query); futures.add(future)
                if (calls.size == 2) hedge.countDown()
                return future
            }
        }
        val socket = SocketDnsTransport()
        NativeNat64Transport(socket, Clock(), dialer, NativeNat64Limits(parallelDelayMillis = 10)).use { transport ->
            val pending = transport.query(query())
            assertTrue(hedge.await(5, TimeUnit.SECONDS))
            futures[1].complete(NativeNat64DohAnswer.Addresses(NativeDnsAnswer(
                listOf(NativeDnsRecord("192.0.2.1", 100)))))
            pending.await()
            val previousCount = calls.size
            transport.query(query(host = "next.test"))
            assertEquals("https://cloudflare-dns.com/dns-query", calls[previousCount].provider.server)
        }
        assertTrue(socket.awaitTermination(5_000))
    }

    @Test fun frozenResolverPreservesPortFeedbackHostnameFallbackAndNat64CandidateTtl() {
        val dialer = Dialer()
        val transport = transport(dialer)
        val catalog = com.dsha.sidecar.net.dns.DnsProviderCatalog(listOf(provider()),
            mapOf("Nat64" to "builtin:Nat64"))
        NativeDnsResolver(catalog, transport, Clock(),
            nat64Domains = listOf("example.test")).use { resolver ->
            val http = resolver.resolve("example.test", destinationPort = 80)
            val https = resolver.resolve("example.test")
            dialer.answer(0)
            val first = http.await()
            assertEquals(Nat64Address.toNat64Address(prefix, "192.0.2.7"), first.selectedIp)
            assertEquals(first.selectedIp, https.await().selectedIp)
            assertTrue(resolver.feedback(first, false))
            val second = resolver.resolve("example.test", destinationPort = 80).await()
            assertEquals(Nat64Address.toNat64Address(prefix, "192.0.2.8"), second.selectedIp)
            resolver.feedback(second, false)
            assertTrue(resolver.resolve("example.test", destinationPort = 80).await().hostnameFallback)
            assertEquals(first.selectedIp, resolver.resolve("example.test").await().selectedIp)
        }
    }

    @Test fun ordinaryUdpQueriesDelegateToExistingSocketTransportWithoutNat64Plan() {
        NativeDnsUdpServer { socket, packet ->
            socket.reply(packet, NativeDnsTestPackets.response(packet.bytes(),
                listOf(NativeDnsTestPackets.a("example.test"))))
        }.use { server ->
            val underlying = SocketDnsTransport(NativeDnsEndpointResolver { dnsLoopback })
            NativeNat64Transport(underlying, Clock()).use { transport ->
                val result = transport.query(NativeDnsQuery(
                    DnsProviderDescriptor("a", "a", "udp", "resolver.test", port = server.port),
                    "example.test", NativeDnsFamily.IPV4)).await()
                assertEquals("192.0.2.7", result.records.single().address)
                assertEquals(0, transport.state().plans)
                server.finish()
            }
            assertTrue(underlying.awaitTermination(5_000))
        }
    }

    @Test fun providerInvalidationClearsAddressAndTypedFlightsWithoutTouchingOtherProvider() {
        val dialer = Dialer()
        transport(dialer).use { transport ->
            val a = transport.query(query())
            val service = transport.queryHttps(HttpsRecordQuery(provider(), "example.test"))
            val other = transport.query(query(provider("other")))
            transport.invalidate(providerKey = "builtin:Nat64")
            failure<NativeDnsInvalidatedException>(a)
            failure<NativeDnsInvalidatedException>(service)
            assertFalse(other.isDone)
            assertTrue(dialer.calls.take(6).all { it.future.isCancelled })
            dialer.answer(6)
            assertFalse(other.await().records.isEmpty())
        }
    }
}
