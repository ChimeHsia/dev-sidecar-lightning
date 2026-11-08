package com.dsha.sidecar.net.http.native.script

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.ResponseAction
import com.dsha.sidecar.net.rules.native.RuleRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ScriptOracleTest(private val id: String, private val fixture: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    @Test fun originalSourceEvidenceAndExplicitSafetyDifferences() {
        val expected = obj(fixture["expected"])
        when (fixture["type"]) {
            "compile" -> assertEquals(
                expected["script"], UserScriptCompiler.compile(fixture["source"] as String, fixture["key"] as String),
            )
            "route" -> {
                val decision = ScriptRouteService(syntheticCatalog()).serve(
                    RuleRequest("example.test", fixture["path"] as String), true, 1_793_577_600_000,
                )
                if (expected["handled"] == false) assertEquals(ScriptRouteDecision.NotHandled, decision)
                else {
                    val response = (decision as ScriptRouteDecision.Served).response
                    assertEquals((expected["status"] as Number).toInt(), response.head.status)
                    assertEquals(expected["body"], response.bodyBytes().toString(Charsets.UTF_8))
                    val actualHeaders = response.head.headers.associate { it.name to it.value }
                    obj(expected["headers"]).forEach { (name, value) -> assertEquals(value, actualHeaders[name]) }
                    assertEquals(response.bodySize.toString(), scriptHeader(response.head, "content-length"))
                }
            }
            "inject" -> {
                val catalog: ScriptCatalog = if (fixture["failCatalog"] == true) ScriptCatalog { throw IllegalStateException("private detail") }
                    else syntheticCatalog(fixture["noBootstrap"] == true)
                val references = ScriptReferencePolicy(setOf(
                    "/scripts/alpha.js", "/bootstrap.js", "https://scripts.test/alpha.js", ScriptTagPlanner.GITHUB_COMPAT_URL,
                ))
                val planner = ScriptTagPlanner(catalog, references, cspMode = ScriptCspMode.EXPLICIT_UPSTREAM_SELF_COMPAT)
                val request = RuleRequest(fixture["host"] as? String ?: "example.test", "/",
                    headers = fixture["turbo"]?.let { mapOf("turbo-frame" to it) } ?: emptyMap())
                val headers = (expected["inputHeaders"] as List<*>).map {
                    val pair = it as List<*>
                    ActionHeader(pair[0] as String, pair[1] as String)
                }
                val head = ActionResponseHead((expected["status"] as Number).toInt(), headers)
                val tags = planner.plan(request, head, ResponseAction.ScriptInjection(fixture["script"], fixture["tampermonkeyScript"]), true)
                if (fixture["failCatalog"] == true || fixture["noBootstrap"] == true) {
                    assertEquals("error", expected["diagnostics"])
                    assertTrue(tags is ScriptTagDecision.Blocked)
                    return
                }
                if (expected["tags"] == null) {
                    assertTrue(tags is ScriptTagDecision.Skipped)
                    val source = object : java.io.InputStream() { override fun read(): Int = error("Skipped input must remain unread") }
                    assertTrue(HtmlScriptInjector().prepare(request, head, tags, source) is ScriptResponseDecision.PassThrough)
                    return
                }
                assertEquals(expected["tags"], (tags as ScriptTagDecision.Ready).headText)
                val input = Base64.getDecoder().decode(expected["inputBase64"] as String)
                val result = HtmlScriptInjector().prepare(request, head, tags, ByteArrayInputStream(input))
                val response = (result as ScriptResponseDecision.Prepared).response
                val body = when (fixture["encoding"]) {
                    "gzip" -> GZIPInputStream(response.bodyBytes().inputStream()).use { it.readBytes() }
                    "deflate" -> InflaterInputStream(response.bodyBytes().inputStream()).use { it.readBytes() }
                    else -> response.bodyBytes()
                }
                assertArrayEquals(Base64.getDecoder().decode(expected["decodedBase64"] as String), body)
                obj(expected["outputHeaders"]).forEach { (name, value) ->
                    if (fixture["encoding"] == "br" && name.equals("content-encoding", true)) {
                        assertNull(scriptHeader(response.head, name))
                    } else assertEquals(value, scriptHeader(response.head, name))
                }
                assertEquals(response.bodySize.toString(), scriptHeader(response.head, "content-length"))
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any?>> = UpstreamScriptOracle.cases.map { arrayOf(it["id"], it) }
    }
}
