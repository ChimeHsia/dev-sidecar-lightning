package com.dsha.sidecar.net.http.native

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import com.dsha.sidecar.net.rules.native.RulePlanner
import com.dsha.sidecar.net.rules.native.RuleRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RuleActionOracleTest(private val id: String, private val fixture: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    @Test fun originalFunctionsMatchHeadersAndBodyOnWire() {
        val expected = obj(fixture["expected"])
        val input = obj(expected["request"])
        val request = RuleRequest(
            input["host"] as String, input["path"] as String, input["method"] as String, obj(input["headers"]),
        )
        val planner = RulePlanner(listOf(mapOf("intercepts" to mapOf(
            request.host to mapOf(".*" to obj(fixture["config"])),
        ))))
        val plan = planner.plan(request)
        val rawHeaders = (expected["inputHeaders"] as List<*>).map { entry ->
            val pair = entry as List<*>
            ActionHeader(pair[0] as String, pair[1] as String)
        }
        val body = Base64.getDecoder().decode(expected["inputBase64"] as String)
        val append = obj(fixture["append"]).takeIf { it.isNotEmpty() }?.let {
            ResolvedHtmlAppend(it["head"] as? String ?: "", it["body"] as? String ?: "")
        }
        val head = ActionResponseHead((expected["status"] as Number).toInt(), rawHeaders, "Fixture")
        val executor = RuleActionExecutor()
        val prepared = executor.prepareResponse(plan, request, head, ByteArrayInputStream(body), fixture["gate"] != false, append)
        val actualHeaders = prepared.head.headers.associate { it.name.lowercase() to it.value }
        val expectedHeaders = obj(expected["headers"]).mapValues { it.value.toString() }.toMutableMap()
        expectedHeaders.remove("content-length")
        assertEquals(id, expectedHeaders, actualHeaders.filterKeys { it != "content-length" })
        assertEquals(prepared.bodySize.toString(), actualHeaders["content-length"])
        val result = prepared.bodyBytes()
        if (expected["encodedUnchanged"] == true) {
            assertArrayEquals(body, result)
        } else {
            val decoded = when (fixture["encoding"]) {
                "gzip" -> GZIPInputStream(ByteArrayInputStream(result)).use { it.readBytes() }
                "deflate" -> InflaterInputStream(ByteArrayInputStream(result)).use { it.readBytes() }
                else -> result
            }
            assertArrayEquals(Base64.getDecoder().decode(expected["decodedBase64"] as String), decoded)
        }
        val sink = ByteArrayOutputStream()
        prepared.writeHttp1(sink)
        val wire = sink.toByteArray()
        val prefix = wire.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        assertTrue(prefix >= 4)
        assertArrayEquals(result, wire.copyOfRange(prefix, wire.size))
        if (append == null) {
            val streaming = executor.transformResponseHead(plan, request, head, fixture["gate"] != false)
            assertEquals(body.size.toString(), streaming.head.headers.single { it.name.equals("content-length", true) }.value)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any?>> = UpstreamActionOracle.cases.map { arrayOf(it["id"], it) }
    }
}
