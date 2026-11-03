package com.dsha.sidecar.net.rules.native

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RulePlannerTest {
    private fun planner(config: Map<String, Any?>): RulePlanner =
        RulePlanner(listOf(mapOf("server" to mapOf("intercepts" to mapOf("example.test" to mapOf(".*" to config))))))

    @Test fun `unknown enabled actions are explicit and reject supported-plan guard`() {
        val plan = planner(mapOf("newAction" to mapOf("enabled" to true))).plan("example.test", "/")
        assertTrue(plan.hasBlockingIssues)
        assertEquals("newAction", plan.issues.single().action)
        assertThrows(UnsupportedRulePlanException::class.java) { plan.requireSupported() }
    }

    @Test fun `unknown disabled actions do not activate and status has no standalone implementation`() {
        assertFalse(planner(mapOf("newAction" to false)).plan("example.test", "/").hasBlockingIssues)
        val status = planner(mapOf("status" to 204)).plan("example.test", "/")
        assertNull(status.localResponse)
        assertTrue(status.issues.any { it.action == "status" })
        assertEquals(204, planner(mapOf("success" to mapOf("status" to 204))).plan("example.test", "/").localResponse?.status)
    }

    @Test fun `scripts are descriptors with blocking issues rather than silent response no-op`() {
        val plan = planner(mapOf("script" to listOf("custom"), "tampermonkeyScript" to "/monkey.js"))
            .plan("example.test", "/")
        assertTrue(plan.responseSteps.any { it.action is ResponseAction.ScriptInjection })
        assertTrue(plan.issues.any { it.action == "script" })
        assertTrue(plan.issues.any { it.action == "tampermonkeyScript" })
        assertThrows(UnsupportedRulePlanException::class.java) { plan.requireSupported() }
    }

    @Test fun `body options are retained as unsupported descriptors and reported`() {
        val plan = planner(mapOf("responseReplace" to mapOf("body" to "new body"))).plan("example.test", "/")
        val body = plan.responseSteps.map { it.action }.filterIsInstance<ResponseAction.UnsupportedBody>().single()
        assertEquals("new body", body.configuration["body"])
        assertTrue(plan.issues.any { it.action == "responseReplace.body" })
    }

    @Test fun `inline proxy SNI on ECH reports source exception and blocks execution guard`() {
        val planner = RulePlanner(listOf(mapOf("server" to mapOf(
            "dns" to mapOf("ech" to mapOf("domains" to listOf("example.test"))),
            "intercepts" to mapOf("example.test" to mapOf(".*" to mapOf("proxy" to "mirror.test", "sni" to "front.test"))),
        ))))
        val plan = planner.plan("example.test", "/")
        assertTrue(plan.echRequired)
        assertTrue(plan.issues.any { it.kind == IssueKind.ECH_PROXY_SNI_CONFLICT })
        assertEquals(ServerName.Send("front.test"), plan.tls.serverName)
    }

    @Test fun `SNI trust compatibility is per request data and suppression retains default trust`() {
        val rewritten = planner(mapOf("sni" to "front.test")).plan("example.test", "/")
        assertEquals(TrustMode.UPSTREAM_COMPAT_UNVERIFIED, rewritten.tls.trustMode)
        assertEquals(listOf(TrustReason.SNI_IMPLEMENTATION), rewritten.tls.compatibilityReasons)
        assertEquals("example.test", rewritten.tls.verificationHost)
        val suppressed = planner(mapOf("sni" to "", "verifyHost" to "cert.test")).plan("example.test", "/")
        assertEquals(ServerName.Suppress, suppressed.tls.serverName)
        assertEquals(TrustMode.VERIFY_CHAIN_AND_HOST, suppressed.tls.trustMode)
        assertEquals("cert.test", suppressed.tls.verificationHost)
    }

    @Test fun `cache retry and backup are explicit deferred descriptors`() {
        val plan = planner(mapOf(
            "cacheDays" to 2, "retry" to mapOf("times" to 2, "methods" to listOf("POST")),
            "proxy" to "mirror.test", "backup" to listOf("backup.test"),
        )).plan("example.test", "/upload", "POST", mapOf("content-length" to "20"))
        val cache = plan.requestSteps.map { it.action }.filterIsInstance<RequestAction.CacheLookup>().single()
        assertEquals(172800.0, cache.descriptor.maxAgeSeconds, 0.0)
        assertTrue(cache.descriptor.requiresExecutionTime)
        val proxy = plan.requestSteps.map { it.action }.filterIsInstance<RequestAction.Proxy>().single()
        assertEquals(listOf("backup.test"), proxy.backup?.alternatives)
        assertTrue(proxy.backup!!.requiresRuntimeRanking)
        assertTrue(plan.retry!!.requestBodyEligible)
        assertTrue(plan.retry.requiresSuccessfulBodyBuffering)
    }

    @Test fun `retry request body eligibility is not a promise of buffered execution`() {
        val plan = planner(mapOf("retry" to mapOf("times" to 2, "methods" to listOf("POST"))))
            .plan("example.test", "/upload", "POST", mapOf("transfer-encoding" to "chunked"))
        assertFalse(plan.retry!!.requestBodyEligible)
    }

    @Test fun `options Date is deferred and no-origin options can forward`() {
        val rules = planner(mapOf("options" to true, "proxy" to "mirror.test"))
        val cors = rules.plan("example.test", "/", "OPTIONS", mapOf("origin" to "https://caller.test"))
        assertTrue(cors.localResponse!!.addDateAtExecution)
        assertFalse(cors.localResponse.headers.containsKey("Date"))
        val forward = rules.plan("example.test", "/", "OPTIONS")
        assertNull(forward.localResponse)
        assertEquals("mirror.test", forward.routeOverride?.host)
    }

    @Test fun `invalid values and invalid matching patterns are surfaced`() {
        val bad = planner(mapOf("sni" to true, "requestReplace" to mapOf("headers" to 7))).plan("example.test", "/")
        assertTrue(bad.hasBlockingIssues)
        val malformed = RulePlanner(listOf(mapOf("intercepts" to mapOf("example.test" to mapOf("[" to mapOf("abort" to true))))))
        assertTrue(malformed.audit().hasBlockingIssues)
        assertTrue(malformed.plan("example.test", "/").issues.any { it.kind == IssueKind.UNSUPPORTED_PATTERN })
        val invalidTarget = planner(mapOf("proxy" to "https://invalid host.test/")).plan("example.test", "/")
        assertTrue(invalidTarget.issues.any { it.kind == IssueKind.UNSUPPORTED_VALUE })
    }

    @Test fun `audit sees unmatched enabled actions without poisoning unrelated requests`() {
        val rules = planner(mapOf("script" to "custom"))
        assertTrue(rules.audit().hasBlockingIssues)
        assertFalse(rules.plan("other.test", "/").hasBlockingIssues)
    }

    @Test fun `global insertion middleware is explicitly unsupported`() {
        val rules = RulePlanner(listOf(mapOf("server" to mapOf("setting" to mapOf("script" to mapOf("enabled" to true))))))
        assertTrue(rules.audit().issues.any { it.kind == IssueKind.GLOBAL_SCRIPT_MIDDLEWARE })
        assertTrue(rules.plan("other.test", "/").hasBlockingIssues)
    }

    @Test fun `planning snapshots configuration and does not mutate caller request headers`() {
        val rule = mutableMapOf<String, Any?>("proxy" to "mirror.test")
        val rules = planner(rule)
        rule["proxy"] = "changed.test"
        val headers = mutableMapOf<String, Any?>("host" to "example.test", "x-sentinel" to "unchanged")
        val result = rules.plan("example.test", "/a", headers = headers)
        assertEquals("mirror.test", result.routeOverride?.host)
        assertEquals("example.test", headers["host"])
        (result.requestHeaders as? MutableMap<String, Any?>)?.set("host", "caller-mutated.test")
        assertEquals("mirror.test", rules.plan("example.test", "/a", headers = headers).requestHeaders["host"])
    }

    @Test fun `parallel requests retain their own captures and headers`() {
        val rules = RulePlanner(listOf(mapOf("intercepts" to mapOf(
            "^(?<site>[a-z]+)\\.test$" to mapOf("^/([0-9]+)" to mapOf("proxy" to "https://mirror.test/\${site}/\${p[1]}")),
        ))))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..12).map { number ->
                pool.submit(Callable { rules.plan("abc.test", "/$number").routeOverride!!.path })
            }
            assertEquals((1..12).map { "/abc/$it" }, results.map { it.get(5, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `named captures use indexed groups with optional groups and lookbehind`() {
        val pattern = "(unrelated)(?:x)(?<first>a)(?<=a)(?<optional>b)?(?<last>c)"
        assertEquals(
            mapOf("first" to "a", "last" to "c"),
            PlannerJson.namedCaptures("unrelatedxac", pattern),
        )
        assertEquals(
            "https://mirror.test/a//c",
            RuleTargets.build(
                RuleRequest("example.test", "/unrelatedxac", scheme = "http"),
                "/unrelatedxac",
                "https://mirror.test/\$<first>/\$<optional>/\$<last>",
                mapOf("replace" to "/$pattern"),
                SelectedImplementation("proxy", 0, "", 0.0, emptyList(), null, null, null),
            ),
        )
    }

    @Test fun `named group indexes ignore quoted groups and comments`() {
        assertEquals(
            mapOf("first" to "a", "last" to "c"),
            PlannerJson.namedCaptures("()ac", "(?x)\\Q()\\E # (ignored)\n(?<first>a)(?<last>c)"),
        )
        assertEquals(
            mapOf("first" to "a", "last" to "c"),
            PlannerJson.namedCaptures("ac", "(?x:# (ignored)\n(?<first>a))(?<last>c)"),
        )
    }

    @Test fun `JSON validation rejects cycles nonfinite values and excessive request size`() {
        val cycle = mutableMapOf<String, Any?>()
        cycle["self"] = cycle
        assertThrows(IllegalArgumentException::class.java) { RulePlanner(listOf(cycle)) }
        assertThrows(IllegalArgumentException::class.java) { RulePlanner(listOf(mapOf("bad" to Double.NaN))) }
        assertThrows(IllegalArgumentException::class.java) { RulePlanner(emptyList()).plan("example.test", "/".repeat(8193)) }
        assertThrows(IllegalArgumentException::class.java) { RulePlanner(listOf(mapOf("server" to 7))) }
        assertThrows(IllegalArgumentException::class.java) { RulePlanner(listOf(mapOf("intercepts" to listOf("bad")))) }
    }
}
