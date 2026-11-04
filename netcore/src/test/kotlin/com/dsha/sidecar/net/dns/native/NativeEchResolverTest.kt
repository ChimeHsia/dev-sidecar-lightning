// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderCatalog
import com.dsha.sidecar.net.dns.DnsProviderConfig
import com.dsha.sidecar.net.dns.EchConfigList
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeEchResolverTest {
    private class Clock(var time: Long = 100) : NativeDnsClock { override fun nowMillis() = time }
    private class Fake : HttpsRecordTransport {
        data class Call(val query: HttpsRecordQuery, val future: CompletableFuture<HttpsRecordAnswer>)
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        var ignoreCancel = false
        var closed = 0
        var synchronous: HttpsRecordAnswer? = null
        override fun queryHttps(query: HttpsRecordQuery): CompletableFuture<HttpsRecordAnswer> {
            val future = if (ignoreCancel) object : CompletableFuture<HttpsRecordAnswer>() {
                override fun cancel(mayInterruptIfRunning: Boolean) = false
            } else CompletableFuture()
            calls.add(Call(query, future))
            synchronous?.let(future::complete)
            return future
        }
        fun answer(index: Int, vararg records: HttpsServiceRecord) =
            calls[index].future.complete(HttpsRecordAnswer(records.toList()))
        override fun close() { closed++ }
    }

    private val catalog = DnsProviderConfig.normalize(linkedMapOf(
        "a" to mapOf("server" to "192.0.2.53"),
        "b" to mapOf("server" to "https://resolver.test/dns-query"),
    ))
    private val basic = NativeEchOptions(dns = "a", publicName = null, parallelDelayMillis = 0)
    private fun resolver(
        fake: Fake, clock: Clock = Clock(), options: NativeEchOptions = basic,
        limits: NativeEchLimits = NativeEchLimits(), providers: DnsProviderCatalog = catalog,
        mapping: Map<String, Any?> = emptyMap(), owns: Boolean = true,
    ) = NativeEchResolver(providers, fake, clock, options, mapping, limits, owns)
    private fun <T> CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)
    private inline fun <reified T : Throwable> failure(future: CompletableFuture<*>): Throwable {
        try { future.await(); fail("Expected ${T::class.java.simpleName}") }
        catch (error: ExecutionException) {
            assertTrue("Unexpected ${error.cause}", error.cause is T)
            return error.cause!!
        }
        error("Expected failure")
    }
    private fun warm(resolver: NativeEchResolver, fake: Fake, host: String = "example.test"): NativeEchResult {
        val pending = resolver.resolve(host)
        fake.answer(fake.calls.lastIndex, NativeEchTestData.record(owner = host))
        return pending.await()!!
    }

    @Test fun defaultsMatchUpstreamWithoutAddingProviderAndRawSettingsAreSnapshotted() {
        val defaults = NativeEchOptions.fromRaw(emptyMap())
        assertEquals(NativeEchOptions(), defaults)
        assertEquals("cloudflare-ech.com", defaults.publicName)
        assertEquals(600_000L, defaults.emptyTtlMillis)
        assertEquals(60_000L, defaults.minTtlMillis)
        assertEquals(3_600_000L, defaults.maxTtlMillis)
        assertEquals(200L, defaults.parallelDelayMillis)
        val raw = mutableMapOf<String, Any?>("dns" to "a", "publicName" to "", "minTtl" to "1000", "use" to false)
        val options = NativeEchOptions.fromRaw(raw)
        raw["dns"] = "invented"
        assertEquals("a", options.dns)
        assertFalse(options.use)
        assertEquals(1_000L, options.minTtlMillis)
    }

    @Test fun actualFixtureListReturnsFullDetachedBytesSelectedConfigAndProviderIdentity() {
        val fake = Fake()
        resolver(fake).use { resolver ->
            val result = warm(resolver, fake)
            assertEquals("provider:a", result.providerKey)
            assertEquals("a", result.providerName)
            assertEquals("udp", result.providerTransport)
            assertEquals("public.example", result.publicName)
            assertEquals(7, result.selectedConfig.contents!!.configId)
            assertArrayEquals(NativeEchTestData.config(), result.echConfigList)
            result.echConfigList.fill(0)
            assertArrayEquals(NativeEchTestData.config(), result.echConfigList)
            assertNotNull(EchConfigList.selectEchConfig(result.configs))
            assertFalse(result.shared)
            assertEquals(HttpsRecordType.HTTPS, fake.calls.single().query.type)
        }
    }

    @Test fun disabledOrUnusedEchReturnsNullWithoutQueries() {
        for (options in listOf(basic.copy(enabled = false), basic.copy(use = false))) {
            val fake = Fake()
            resolver(fake, options = options).use { resolver ->
                assertNull(resolver.resolve("example.test").await())
                assertTrue(fake.calls.isEmpty())
            }
        }
    }

    @Test fun dedicatedProviderQueriesOnlyThatProviderAndDisabledProviderIsSkipped() {
        val fake = Fake()
        resolver(fake, options = basic.copy(dns = "b", tryAllProviders = true)).use { resolver ->
            val future = resolver.resolve("example.test")
            assertEquals(listOf("provider:b"), fake.calls.map { it.query.provider.key })
            fake.answer(0, NativeEchTestData.record())
            assertEquals("provider:b", future.await()!!.providerKey)
        }
        val disabled = DnsProviderConfig.normalize(mapOf("a" to mapOf("server" to "192.0.2.53", "ech" to false)))
        val never = Fake()
        resolver(never, providers = disabled).use { resolver ->
            assertNull(resolver.resolve("example.test").await())
            assertTrue(never.calls.isEmpty())
        }
    }

    @Test fun mappedProviderThenForSniAndOthersAreDeduplicatedAndWinnerCancelsLoser() {
        val fake = Fake()
        resolver(fake, options = basic.copy(dns = null), mapping = mapOf(
            "example.test" to mapOf("dnsName" to "b"),
        )).use { resolver ->
            val future = resolver.resolve("example.test")
            assertEquals(listOf("provider:b", "provider:a"), fake.calls.map { it.query.provider.key })
            fake.answer(1, NativeEchTestData.record())
            assertEquals("provider:a", future.await()!!.providerKey)
            assertTrue(fake.calls[0].future.isCancelled)
        }
    }

    @Test fun tryAllFalseHasNoInventedProviderForUnmappedDomain() {
        val fake = Fake()
        resolver(fake, options = basic.copy(dns = null, tryAllProviders = false)).use { resolver ->
            assertNull(resolver.resolve("unmapped.test").await())
            assertTrue(fake.calls.isEmpty())
        }
    }

    @Test fun unknownDedicatedProviderFailsConstructionInsteadOfFallingBack() {
        try { resolver(Fake(), options = basic.copy(dns = "unknown")); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun providerErrorsDoNotPreventOtherProviderSuccess() {
        val fake = Fake()
        resolver(fake, options = basic.copy(dns = null)).use { resolver ->
            val future = resolver.resolve("example.test")
            fake.calls[0].future.completeExceptionally(IOException("offline"))
            fake.answer(1, NativeEchTestData.record())
            assertNotNull(future.await())
        }
    }

    @Test fun cleanAbsenceIsNegativeCachedButWireErrorsAreNotCachedOrSharedFallback() {
        val fake = Fake()
        val clock = Clock()
        resolver(fake, clock, basic.copy(emptyTtlMillis = 20, publicName = "shared.test")).use { resolver ->
            val future = resolver.resolve("example.test")
            fake.calls[0].future.completeExceptionally(NativeDnsProtocolException("wrong question"))
            failure<NativeEchLookupException>(future)
            assertEquals(0, resolver.state().entries)
            assertEquals(1, fake.calls.size)
            val retry = resolver.resolve("example.test")
            fake.answer(1)
            assertEquals("shared.test", fake.calls[2].query.hostname)
            fake.answer(2)
            assertNull(retry.await())
            assertNull(resolver.resolve("example.test").await())
            assertEquals(3, fake.calls.size)
            clock.time += 20
            resolver.resolve("example.test")
            assertEquals(4, fake.calls.size)
        }
    }

    @Test fun malformedAndUnsupportedEchAreExceptionalAndNotNegativeCached() {
        for (config in listOf(byteArrayOf(0, 0), NativeEchTestData.config("p256-only-fallback"))) {
            val fake = Fake()
            resolver(fake).use { resolver ->
                val pending = resolver.resolve("example.test")
                fake.answer(0, NativeEchTestData.record(config))
                val error = failure<NativeEchLookupException>(pending) as NativeEchLookupException
                assertTrue(error.failures.single() is NativeEchConfigException)
                assertEquals(0, resolver.state().entries)
                resolver.resolve("example.test")
                assertEquals(2, fake.calls.size)
            }
        }
    }

    @Test fun selectionSkipsInvalidHigherPriorityAndFindsUsableRecordInPriorityOrder() {
        val fake = Fake()
        resolver(fake).use { resolver ->
            val pending = resolver.resolve("example.test")
            fake.answer(0,
                NativeEchTestData.record(priority = 9, ttl = 400_000),
                NativeEchTestData.record(byteArrayOf(0, 0), priority = 1),
                NativeEchTestData.record(priority = 3, ttl = 80_000),
            )
            assertEquals(3, pending.await()!!.priority)
            assertEquals(80_000L, pending.await()!!.ttlMillis)
        }
    }

    @Test fun missingEchAndUnknownMandatoryParamAreCleanAbsence() {
        val fake = Fake()
        resolver(fake).use { resolver ->
            val pending = resolver.resolve("example.test")
            fake.answer(0, NativeEchTestData.record(ech = null),
                NativeEchTestData.record(extra = listOf(0 to byteArrayOf(0, 99), 99 to byteArrayOf(1))))
            assertNull(pending.await())
            assertEquals(1, resolver.state().entries)
        }
    }

    @Test fun sharedFallbackIsExplicitAndCachesNoLongerThanDirectAbsenceLifetime() {
        val fake = Fake()
        val clock = Clock()
        resolver(fake, clock, basic.copy(publicName = "shared.test", emptyTtlMillis = 5_000)).use { resolver ->
            val pending = resolver.resolve("example.test")
            fake.answer(0)
            assertEquals("shared.test", fake.calls[1].query.hostname)
            fake.answer(1, NativeEchTestData.record(owner = "shared.test"))
            val result = pending.await()!!
            assertTrue(result.shared)
            assertEquals("example.test", result.hostname)
            assertEquals("shared.test", result.queriedName)
            assertEquals(5_000L, result.ttlMillis)
            clock.time += 5_000
            resolver.resolve("example.test")
            assertEquals("example.test", fake.calls[2].query.hostname)
        }
    }

    @Test fun sharedNameEqualToTargetNeverLoops() {
        val fake = Fake()
        resolver(fake, options = basic.copy(publicName = "example.test")).use { resolver ->
            val pending = resolver.resolve("example.test")
            fake.answer(0)
            assertNull(pending.await())
            assertEquals(1, fake.calls.size)
        }
    }

    @Test fun aliasModeFollowsSameProviderAndAppliesAliasTtlWithoutRewritingPublicName() {
        val fake = Fake()
        resolver(fake, options = basic.copy(minTtlMillis = 0)).use { resolver ->
            val pending = resolver.resolve("example.test")
            fake.answer(0, NativeEchTestData.record(null, 0, "alias.test", 2_000))
            assertEquals("alias.test", fake.calls[1].query.hostname)
            fake.answer(1, NativeEchTestData.record(owner = "alias.test", ttl = 100_000))
            val result = pending.await()!!
            assertEquals(2_000L, result.ttlMillis)
            assertEquals("public.example", result.publicName)
            assertEquals("alias.test", result.queriedName)
        }
    }

    @Test fun aliasCycleDepthAndAmbiguousAliasModesFail() {
        for (mode in 0..2) {
            val fake = Fake()
            resolver(fake, limits = NativeEchLimits(maxAliasDepth = if (mode == 1) 0 else 3)).use { resolver ->
                val pending = resolver.resolve("example.test")
                when (mode) {
                    0 -> fake.answer(0, NativeEchTestData.record(null, 0, "example.test"))
                    1 -> fake.answer(0, NativeEchTestData.record(null, 0, "alias.test"))
                    2 -> fake.answer(0, NativeEchTestData.record(null, 0, "alias.test"), NativeEchTestData.record())
                }
                failure<NativeEchLookupException>(pending)
                assertEquals(0, resolver.state().entries)
            }
        }
    }

    @Test fun minMaxTtlClampingExpiryBoundaryAndNegativeTtlAreExplicit() {
        val fake = Fake()
        val clock = Clock()
        resolver(fake, clock, basic.copy(minTtlMillis = 20, maxTtlMillis = 50)).use { resolver ->
            val pending = resolver.resolve("example.test")
            fake.answer(0, NativeEchTestData.record(ttl = 0))
            assertEquals(20L, pending.await()!!.ttlMillis)
            clock.time = 119
            assertSame(pending.await(), resolver.resolve("example.test").await())
            clock.time = 120
            val next = resolver.resolve("example.test")
            fake.answer(1, NativeEchTestData.record(ttl = 10_000))
            assertEquals(50L, next.await()!!.ttlMillis)
            clock.time = 170
            assertEquals(0, resolver.state().entries)
        }
    }

    @Test fun hostnameInvalidationRejectsLateUncooperativeAnswersAndRequeries() {
        val fake = Fake().apply { ignoreCancel = true }
        resolver(fake).use { resolver ->
            val old = resolver.resolve("example.test")
            resolver.invalidate("example.test")
            failure<NativeDnsInvalidatedException>(old)
            val fresh = resolver.resolve("example.test")
            fake.answer(0, NativeEchTestData.record())
            assertFalse(fresh.isDone)
            assertEquals(0, resolver.state().entries)
            fake.answer(1, NativeEchTestData.record())
            assertNotNull(fresh.await())
        }
    }

    @Test fun sharedAndCanonicalDependencyInvalidationClearsOriginalLookupCache() {
        val fake = Fake()
        resolver(fake, options = basic.copy(publicName = "shared.test")).use { resolver ->
            val pending = resolver.resolve("example.test")
            fake.answer(0)
            fake.calls[1].future.complete(HttpsRecordAnswer(
                listOf(NativeEchTestData.record(owner = "canonical.test")), canonicalNames = listOf("canonical.test"),
            ))
            assertNotNull(pending.await())
            resolver.invalidate("canonical.test")
            assertEquals(0, resolver.state().entries)
            val next = resolver.resolve("example.test")
            fake.answer(2)
            fake.answer(3, NativeEchTestData.record(owner = "shared.test"))
            next.await()
            resolver.invalidate("shared.test")
            assertEquals(0, resolver.state().entries)
        }
    }

    @Test fun providerInvalidationOnlyClearsMatchingCachedAndPendingQueries() {
        val fake = Fake()
        resolver(fake, options = basic.copy(dns = null, tryAllProviders = false), mapping = mapOf(
            "one.test" to mapOf("dnsName" to "a"), "two.test" to mapOf("dnsName" to "b"),
        )).use { resolver ->
            warm(resolver, fake, "one.test")
            val pending = resolver.resolve("two.test")
            resolver.invalidate(providerKey = "provider:a")
            assertFalse(pending.isDone)
            assertEquals(0, resolver.state().entries)
            resolver.invalidate(providerKey = "provider:b")
            failure<NativeDnsInvalidatedException>(pending)
        }
    }

    @Test fun concurrentLookupsShareOneQueryAndCancelingOneWaiterPreservesOther() {
        val fake = Fake()
        resolver(fake).use { resolver ->
            val ready = CountDownLatch(4)
            val go = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val calls = (0..3).map {
                    pool.submit<CompletableFuture<NativeEchResult?>> {
                        ready.countDown(); check(go.await(5, TimeUnit.SECONDS))
                        resolver.resolve("EXAMPLE.TEST.")
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
                val waiters = calls.map { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, fake.calls.size)
                assertEquals(4, resolver.state().waiters)
                waiters[0].cancel(true)
                assertFalse(fake.calls[0].future.isCancelled)
                fake.answer(0, NativeEchTestData.record())
                waiters.drop(1).forEach { assertNotNull(it.await()) }
            } finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }

    @Test fun lastWaiterCancellationCancelsAllProvidersAndDoesNotCache() {
        val fake = Fake()
        resolver(fake, options = basic.copy(dns = null)).use { resolver ->
            val pending = resolver.resolve("example.test")
            assertEquals(2, fake.calls.size)
            pending.cancel(true)
            assertTrue(fake.calls.all { it.future.isCancelled })
            assertEquals(0, resolver.state().flights)
            assertEquals(0, resolver.state().entries)
        }
    }

    @Test fun boundedFlightsWaitersAndProviderCountRejectOverload() {
        val fake = Fake()
        resolver(fake, limits = NativeEchLimits(maxFlights = 1, maxWaiters = 1)).use { resolver ->
            val pending = resolver.resolve("one.test")
            failure<NativeDnsCapacityException>(resolver.resolve("one.test"))
            failure<NativeDnsCapacityException>(resolver.resolve("two.test"))
            pending.cancel(true)
            val next = resolver.resolve("two.test")
            fake.answer(1, NativeEchTestData.record(owner = "two.test"))
            assertNotNull(next.await())
        }
        resolver(Fake(), options = basic.copy(dns = null), limits = NativeEchLimits(maxProviders = 1)).use {
            failure<IllegalArgumentException>(it.resolve("example.test"))
        }
    }

    @Test fun cacheIsBoundedLruAcrossPositiveAndNegativeEntries() {
        val fake = Fake()
        resolver(fake, limits = NativeEchLimits(maxEntries = 2)).use { resolver ->
            warm(resolver, fake, "one.test")
            val negative = resolver.resolve("two.test"); fake.answer(1); assertNull(negative.await())
            resolver.resolve("one.test").await()
            warm(resolver, fake, "three.test")
            assertEquals(2, resolver.state().entries)
            resolver.resolve("two.test")
            assertEquals(4, fake.calls.size)
        }
    }

    @Test fun closeCancelsInflightCallersAndDoesNotCloseBorrowedTransport() {
        val fake = Fake()
        val resolver = resolver(fake, owns = false)
        val first = resolver.resolve("example.test")
        val second = resolver.resolve("example.test")
        resolver.close(); resolver.close()
        failure<NativeDnsClosedException>(first)
        failure<NativeDnsClosedException>(second)
        failure<NativeDnsClosedException>(resolver.resolve("example.test"))
        assertTrue(fake.calls.single().future.isCancelled)
        assertEquals(0, fake.closed)
        val owned = Fake()
        resolver(owned).close()
        assertEquals(1, owned.closed)
    }

    @Test fun overallDeadlineCancelsSilentTransportAndDoesNotNegativeCache() {
        val fake = Fake()
        resolver(fake, limits = NativeEchLimits(lookupTimeoutMillis = 100)).use { resolver ->
            failure<SocketTimeoutException>(resolver.resolve("example.test"))
            assertTrue(fake.calls.single().future.isCancelled)
            assertEquals(0, resolver.state().entries)
        }
    }

    @Test fun synchronousTransportCompletionDoesNotLaunchLosersOrLeakFlights() {
        val fake = Fake().apply { synchronous = HttpsRecordAnswer(listOf(NativeEchTestData.record())) }
        resolver(fake, options = basic.copy(dns = null)).use { resolver ->
            assertNotNull(resolver.resolve("example.test").await())
            assertEquals(1, fake.calls.size)
            assertEquals(0, resolver.state().flights)
        }
    }

    @Test fun delayedProviderHedgeReturnsFirstUsableAndCancelsSlowMappedProvider() {
        val secondStarted = CountDownLatch(1)
        val slow = CompletableFuture<HttpsRecordAnswer>()
        val fast = CompletableFuture<HttpsRecordAnswer>()
        val transport = object : HttpsRecordTransport {
            override fun queryHttps(query: HttpsRecordQuery): CompletableFuture<HttpsRecordAnswer> =
                if (query.provider.key == "provider:a") slow else fast.also { secondStarted.countDown() }
        }
        NativeEchResolver(catalog, transport, Clock(),
            basic.copy(dns = null, parallelDelayMillis = 10), mapOf("example.test" to mapOf("dnsName" to "a"))).use {
            val result = it.resolve("example.test")
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            fast.complete(HttpsRecordAnswer(listOf(NativeEchTestData.record())))
            assertEquals("provider:b", result.await()!!.providerKey)
            assertTrue(slow.isCancelled)
        }
    }

    @Test fun zeroNegativeTtlDoesNotRetainCleanAbsence() {
        val fake = Fake()
        resolver(fake, options = basic.copy(emptyTtlMillis = 0)).use { resolver ->
            val pending = resolver.resolve("example.test")
            fake.answer(0)
            assertNull(pending.await())
            assertEquals(0, resolver.state().entries)
        }
    }

    @Test fun realUdpHttpsRecordFeedsEchResolverWithoutNodeOrTlsHandshake() {
        NativeDnsUdpServer { socket, packet ->
            val request = packet.bytes()
            assertEquals(65, NativeDnsTestPackets.question(request).type)
            socket.reply(packet, NativeDnsTestPackets.response(request,
                listOf(NativeDnsTestPackets.Rr("example.test", 65, NativeEchTestData.rdata(), 90))))
        }.use { server ->
            val providers = DnsProviderConfig.normalize(mapOf("local" to mapOf(
                "server" to "127.0.0.1", "port" to server.port,
            )))
            val transport = SocketDnsTransport(NativeDnsEndpointResolver { dnsLoopback })
            NativeEchResolver(providers, transport, Clock(), basic.copy(dns = "local")).use { resolver ->
                val result = resolver.resolve("example.test").await()!!
                assertEquals("public.example", result.publicName)
                assertEquals(90_000, result.ttlMillis)
                assertArrayEquals(NativeEchTestData.config(), result.echConfigList)
                assertEquals(0, transport.state().resources)
                server.finish()
            }
            assertTrue(transport.awaitTermination(5_000))
        }
    }

    @Test fun nxdomainAndNoDataAreCleanMissingEchWithExplicitEmptyLifetime() {
        for (code in listOf(NativeDnsAnswerCode.NXDOMAIN, NativeDnsAnswerCode.NODATA)) {
            val fake = Fake()
            resolver(fake, options = basic.copy(emptyTtlMillis = 20)).use { resolver ->
                val pending = resolver.resolve("example.test")
                fake.calls[0].future.complete(HttpsRecordAnswer(code = code, negativeTtlMillis = 5))
                assertNull(pending.await())
                assertNull(resolver.resolve("example.test").await())
                assertEquals(1, fake.calls.size)
                assertEquals(1, resolver.state().entries)
            }
        }
    }

    @Test fun untrustedTypedTransportCannotInjectOwnerOrOversizeNegativeAnswer() {
        val answers = listOf(
            HttpsRecordAnswer(listOf(NativeEchTestData.record(owner = "injected.test"))),
            HttpsRecordAnswer(listOf(NativeEchTestData.record()), code = NativeDnsAnswerCode.NXDOMAIN),
            HttpsRecordAnswer(negativeTtlMillis = -1),
            HttpsRecordAnswer(canonicalNames = listOf("example.test")),
        )
        for (answer in answers) {
            val fake = Fake()
            resolver(fake).use { resolver ->
                val pending = resolver.resolve("example.test")
                fake.calls[0].future.complete(answer)
                failure<NativeEchLookupException>(pending)
                assertEquals(0, resolver.state().entries)
            }
        }
    }

    @Test fun activeAliasAndSharedDependencyInvalidationCancelsOriginalWaiters() {
        for (shared in listOf(true, false)) {
            val fake = Fake()
            resolver(fake, options = basic.copy(publicName = if (shared) "shared.test" else null)).use { resolver ->
                val pending = resolver.resolve("example.test")
                if (shared) fake.answer(0)
                else fake.answer(0, NativeEchTestData.record(null, 0, "shared.test"))
                assertEquals("shared.test", fake.calls[1].query.hostname)
                resolver.invalidate("shared.test")
                failure<NativeDnsInvalidatedException>(pending)
                assertTrue(fake.calls[1].future.isCancelled)
                assertEquals(0, resolver.state().flights)
            }
        }
    }

    @Test fun synchronousTransportThrowsReleaseAllWaitersWithoutCaching() {
        val broken = object : HttpsRecordTransport {
            override fun queryHttps(query: HttpsRecordQuery): CompletableFuture<HttpsRecordAnswer> =
                throw IOException("No endpoint")
        }
        NativeEchResolver(catalog, broken, Clock(), basic).use { resolver ->
            repeat(3) { failure<NativeEchLookupException>(resolver.resolve("example.test")) }
            assertEquals(NativeEchResolver.State(0, 0, 0, false), resolver.state())
        }
    }

    @Test fun cancelDuringDelayedHedgePreventsNewQueriesAndSchedulerStopsOnClose() {
        val fake = Fake()
        val resolver = resolver(fake, options = basic.copy(dns = null, parallelDelayMillis = 60_000))
        val pending = resolver.resolve("example.test")
        assertEquals(1, fake.calls.size)
        pending.cancel(true)
        assertTrue(fake.calls.single().future.isCancelled)
        resolver.close()
        assertTrue(resolver.awaitTermination(5_000))
        assertEquals(1, fake.calls.size)
        assertEquals(0, resolver.state().flights)
    }

    @Test fun rawConfigRejectsInvalidTtlAndUnknownProfileFieldsAreNotCoerced() {
        for (raw in listOf(
            mapOf("minTtl" to -1), mapOf("maxTtl" to "bad"),
            mapOf("minTtl" to 100, "maxTtl" to 50),
            mapOf("publicName" to "https://wrong.test"), mapOf("dns" to 17),
        )) {
            try { NativeEchOptions.fromRaw(raw); fail("Invalid ECH settings accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun multipleConfigsPreserveWholeWireListWhileSelectingSupportedProfile() {
        val fake = Fake()
        resolver(fake).use { resolver ->
            val pending = resolver.resolve("example.test")
            val full = NativeEchTestData.config("prefer-x25519-after-p256")
            fake.answer(0, NativeEchTestData.record(full))
            val result = pending.await()!!
            assertEquals(2, result.configs.size)
            assertEquals(10, result.selectedConfig.contents!!.configId)
            assertArrayEquals(full, result.echConfigList)
            assertTrue(result.echConfigList.size > result.selectedConfig.raw.size)
        }
    }

    @Test fun failedSharedLookupDoesNotCacheAbsenceOrPretendRequiredEchSucceeded() {
        val fake = Fake()
        resolver(fake, options = basic.copy(publicName = "shared.test")).use { resolver ->
            val pending = resolver.resolve("example.test")
            fake.answer(0)
            fake.calls[1].future.completeExceptionally(IOException("shared endpoint offline"))
            failure<NativeEchLookupException>(pending)
            assertEquals(0, resolver.state().entries)
            assertEquals(0, resolver.state().flights)
        }
    }
}
