// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import com.dsha.sidecar.net.dns.DnsProviderConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeDnsResolverTest {
    private class Clock(var now: Long = 100) : NativeDnsClock {
        override fun nowMillis() = now
    }

    private class FakeTransport : DnsTransport {
        data class Call(val query: NativeDnsQuery, val answer: CompletableFuture<NativeDnsAnswer>)
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        var closed = 0
        var failure: RuntimeException? = null
        var ignoreCancel = false

        override fun query(query: NativeDnsQuery): CompletableFuture<NativeDnsAnswer> {
            failure?.let { throw it }
            val answer = if (ignoreCancel) object : CompletableFuture<NativeDnsAnswer>() {
                override fun cancel(mayInterruptIfRunning: Boolean) = false
            } else CompletableFuture()
            calls.add(Call(query, answer))
            return answer
        }

        fun answer(index: Int, vararg ips: String, ttl: Long = 1_000) {
            calls[index].answer.complete(NativeDnsAnswer(ips.map { NativeDnsRecord(it, ttl) }))
        }

        override fun close() { closed++ }
    }

    private val providers = linkedMapOf<String, Any?>(
        "a" to mapOf("server" to "192.0.2.53", "family" to 4),
        "b" to mapOf("server" to "https://resolver.test/dns-query"),
    )
    private val mapping = mapOf<String, Any?>("*" to mapOf("dnsName" to "a"))
    private val first = "192.0.2.1"
    private val second = "192.0.2.2"
    private val v6 = "2001:db8::1"

    private fun resolver(
        transport: FakeTransport,
        clock: Clock = Clock(),
        rules: Map<String, Any?> = mapping,
        presets: Map<String, Any?> = emptyMap(),
        limits: NativeDnsLimits = NativeDnsLimits(),
        available: Boolean = true,
        order: NativeDnsCandidateOrder = NativeDnsCandidateOrder { _, ips -> ips },
        configuration: Map<String, Any?> = providers,
    ) = NativeDnsResolver(
        DnsProviderConfig.normalize(configuration), transport, clock, rules, presets,
        ipv6Available = available, limits = limits, candidateOrder = order,
    )

    private fun <T> CompletableFuture<T>.await(): T = get(3, TimeUnit.SECONDS)

    private inline fun <reified T : Throwable> failure(future: CompletableFuture<*>) {
        try {
            future.await()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: ExecutionException) {
            assertTrue("Unexpected failure: ${error.cause}", error.cause is T)
        }
    }

    private fun warm(
        resolver: NativeDnsResolver,
        transport: FakeTransport,
        host: String = "example.test",
        vararg ips: String = arrayOf(first, second),
    ): NativeDnsResult {
        val future = resolver.resolve(host)
        transport.answer(transport.calls.lastIndex, *ips)
        return future.await()
    }

    @Test fun descriptorsMappingAndFamilyArePassedToTransport() {
        val transport = FakeTransport()
        resolver(transport, rules = mapOf(
            "*" to mapOf("dnsName" to "a"),
            "example.test" to mapOf("dnsName" to "b", "family" to "6"),
        )).use { resolver ->
            val future = resolver.resolve(" Example.TEST. ")
            val query = transport.calls.single().query
            assertEquals("provider:b", query.provider.key)
            assertEquals("https", query.provider.transport)
            assertEquals("https://resolver.test/dns-query", query.provider.server)
            assertEquals("example.test", query.hostname)
            assertEquals(NativeDnsFamily.IPV6, query.family)
            transport.answer(0, first, v6)
            val result = future.await()
            assertEquals(v6, result.selectedIp)
            assertEquals(listOf(v6, "example.test"), result.candidateOrder)
        }
    }

    @Test fun requestedFamilyOverridesMappingAndDescriptor() {
        val transport = FakeTransport()
        resolver(transport, rules = mapOf(
            "*" to mapOf("dnsName" to "a", "family" to 6),
        )).use { resolver ->
            val request = resolver.resolve("example.test", NativeDnsFamily.IPV4)
            assertEquals(NativeDnsFamily.IPV4, transport.calls.single().query.family)
            transport.answer(0, v6, first)
            assertEquals(listOf(first), request.await().addresses)
        }
    }

    @Test fun absentProviderReturnsExplicitUnresolvedFallbackWithoutQuery() {
        val transport = FakeTransport()
        resolver(transport, rules = emptyMap()).use { resolver ->
            val result = resolver.resolve("unmapped.test").await()
            assertEquals(NativeDnsStatus.NO_PROVIDER, result.status)
            assertTrue(result.hostnameFallback)
            assertNull(result.selectedIp)
            assertFalse(resolver.feedback(result, true))
            assertTrue(transport.calls.isEmpty())
        }
    }

    @Test fun presetRulesBypassTransportButRetainFamilyAndFeedback() {
        val transport = FakeTransport()
        resolver(transport, presets = mapOf("*.example.test" to listOf(v6, first, second))).use { resolver ->
            val result = resolver.resolve("cdn.example.test", NativeDnsFamily.IPV4).await()
            assertEquals("builtin:PreSet", result.providerKey)
            assertEquals(listOf(first, second), result.addresses)
            assertTrue(transport.calls.isEmpty())
            assertTrue(resolver.feedback(result, false))
            assertEquals(second, resolver.resolve("cdn.example.test", NativeDnsFamily.IPV4).await().selectedIp)
        }
    }

    @Test fun echAndNat64SelectDescriptorsWithoutImplementingTransports() {
        val transport = FakeTransport()
        val catalog = DnsProviderConfig.normalize(providers, mapOf("nat64" to mapOf("enabled" to true)))
        NativeDnsResolver(
            catalog, transport, Clock(), mapping,
            presets = mapOf("secure.test" to listOf(first)),
            ech = mapOf("dns" to "b", "domains" to listOf("secure.test")),
            nat64Domains = listOf("ipv6.test"),
        ).use { resolver ->
            resolver.resolve("secure.test")
            resolver.resolve("ipv6.test")
            assertEquals(listOf("provider:b", "builtin:Nat64"), transport.calls.map { it.query.provider.key })
            assertEquals(listOf("https", "nat64"), transport.calls.map { it.query.provider.transport })
        }
    }

    @Test fun literalNeverCallsTransportAndPreservesScopeCase() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val result = resolver.resolve("fe80::1%EtherNet").await()
            assertEquals("fe80::1%EtherNet", result.selectedIp)
            assertEquals(NativeDnsStatus.LITERAL, result.status)
            val wrongFamily = resolver.resolve(first, NativeDnsFamily.IPV6).await()
            assertEquals(NativeDnsStatus.NODATA, wrongFamily.status)
            assertNull(wrongFamily.selectedDestination)
            assertTrue(transport.calls.isEmpty())
        }
    }

    @Test fun rankingExposesExactFastestIpAndOrderWithoutRevertingToDnsFirst() {
        val transport = FakeTransport()
        resolver(transport, order = NativeDnsCandidateOrder { _, ips -> ips.reversed() }).use { resolver ->
            val result = warm(resolver, transport)
            assertEquals(second, result.selectedIp)
            assertEquals(listOf(second, first, "example.test"), result.candidateOrder)
            assertEquals(listOf(second, first), result.addresses)
            assertEquals(second, resolver.resolve("example.test").await().selectedIp)
        }
    }

    @Test fun allProbesFailDoesNotFabricateAnIpOrSuccessfulResult() {
        val transport = FakeTransport()
        resolver(transport, order = NativeDnsCandidateOrder { _, _ -> emptyList() }).use { resolver ->
            val result = warm(resolver, transport)
            assertEquals(NativeDnsStatus.NO_REACHABLE_IP, result.status)
            assertTrue(result.addresses.isEmpty())
            assertNull(result.selectedIp)
            assertTrue(result.hostnameFallback)
            assertEquals(0, resolver.state().entries)
        }
    }

    @Test fun rankerCannotInjectAnAddressOrDuplicateAttempts() {
        for (badOrder in listOf(listOf("203.0.113.99"), listOf(first, first))) {
            val transport = FakeTransport()
            resolver(transport, order = NativeDnsCandidateOrder { _, _ -> badOrder }).use { resolver ->
                val request = resolver.resolve("example.test")
                transport.answer(0, first)
                failure<IllegalArgumentException>(request)
                assertEquals(0, resolver.state().entries)
                assertEquals(0, resolver.state().flights)
            }
        }
    }

    @Test fun firstFailureSwitchesImmediatelyThenHostnameThenExhaustionAndReset() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val initial = warm(resolver, transport)
            assertTrue(resolver.feedback(initial, false))
            val next = resolver.resolve("example.test").await()
            assertEquals(second, next.selectedIp)
            assertEquals(listOf(second, "example.test"), next.candidateOrder)
            assertTrue(resolver.feedback(next, false))
            val fallback = resolver.resolve("example.test").await()
            assertTrue(fallback.hostnameFallback)
            assertNull(fallback.selectedIp)
            assertTrue(resolver.feedback(fallback, false))
            val exhausted = resolver.resolve("example.test").await()
            assertEquals(NativeDnsStatus.EXHAUSTED, exhausted.status)
            assertNull(exhausted.selectedDestination)
            assertTrue(exhausted.candidateOrder.isEmpty())
            assertTrue(resolver.reset(exhausted))
            assertFalse(resolver.feedback(initial, false))
            val reset = resolver.resolve("example.test").await()
            assertEquals(first, reset.selectedIp)
            assertEquals(listOf(first, second, "example.test"), reset.candidateOrder)
            assertEquals(1, transport.calls.size)
        }
    }

    @Test fun lateSuccessForPriorIpDoesNotUndoFallbackAndSuccessKeepsCurrentIp() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val firstAttempt = warm(resolver, transport)
            resolver.feedback(firstAttempt, false)
            assertTrue(resolver.feedback(firstAttempt, true))
            val secondAttempt = resolver.resolve("example.test").await()
            assertEquals(second, secondAttempt.selectedIp)
            assertTrue(resolver.feedback(secondAttempt, true))
            assertEquals(second, resolver.resolve("example.test").await().selectedIp)
        }
    }

    @Test fun minimumTtlExpiresAtBoundaryAndRejectsOldFeedback() {
        val transport = FakeTransport()
        val clock = Clock()
        resolver(transport, clock).use { resolver ->
            val request = resolver.resolve("example.test")
            transport.calls[0].answer.complete(NativeDnsAnswer(listOf(
                NativeDnsRecord(first, 500), NativeDnsRecord(second, 100),
            )))
            val old = request.await()
            clock.now = 199
            assertTrue(resolver.resolve("example.test").await().cached)
            clock.now = 200
            val refreshed = resolver.resolve("example.test")
            assertFalse(resolver.feedback(old, false))
            transport.answer(1, second)
            assertFalse(refreshed.await().cached)
            assertEquals(second, resolver.resolve("example.test").await().selectedIp)
        }
    }

    @Test fun ttlCapAndZeroTtlAreExplicit() {
        val transport = FakeTransport()
        val clock = Clock()
        resolver(transport, clock, limits = NativeDnsLimits(maxTtlMillis = 50)).use { resolver ->
            val old = warm(resolver, transport)
            clock.now += 50
            assertEquals(0, resolver.state().entries)
            assertFalse(resolver.feedback(old, true))
            val next = resolver.resolve("example.test")
            transport.answer(1, first, ttl = 0)
            assertEquals(first, next.await().selectedIp)
            assertEquals(0, resolver.state().entries)
            assertFalse(resolver.feedback(next.await(), true))
        }
    }

    @Test fun positiveAnswerDeduplicatesIpsAndReturnsDefensiveLists() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val result = warm(resolver, transport, ips = arrayOf(first, first, second))
            assertEquals(listOf(first, second), result.addresses)
            (result.candidateOrder as MutableList).clear()
            (result.addresses as MutableList).clear()
            assertEquals(listOf(first, second, "example.test"), result.candidateOrder)
            assertEquals(listOf(first, second), resolver.resolve("example.test").await().addresses)
        }
    }

    @Test fun authoritativeNegativeAnswersCacheOnlyTheirExplicitTtl() {
        for (code in listOf(NativeDnsAnswerCode.NXDOMAIN, NativeDnsAnswerCode.NODATA)) {
            val transport = FakeTransport()
            val clock = Clock()
            resolver(transport, clock).use { resolver ->
                val future = resolver.resolve("negative.test")
                transport.calls[0].answer.complete(NativeDnsAnswer(code = code, negativeTtlMillis = 50))
                val negative = future.await()
                assertEquals(code.name, negative.status.name)
                assertTrue(negative.hostnameFallback)
                assertNull(negative.selectedIp)
                assertTrue(resolver.resolve("negative.test").await().cached)
                assertFalse(resolver.reset(negative))
                clock.now += 50
                resolver.resolve("negative.test")
                assertEquals(2, transport.calls.size)
            }
        }
    }

    @Test fun emptyOrFamilyFilteredAnswersNeverInventCandidates() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val empty = resolver.resolve("empty.test")
            transport.answer(0)
            assertEquals(NativeDnsStatus.NODATA, empty.await().status)
            val filtered = resolver.resolve("filtered.test")
            transport.answer(1, v6)
            assertTrue(filtered.await().hostnameFallback)
            assertNull(filtered.await().selectedIp)
            assertEquals(0, resolver.state().entries)
        }
    }

    @Test fun transportFailuresAreExceptionalAndRetriedNotNegativeCached() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val request = resolver.resolve("example.test")
            transport.calls[0].answer.completeExceptionally(IOException("offline"))
            failure<IOException>(request)
            assertEquals(0, resolver.state().entries)
            val retry = resolver.resolve("example.test")
            transport.answer(1, first)
            assertEquals(first, retry.await().selectedIp)
        }
    }

    @Test fun synchronousTransportFailureDoesNotLeakFlightOrWaiters() {
        val transport = FakeTransport().apply { failure = IllegalStateException("unsupported transport") }
        resolver(transport).use { resolver ->
            repeat(5) { failure<IllegalStateException>(resolver.resolve("example.test")) }
            assertEquals(NativeDnsResolver.State(0, 0, 0, false), resolver.state())
        }
    }

    @Test fun malformedAndOversizeAnswersReleaseWaitersWithoutCaching() {
        val badAnswers = listOf(
            NativeDnsAnswer(listOf(NativeDnsRecord("not-an-ip.test", 100))),
            NativeDnsAnswer(listOf(NativeDnsRecord(first, -1))),
            NativeDnsAnswer(listOf(NativeDnsRecord(first, 100)), NativeDnsAnswerCode.NXDOMAIN),
            NativeDnsAnswer(negativeTtlMillis = -1),
            NativeDnsAnswer(listOf(NativeDnsRecord(first, 100), NativeDnsRecord(second, 100))),
        )
        for (answer in badAnswers) {
            val transport = FakeTransport()
            resolver(transport, limits = NativeDnsLimits(maxCandidates = 1)).use { resolver ->
                val request = resolver.resolve("example.test")
                transport.calls[0].answer.complete(answer)
                failure<IllegalArgumentException>(request)
                assertEquals(NativeDnsResolver.State(0, 0, 0, false), resolver.state())
            }
        }
    }

    @Test fun globalAndPerProviderLruEvictionRejectStaleFeedback() {
        val transport = FakeTransport()
        resolver(transport, limits = NativeDnsLimits(maxEntries = 3, maxEntriesPerProvider = 2),
            rules = mapOf(
                "*.a.test" to mapOf("dnsName" to "a"),
                "*.b.test" to mapOf("dnsName" to "b"),
            )).use { resolver ->
            val oldest = warm(resolver, transport, "one.a.test")
            warm(resolver, transport, "two.a.test")
            resolver.resolve("one.a.test").await()
            warm(resolver, transport, "three.a.test")
            assertTrue(resolver.feedback(oldest, true))
            resolver.resolve("three.a.test").await()
            warm(resolver, transport, "one.b.test")
            warm(resolver, transport, "two.b.test")
            assertEquals(3, resolver.state().entries)
            assertFalse(resolver.feedback(oldest, true))
            resolver.resolve("two.a.test")
            assertEquals(6, transport.calls.size)
        }
    }

    @Test fun providerCacheSizeCanDisableCacheAndCannotExceedHardBound() {
        for (size in listOf(0, 100_000)) {
            val transport = FakeTransport()
            resolver(transport, configuration = mapOf(
                "a" to mapOf("server" to "192.0.2.53", "cacheSize" to size),
            ), limits = NativeDnsLimits(maxEntriesPerProvider = 1)).use { resolver ->
                warm(resolver, transport, "one.test")
                warm(resolver, transport, "two.test")
                assertEquals(if (size == 0) 0 else 1, resolver.state().entries)
            }
        }
    }

    @Test fun familyCachesAndFeedbackAreIsolatedButHostnameInvalidationCoversAll() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val ipv4 = resolver.resolve("example.test", NativeDnsFamily.IPV4)
            val ipv6 = resolver.resolve("example.test", NativeDnsFamily.IPV6)
            transport.answer(0, first, second)
            transport.answer(1, v6)
            resolver.feedback(ipv4.await(), false)
            assertEquals(v6, resolver.resolve("example.test", NativeDnsFamily.IPV6).await().selectedIp)
            resolver.invalidate("example.test", family = NativeDnsFamily.IPV4)
            assertEquals(1, resolver.state().entries)
            assertFalse(resolver.feedback(ipv4.await(), true))
            resolver.invalidate("EXAMPLE.TEST.")
            assertEquals(0, resolver.state().entries)
            assertFalse(resolver.feedback(ipv6.await(), true))
        }
    }

    @Test fun providerScopedInvalidationPreservesOtherProviders() {
        val transport = FakeTransport()
        resolver(transport, rules = mapOf(
            "a.test" to mapOf("dnsName" to "a"), "b.test" to mapOf("dnsName" to "b"),
        )).use { resolver ->
            val a = warm(resolver, transport, "a.test")
            val b = warm(resolver, transport, "b.test")
            resolver.invalidate(providerKey = "provider:a")
            assertFalse(resolver.feedback(a, false))
            assertTrue(resolver.feedback(b, true))
            assertEquals(1, resolver.state().entries)
        }
    }

    @Test fun concurrentEquivalentRequestsSingleFlightAndShareOrderedResult() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val pool = Executors.newFixedThreadPool(4)
            val ready = CountDownLatch(4)
            val go = CountDownLatch(1)
            try {
                val tasks = (0..3).map { index ->
                    pool.submit<CompletableFuture<NativeDnsResult>> {
                        ready.countDown()
                        check(go.await(3, TimeUnit.SECONDS))
                        resolver.resolve(if (index % 2 == 0) "EXAMPLE.TEST." else "example.test")
                    }
                }
                assertTrue(ready.await(3, TimeUnit.SECONDS))
                go.countDown()
                val requests = tasks.map { it.get(3, TimeUnit.SECONDS) }
                assertEquals(1, transport.calls.size)
                assertEquals(4, resolver.state().waiters)
                transport.answer(0, first, second)
                requests.forEach { assertEquals(listOf(first, second, "example.test"), it.await().candidateOrder) }
                assertEquals(0, resolver.state().flights)
                assertEquals(0, resolver.state().waiters)
            } finally {
                go.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun cancellationOfOneWaiterLeavesOtherWaiterAndTransportRunning() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val canceled = resolver.resolve("example.test")
            val survivor = resolver.resolve("example.test")
            assertTrue(canceled.cancel(true))
            assertFalse(transport.calls[0].answer.isCancelled)
            assertEquals(1, resolver.state().waiters)
            transport.answer(0, first)
            assertEquals(first, survivor.await().selectedIp)
        }
    }

    @Test fun lastWaiterCancellationRemovesFlightAndCancelsTransport() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val request = resolver.resolve("example.test")
            request.cancel(true)
            assertTrue(transport.calls[0].answer.isCancelled)
            assertEquals(0, resolver.state().flights)
            val retry = resolver.resolve("example.test")
            transport.answer(1, second)
            assertEquals(second, retry.await().selectedIp)
        }
    }

    @Test fun cancellationIgnoresLateUncooperativeAnswerAndCannotOverwriteNewFlight() {
        val transport = FakeTransport().apply { ignoreCancel = true }
        resolver(transport).use { resolver ->
            resolver.resolve("example.test").cancel(true)
            val retry = resolver.resolve("example.test")
            transport.answer(0, first)
            assertFalse(retry.isDone)
            assertEquals(0, resolver.state().entries)
            transport.answer(1, second)
            assertEquals(second, retry.await().selectedIp)
        }
    }

    @Test fun invalidationCancelsPendingCallersAndRejectsLateAnswer() {
        val transport = FakeTransport().apply { ignoreCancel = true }
        resolver(transport).use { resolver ->
            val firstWaiter = resolver.resolve("example.test")
            val secondWaiter = resolver.resolve("example.test")
            resolver.invalidate("example.test")
            failure<NativeDnsInvalidatedException>(firstWaiter)
            failure<NativeDnsInvalidatedException>(secondWaiter)
            val replacement = resolver.resolve("example.test")
            transport.answer(0, first)
            assertFalse(replacement.isDone)
            transport.answer(1, second)
            assertEquals(second, replacement.await().selectedIp)
        }
    }

    @Test fun flightAndWaiterLimitsRejectOverloadAndCapacityRecovers() {
        val transport = FakeTransport()
        resolver(transport, limits = NativeDnsLimits(maxInFlight = 1, maxWaitersPerFlight = 2)).use { resolver ->
            val one = resolver.resolve("example.test")
            val two = resolver.resolve("example.test")
            failure<NativeDnsCapacityException>(resolver.resolve("example.test"))
            failure<NativeDnsCapacityException>(resolver.resolve("other.test"))
            one.cancel(true)
            val replacement = resolver.resolve("example.test")
            transport.answer(0, first)
            assertEquals(first, two.await().selectedIp)
            assertEquals(first, replacement.await().selectedIp)
            val other = resolver.resolve("other.test")
            transport.answer(1, second)
            assertEquals(second, other.await().selectedIp)
        }
    }

    @Test fun shutdownReleasesBlockedCallersCancelsTransportAndIsIdempotent() {
        val transport = FakeTransport()
        val resolver = resolver(transport)
        val firstWaiter = resolver.resolve("one.test")
        val secondWaiter = resolver.resolve("one.test")
        val thirdWaiter = resolver.resolve("two.test")
        resolver.close()
        listOf(firstWaiter, secondWaiter, thirdWaiter).forEach { failure<NativeDnsClosedException>(it) }
        assertTrue(transport.calls.all { it.answer.isCancelled })
        assertEquals(NativeDnsResolver.State(0, 0, 0, true), resolver.state())
        failure<NativeDnsClosedException>(resolver.resolve("new.test"))
        resolver.close()
        assertEquals(1, transport.closed)
    }

    @Test fun shutdownDuringRankingCannotPublishLateResultOrRetainCache() {
        val transport = FakeTransport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val resolver = resolver(transport, order = NativeDnsCandidateOrder { _, ips ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            ips
        })
        try {
            val request = resolver.resolve("example.test")
            val completing = pool.submit { transport.answer(0, first) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            resolver.close()
            failure<NativeDnsClosedException>(request)
            assertEquals(0, resolver.state().entries)
            release.countDown()
            completing.get(3, TimeUnit.SECONDS)
            assertEquals(0, resolver.state().entries)
        } finally {
            release.countDown()
            resolver.close()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun thirdIpv6UnreachableEventInvalidatesEpochAndFiltersFutureAnswers() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            val firstResult = resolver.resolve("example.test", NativeDnsFamily.ANY)
            transport.answer(0, v6, first)
            assertEquals(v6, firstResult.await().selectedIp)
            repeat(5) { resolver.reportIpv6Unreachable(first) }
            resolver.reportIpv6Unreachable(v6)
            resolver.reportIpv6Unreachable(v6)
            assertEquals(1, resolver.state().entries)
            val pending = resolver.resolve("pending.test")
            resolver.reportIpv6Unreachable(v6)
            failure<NativeDnsInvalidatedException>(pending)
            assertFalse(resolver.feedback(firstResult.await(), true))
            val refreshed = resolver.resolve("example.test", NativeDnsFamily.ANY)
            transport.answer(2, v6, first)
            assertEquals(listOf(first), refreshed.await().addresses)
        }
    }

    @Test fun callerProvidedIpv6UnavailableFiltersPresetsAndDnsWithoutOsAccess() {
        val transport = FakeTransport()
        resolver(transport, available = false, presets = mapOf("preset.test" to listOf(v6, first))).use { resolver ->
            val preset = resolver.resolve("preset.test").await()
            assertEquals(listOf(first), preset.addresses)
            val normal = resolver.resolve("example.test", NativeDnsFamily.ANY)
            transport.answer(0, v6, second)
            assertEquals(listOf(second), normal.await().addresses)
        }
    }

    @Test fun feedbackFromAnotherResolverCannotAlterSameKey() {
        val leftTransport = FakeTransport()
        val rightTransport = FakeTransport()
        resolver(leftTransport).use { left ->
            resolver(rightTransport).use { right ->
                val leftResult = warm(left, leftTransport)
                warm(right, rightTransport)
                assertFalse(right.feedback(leftResult, false))
                assertEquals(first, right.resolve("example.test").await().selectedIp)
            }
        }
    }

    @Test fun zeroAddressesRemainDnsCandidatesButAnInjectedProbeCanRejectThem() {
        val transport = FakeTransport()
        resolver(transport, order = NativeDnsCandidateOrder { _, ips ->
            com.dsha.sidecar.net.dns.IpCandidatePolicy.speedCandidates(ips)
        }).use { resolver ->
            val request = resolver.resolve("example.test", NativeDnsFamily.ANY)
            transport.answer(0, "0.0.0.0", "::", second)
            assertEquals(listOf(second), request.await().addresses)
            assertEquals(second, request.await().selectedIp)
        }
    }

    @Test fun invalidationWhileRankingDoesNotPoisonReplacementFlight() {
        val transport = FakeTransport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val resolver = resolver(transport, order = NativeDnsCandidateOrder { query, ips ->
            if (ips == listOf(first)) {
                assertEquals("example.test", query.hostname)
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            ips
        })
        try {
            val old = resolver.resolve("example.test")
            val completing = pool.submit { transport.answer(0, first) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            resolver.invalidate("example.test")
            failure<NativeDnsInvalidatedException>(old)
            val replacement = resolver.resolve("example.test")
            transport.answer(1, second)
            assertEquals(second, replacement.await().selectedIp)
            release.countDown()
            completing.get(3, TimeUnit.SECONDS)
            assertEquals(second, resolver.resolve("example.test").await().selectedIp)
        } finally {
            release.countDown()
            resolver.close()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun invalidHostOrRuleFamilyFailsBeforeAnyTransportWork() {
        val transport = FakeTransport()
        resolver(transport, rules = mapOf("*" to mapOf("dnsName" to "a", "family" to 5))).use { resolver ->
            for (host in listOf("bad host.test", "bad.test:443", "example.test")) {
                try {
                    resolver.resolve(host)
                    fail("Expected validation failure for $host")
                } catch (_: IllegalArgumentException) {
                    assertTrue(transport.calls.isEmpty())
                    assertEquals(0, resolver.state().flights)
                }
            }
        }
    }

    @Test fun presetCacheExpiryAllowsRetryAndRetainsNoTransportWork() {
        val transport = FakeTransport()
        val clock = Clock()
        resolver(transport, clock, presets = mapOf("preset.test" to listOf(first)),
            limits = NativeDnsLimits(presetTtlMillis = 20)).use { resolver ->
            val initial = resolver.resolve("preset.test").await()
            resolver.feedback(initial, false)
            assertTrue(resolver.resolve("preset.test").await().hostnameFallback)
            clock.now += 20
            val refreshed = resolver.resolve("preset.test").await()
            assertFalse(refreshed.cached)
            assertEquals(first, refreshed.selectedIp)
            assertFalse(resolver.feedback(initial, true))
            assertTrue(transport.calls.isEmpty())
        }
    }

    @Test fun port80FeedbackAndExhaustionCannotChangePort443Candidates() {
        val transport = FakeTransport()
        val seenPorts = mutableListOf<Int>()
        val ordering = object : NativeDnsCandidateOrder {
            override fun order(query: NativeDnsQuery, addresses: List<String>) = addresses
            override fun order(
                query: NativeDnsQuery,
                addresses: List<String>,
                destinationPort: Int,
            ): List<String> {
                seenPorts.add(destinationPort)
                return if (destinationPort == 80) addresses.reversed() else addresses
            }
        }
        resolver(transport, order = ordering).use { resolver ->
            val http = resolver.resolve("example.test", destinationPort = 80)
            val https = resolver.resolve("example.test")
            val explicitHttps = resolver.resolve("example.test", destinationPort = 443)
            assertEquals(2, transport.calls.size)
            assertEquals(transport.calls[0].query, transport.calls[1].query)
            assertEquals(3, resolver.state().waiters)
            transport.answer(0, first, second)
            transport.answer(1, first, second)
            assertEquals(listOf(80, 443), seenPorts)
            assertEquals(80, http.await().destinationPort)
            assertEquals(443, https.await().destinationPort)
            assertEquals(first, explicitHttps.await().selectedIp)
            assertEquals(second, http.await().selectedIp)
            resolver.feedback(http.await(), false)
            val httpSecond = resolver.resolve("example.test", destinationPort = 80).await()
            assertEquals(first, httpSecond.selectedIp)
            assertEquals(listOf(first, "example.test"), httpSecond.candidateOrder)
            resolver.feedback(httpSecond, false)
            val httpFallback = resolver.resolve("example.test", destinationPort = 80).await()
            assertTrue(httpFallback.hostnameFallback)
            resolver.feedback(httpFallback, false)
            val exhausted = resolver.resolve("example.test", destinationPort = 80).await()
            assertEquals(NativeDnsStatus.EXHAUSTED, exhausted.status)
            val unaffected = resolver.resolve("example.test", destinationPort = 443).await()
            assertEquals(first, unaffected.selectedIp)
            assertEquals(listOf(first, second, "example.test"), unaffected.candidateOrder)
            assertTrue(resolver.feedback(https.await(), true))
            assertEquals(2, transport.calls.size)
            assertTrue(resolver.reset(exhausted))
            assertEquals(second, resolver.resolve("example.test", destinationPort = 80).await().selectedIp)
            assertEquals(first, resolver.resolve("example.test").await().selectedIp)
        }
    }

    @Test fun hostnameInvalidationClearsCachedAndPendingPortsButPreservesOtherHosts() {
        val transport = FakeTransport().apply { ignoreCancel = true }
        resolver(transport).use { resolver ->
            val http = resolver.resolve("example.test", destinationPort = 80)
            val https = resolver.resolve("example.test")
            transport.answer(0, first)
            transport.answer(1, second)
            val unrelated = warm(resolver, transport, "other.test")
            val pending8080 = resolver.resolve("example.test", destinationPort = 8080)
            val pending8443 = resolver.resolve("example.test", destinationPort = 8443)
            assertEquals(3, resolver.state().entries)
            assertEquals(2, resolver.state().flights)
            resolver.invalidate("EXAMPLE.TEST.")
            assertFalse(resolver.feedback(http.await(), true))
            assertFalse(resolver.feedback(https.await(), true))
            failure<NativeDnsInvalidatedException>(pending8080)
            failure<NativeDnsInvalidatedException>(pending8443)
            transport.answer(3, first)
            transport.answer(4, second)
            assertEquals(1, resolver.state().entries)
            assertEquals(0, resolver.state().flights)
            assertTrue(resolver.feedback(unrelated, true))
            val retry80 = resolver.resolve("example.test", destinationPort = 80)
            val retry443 = resolver.resolve("example.test")
            assertEquals(7, transport.calls.size)
            transport.answer(5, first)
            transport.answer(6, second)
            assertFalse(retry80.await().cached)
            assertFalse(retry443.await().cached)
        }
    }

    @Test fun destinationPortValidationPrecedesTransportAndIncludesBoundaryPorts() {
        val transport = FakeTransport()
        resolver(transport).use { resolver ->
            for (port in listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE)) {
                try {
                    resolver.resolve("example.test", destinationPort = port)
                    fail("Expected invalid port rejection: $port")
                } catch (_: IllegalArgumentException) {
                    assertTrue(transport.calls.isEmpty())
                    assertEquals(0, resolver.state().flights)
                }
            }
            assertEquals(1, resolver.resolve(first, destinationPort = 1).await().destinationPort)
            assertEquals(65535, resolver.resolve(first, destinationPort = 65535).await().destinationPort)
            assertTrue(transport.calls.isEmpty())
        }
    }
}
