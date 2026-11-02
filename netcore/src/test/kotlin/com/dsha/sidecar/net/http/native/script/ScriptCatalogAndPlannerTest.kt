package com.dsha.sidecar.net.http.native.script

import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.rules.native.ResponseAction
import org.junit.Assert.*
import org.junit.Test

class ScriptCatalogAndPlannerTest {
    private val action = ResponseAction.ScriptInjection(listOf("alpha"), null)
    private val request = scriptRequest()
    private val head = scriptHead()

    @Test fun `catalog snapshots sources and returned byte arrays cannot mutate assets`() {
        val sources = mutableMapOf("alpha" to CatalogSource("synthetic"))
        val catalog = ReadOnlyScriptCatalog(sources)
        sources["alpha"] = CatalogSource("changed")
        catalog.lookup("alpha")!!.bytes().fill(0)
        assertEquals("synthetic", catalog.lookup("alpha")!!.bytes().toString(Charsets.UTF_8))
    }

    @Test fun `catalog rejects traversal invalid Unicode and size or entry overflow`() {
        for (key in listOf("../x", "a/b", "__proto__", "constructor", "%2e%2e")) {
            assertThrows(ScriptInjectionException::class.java) { ReadOnlyScriptCatalog(mapOf(key to CatalogSource("x"))) }
        }
        assertThrows(ScriptInjectionException::class.java) { ReadOnlyScriptCatalog(mapOf("x" to CatalogSource("\ud800"))) }
        assertThrows(ScriptInjectionException::class.java) { ReadOnlyScriptCatalog(mapOf("x" to CatalogSource("xx")), ScriptLimits(maximumAssetBytes = 1)) }
        assertThrows(ScriptInjectionException::class.java) {
            ReadOnlyScriptCatalog(mapOf("a" to CatalogSource("x"), "b" to CatalogSource("x")), ScriptLimits(maximumCatalogEntries = 1))
        }
    }

    @Test fun `external and relative script references require exact authorization`() {
        val planner = ScriptTagPlanner(syntheticCatalog())
        for (reference in listOf("https://scripts.test/x.js", "/safe/x.js", "//evil.test/x.js", "http://scripts.test/x.js")) {
            assertTrue(planner.plan(request, head, ResponseAction.ScriptInjection(listOf(reference), null), true) is ScriptTagDecision.Blocked)
        }
        val allowed = ScriptTagPlanner(syntheticCatalog(), ScriptReferencePolicy(setOf("/safe/x.js")))
        assertTrue(allowed.plan(request, head, ResponseAction.ScriptInjection(listOf("/safe/x.js"), null), true) is ScriptTagDecision.Ready)
    }

    @Test fun `attribute injection traversal credentials encoded controls and unsafe schemes are refused`() {
        for (reference in listOf(
            "https://user:synthetic@evil.test/x.js", "https://scripts.test/x.js\" onload=\"x", "/x'><script>", "/../x",
            "/%2e%2e/x", "/x%0a.js", "/x%2Fsecret", "/x&nonce=a", "javascript:alert(1)", "data:text/javascript,alert(1)",
            "\\\\evil.test\\x", "/x#fragment", "https://scripts.test:8443/x",
        )) assertThrows("Unsafe reference accepted", ScriptInjectionException::class.java) { ScriptReferencePolicy(setOf(reference)) }
    }

    @Test fun `script ordering duplicates optional misses reserved keys and bootstrap match source`() {
        val planner = ScriptTagPlanner(syntheticCatalog())
        val tags = planner.plan(request, head, ResponseAction.ScriptInjection(listOf("missing", "global", "beta", "alpha", "beta", "tampermonkey"), null), true) as ScriptTagDecision.Ready
        assertTrue(tags.headText.indexOf("/tampermonkey") < tags.headText.indexOf("/beta"))
        assertTrue(tags.headText.indexOf("/beta") < tags.headText.indexOf("/alpha"))
        assertEquals(2, Regex("/beta").findAll(tags.headText).count())
        assertEquals(listOf(ScriptNotice.MISSING_OPTIONAL_ASSET), tags.notices)
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.MISSING_ASSET),
            ScriptTagPlanner(syntheticCatalog(true)).plan(request, head, action, true))
    }

    @Test fun `script configuration type errors and count cap are explicit`() {
        val planner = ScriptTagPlanner(syntheticCatalog(), limits = ScriptLimits(maximumScriptsPerResponse = 1))
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.BODY_LIMIT), planner.plan(request, head, ResponseAction.ScriptInjection(listOf("alpha", "beta"), null), true))
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.INVALID_CONFIGURATION), planner.plan(request, head, ResponseAction.ScriptInjection(true, null), true))
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.INVALID_CONFIGURATION), planner.plan(request, head, ResponseAction.ScriptInjection(listOf(1), null), true))
    }

    @Test fun `catalog failures are typed and never expose private exception messages`() {
        val planner = ScriptTagPlanner(ScriptCatalog { throw IllegalStateException("SECRET-NOT-LOGGED") })
        val result = planner.plan(request, head, action, true)
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.CATALOG_FAILURE), result)
        assertFalse(result.toString().contains("SECRET"))
    }

    @Test fun `disabled non-html XHTML and GitHub turbo bypass explicitly`() {
        val planner = ScriptTagPlanner(syntheticCatalog())
        assertEquals(ScriptTagDecision.Skipped(ScriptSkip.DISABLED), planner.plan(request, head, action, false))
        val turbo = request.copy(host = "github.com", headers = mapOf("turbo-frame" to "repo-content-turbo-frame"))
        assertEquals(ScriptTagDecision.Skipped(ScriptSkip.GITHUB_TURBO_FRAME), planner.plan(turbo, head, action, true))
        for (type in listOf("application/xhtml+xml", "application/json", "TEXT/HTML")) {
            val other = head.copy(headers = listOf(ActionHeader("Content-Type", type)))
            assertEquals(ScriptSkip.NON_HTML, (planner.plan(request, other, action, true) as ScriptTagDecision.Skipped).reason)
        }
    }

    @Test fun `GitHub compatibility URL remains explicit allowlist choice`() {
        val github = request.copy(host = "github.com")
        assertTrue(ScriptTagPlanner(syntheticCatalog()).plan(github, head, action, true) is ScriptTagDecision.Blocked)
        val allowed = ScriptTagPlanner(syntheticCatalog(), ScriptReferencePolicy(setOf(ScriptTagPlanner.GITHUB_COMPAT_URL)))
        val tags = allowed.plan(github, head, action, true) as ScriptTagDecision.Ready
        assertTrue(tags.headText.indexOf("/alpha") < tags.headText.indexOf("fakefile.js"))
    }

    @Test fun `nonce validation blocks HTML metacharacters and ambiguous policies`() {
        val planner = ScriptTagPlanner(syntheticCatalog())
        for (policy in listOf("script-src 'nonce-abc&xyz'", "script-src \"nonce-abc<xyz\"")) {
            assertEquals(ScriptTagDecision.Blocked(ScriptProblem.UNSAFE_NONCE),
                planner.plan(request, scriptHead(extra = listOf(ActionHeader("Content-Security-Policy", policy))), action, true))
        }
        val duplicate = scriptHead(extra = listOf(ActionHeader("Content-Security-Policy", "script-src 'self'"), ActionHeader("Content-Security-Policy", "script-src 'none'")))
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.BLOCKING_CSP), planner.plan(request, duplicate, action, true))
    }

    @Test fun `default CSP policy preserves security rather than silently adding self`() {
        val planner = ScriptTagPlanner(syntheticCatalog())
        val blocked = scriptHead(extra = listOf(ActionHeader("Content-Security-Policy", "default-src 'none'")))
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.BLOCKING_CSP), planner.plan(request, blocked, action, true))
        val sandbox = scriptHead(extra = listOf(ActionHeader("Content-Security-Policy", "sandbox; script-src 'self'")))
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.BLOCKING_CSP), planner.plan(request, sandbox, action, true))
        val nonce = scriptHead(extra = listOf(ActionHeader("Content-Security-Policy", "script-src 'nonce-Abc012=' 'strict-dynamic'")))
        assertTrue(planner.plan(request, nonce, action, true) is ScriptTagDecision.Ready)
    }

    @Test fun `userscript wrapper supports safe grants and rejects invalid metadata or executable grant names`() {
        val source = "// @name Synthetic\n// @version 1\n// @grant GM_getValue\n// ==/UserScript==\nwindow.test=true;"
        val catalog = ReadOnlyScriptCatalog(mapOf("test" to CatalogSource(source, CatalogFormat.USER_SCRIPT)))
        assertTrue(catalog.lookup("test")!!.bytes().toString(Charsets.UTF_8).contains("DOMContentLoaded"))
        for (bad in listOf(source.replace("@version 1", "@version bad\"code"), source.replace("GM_getValue", "x;evil()"), source.replace("GM_getValue", "none"), "no metadata delimiter")) {
            assertThrows(ScriptInjectionException::class.java) { UserScriptCompiler.compile(bad, "test") }
        }
    }

    @Test fun `route uses catalog only with bounded GET HEAD alias and controlled proxy bindings`() {
        val alias = "/____ds_script_proxy____/synthetic.js"
        val service = ScriptRouteService(syntheticCatalog(), mapOf(alias to "alpha"))
        for (path in listOf("/____ds_script____/alpha", alias)) {
            val get = (service.serve(request.copy(path = path), true, 1_793_577_600_000) as ScriptRouteDecision.Served).response
            val head = (service.serve(request.copy(path = path, method = "HEAD"), true, 1_793_577_600_000) as ScriptRouteDecision.Served).response
            assertEquals(200, get.head.status)
            assertEquals(0, head.bodySize)
            assertEquals(get.bodySize.toString(), scriptHeader(head.head, "content-length"))
            assertEquals("nosniff", scriptHeader(get.head, "x-content-type-options"))
        }
        val global = (service.serve(request.copy(path = "/____ds_script____/global"), true, 0) as ScriptRouteDecision.Served).response
        assertEquals("/* synthetic bootstrap */", global.bodyBytes().toString(Charsets.UTF_8))
    }

    @Test fun `route traversal query and encoded separators blocked before catalog callback`() {
        var calls = 0
        val service = ScriptRouteService(ScriptCatalog { calls++; null })
        for (path in listOf("/____ds_script____/../x", "/____ds_script____/%2e%2e", "/____ds_script____/alpha?x=1", "/____ds_script____/alpha/x", "/____ds_script____/alpha%2Fx")) {
            assertTrue(service.serve(request.copy(path = path), true, 0) is ScriptRouteDecision.Blocked)
        }
        assertEquals(0, calls)
        assertEquals(ScriptRouteDecision.NotHandled, service.serve(request.copy(path = "/other"), true, 0))
        assertEquals(ScriptRouteDecision.NotHandled, service.serve(request.copy(path = "/____ds_script____/alpha"), false, 0))
    }

    @Test fun `same host external URL on a different origin is not CSP self`() {
        val planner = ScriptTagPlanner(syntheticCatalog(), ScriptReferencePolicy(setOf("https://example.test/a.js")))
        val secured = scriptHead(extra = listOf(ActionHeader("Content-Security-Policy", "script-src 'self'")))
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.BLOCKING_CSP),
            planner.plan(request.copy(port = 8443), secured, ResponseAction.ScriptInjection(listOf("https://example.test/a.js"), null), true))
    }

    @Test fun `non GET route rejects methods and duplicate Content-Type cannot choose a parser`() {
        val served = ScriptRouteService(syntheticCatalog()).serve(request.copy(method = "POST", path = "/____ds_script____/alpha"), true, 0)
            as ScriptRouteDecision.Served
        assertEquals(405, served.response.head.status)
        assertEquals("GET, HEAD", scriptHeader(served.response.head, "allow"))
        val ambiguous = scriptHead(extra = listOf(ActionHeader("Content-Type", "text/plain")))
        assertEquals(ScriptTagDecision.Blocked(ScriptProblem.INVALID_HEADERS),
            ScriptTagPlanner(syntheticCatalog()).plan(request, ambiguous, action, true))
    }
}
