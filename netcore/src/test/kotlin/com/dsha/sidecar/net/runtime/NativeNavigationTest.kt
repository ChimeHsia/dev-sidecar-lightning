package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.config.native.BoundedJson5
import com.dsha.sidecar.net.http.native.script.CatalogSource
import com.dsha.sidecar.net.proxy.http.Http1ChunkMode
import com.dsha.sidecar.net.proxy.http.Http1Header
import com.dsha.sidecar.net.proxy.http.Http1Headers
import com.dsha.sidecar.net.proxy.http.Http1Reader
import com.dsha.sidecar.net.proxy.http.Http1RequestHead
import com.dsha.sidecar.net.proxy.http.Http1ResponseHead
import com.dsha.sidecar.net.proxy.http.Http1Target
import com.dsha.sidecar.net.proxy.native.NativeAuthority
import com.dsha.sidecar.net.proxy.native.NativeExchangePolicy
import com.dsha.sidecar.net.proxy.native.NativeProxyRequest
import com.dsha.sidecar.net.proxy.native.NativeProxyServer
import com.dsha.sidecar.net.proxy.native.NativePreparedResponse
import com.dsha.sidecar.net.proxy.native.NativeRequestDecision
import com.dsha.sidecar.net.proxy.native.NativeResponseEntity
import com.dsha.sidecar.net.proxy.native.RouteResolver
import com.dsha.sidecar.net.proxy.tls.EphemeralTestCa
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeNavigationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val defaults = """{
        "version": 1,
        "rules": [{
            "sourceHosts": ["google.cn", "www.google.cn"],
            "sourceSchemes": ["http", "https"],
            "targetOrigin": "https://www.google.com.hk"
        }]
    }"""

    private fun rules(value: String = defaults) = NativeNavigationRules.compile(BoundedJson5().parseObject(value))

    private fun request(
        path: String = "/search/a%2Fb?q=a%2Fb%20c",
        method: String = "GET",
        secure: Boolean = false,
        port: Int = if (secure) 443 else 80,
        headers: List<Http1Header> = listOf(Http1Header("Accept", " text/html")),
        host: String = "google.cn",
    ): NativeProxyRequest {
        val authority = NativeAuthority(host, port)
        return NativeProxyRequest(
            Http1RequestHead(method, Http1Target.Origin(path), "HTTP/1.1", Http1Headers(headers)),
            authority,
            secure,
            if (secure) authority else null,
        )
    }

    @Test
    fun redirectsDocumentsPreservingEscapedPathAndQuery() {
        assertEquals("https://www.google.com.hk/search/a%2Fb?q=a%2Fb%20c", rules().target(request()))
        assertEquals("https://www.google.com.hk/search/a%2Fb?q=a%2Fb%20c", rules().target(request(secure = true)))
        assertEquals("https://www.google.com.hk/b?x=1", rules().target(request(path = "/a/%2e%2e/b?x=1")))
        assertEquals("https://www.google.com.hk/", rules().target(request(path = "/")))
        assertEquals("https://www.google.com.hk/search/a%2Fb?q=a%2Fb%20c", rules().target(request(method = "HEAD")))
    }

    @Test
    fun acceptsRepositoryDefaultRules() {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { it.resolve("port/node/navigation-rules.json") }.first { it.isFile }
        val defaults = rules(file.readText())
        assertEquals("https://www.google.com.hk/", defaults.target(request(path = "/", host = "google.cn")))
        assertEquals("https://www.google.com/", defaults.target(request(path = "/", host = "google.com")))
        assertEquals("https://www.google.com.hk/", defaults.target(request(path = "/", host = "google.com.hk")))
        assertNull(defaults.target(request(path = "/", secure = true, host = "google.com")))
    }

    @Test
    fun rejectsNonDocumentOrUnsafeRequests() {
        assertNull(rules().target(request(method = "POST")))
        assertNull(rules().target(request(port = 8080)))
        assertNull(rules().target(request(path = "//attacker.test/")))
        assertNull(rules().target(request(path = "/\\attacker.test/")))
        assertNull(rules().target(request(headers = listOf(Http1Header("Accept", " application/json")))))
        assertNull(rules().target(request(headers = listOf(Http1Header("Sec-Fetch-Dest", " script"), Http1Header("Accept", " text/html")))))
        assertNull(rules().target(request(headers = listOf(
            Http1Header("Sec-Fetch-Dest", " script"),
            Http1Header("Sec-Fetch-Dest", " document"),
            Http1Header("Accept", " text/html"),
        ))))
        assertNull(rules().target(request(headers = listOf(Http1Header("Content-Length", " 1"), Http1Header("Accept", " text/html")))))
        assertNull(rules().target(request(headers = listOf(Http1Header("Transfer-Encoding", " chunked"), Http1Header("Accept", " text/html")))))
        assertTrue(rules().target(request(headers = listOf(Http1Header("Sec-Fetch-Dest", " iframe")))) != null)
    }

    @Test
    fun rejectsInvalidOrCyclicConfigurations() {
        for (value in listOf(
            """{"version":2,"rules":[]}""",
            """{"version":1,"rules":[{"sourceHosts":["127.0.0.1"],"sourceSchemes":["http"],"targetOrigin":"https://example.com"}]}""",
            """{"version":1,"rules":[{"sourceHosts":["google.cn"],"sourceSchemes":["http"],"targetOrigin":"https://example.com/path"}]}""",
            """{"version":1,"rules":[{"sourceHosts":["google.cn"],"sourceSchemes":["https"],"targetOrigin":"https://google.cn"}]}""",
            """{"version":1,"rules":[{"sourceHosts":["google.cn","google.cn"],"sourceSchemes":["http"],"targetOrigin":"https://example.com"}]}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { rules(value) }
        }
    }

    @Test
    fun reloadsAndRetainsLastValidRulesWithoutOverwritingExistingFile() {
        val file = temporaryFolder.root.resolve("nested/navigation.json")
        val store = NativeNavigationRuleStore(defaults, file)
        assertEquals(defaults, file.readText())
        val replacement = """{"version":1,"rules":[]}"""
        file.writeText(replacement)
        assertNull(store.getRules().target(request()))
        file.writeText("{invalid")
        assertNull(store.getRules().target(request()))
        file.writeText(defaults.replace("\"version\"", "version"))
        assertNull(store.getRules().target(request()))
        file.writeText("""{"version":1,"rules":[{"sourceHosts":["google.cn"],"sourceSchemes":["http"],"targetOrigin":"https://www.google.com.hk"},]}""")
        assertNull(store.getRules().target(request()))
        file.writeText(" ".repeat(65_537))
        assertNull(store.getRules().target(request()))
        file.writeText(defaults)
        assertTrue(store.getRules().target(request()) != null)
        file.delete()
        assertTrue(store.getRules().target(request()) != null)
        file.writeText(replacement)
        NativeNavigationRuleStore(defaults, file)
        assertEquals(replacement, file.readText())
    }

    @Test
    fun policyRespondsWith307AndDelegatesOtherRequests() {
        var forwarded = false
        val downstream = object : NativeExchangePolicy {
            override fun onRequest(request: NativeProxyRequest): NativeRequestDecision {
                forwarded = true
                return super.onRequest(request)
            }
        }
        val policy = NativeNavigationPolicy(downstream) { rules() }
        val redirect = policy.onRequest(request()) as NativeRequestDecision.LocalResponse
        assertEquals(307, redirect.head.status)
        assertEquals(listOf("https://www.google.com.hk/search/a%2Fb?q=a%2Fb%20c"), redirect.head.headers.values("Location"))
        assertEquals(listOf("no-store"), redirect.head.headers.values("Cache-Control"))
        assertFalse(forwarded)
        assertTrue(policy.onRequest(request(method = "POST")) is NativeRequestDecision.Forward)
        assertTrue(forwarded)
    }

    @Test
    fun policyDelegatesResponseEntityToDownstream() {
        var called = false
        val downstream = object : NativeExchangePolicy {
            override fun onResponseEntity(
                request: NativeProxyRequest, head: Http1ResponseHead, entity: NativeResponseEntity,
            ): NativePreparedResponse {
                called = true
                return NativePreparedResponse(head, entity.read(3))
            }
        }
        val head = Http1ResponseHead("HTTP/1.1", 200, "OK", Http1Headers(emptyList()))
        val result = NativeNavigationPolicy(downstream) { rules() }
            .onResponseEntity(request(host = "example.test"), head, NativeResponseEntity { "abc".toByteArray() })
        assertTrue(called)
        assertEquals("abc", result!!.body.toString(Charsets.US_ASCII))
    }

    @Test
    fun loopbackProxyRedirectsBeforeResolvingUpstream() {
        val resolutions = AtomicInteger()
        val policy = NativeNavigationPolicy(NativeExchangePolicy.Passthrough) { rules() }
        NativeProxyServer(
            RouteResolver { resolutions.incrementAndGet(); error("Unexpected upstream resolution") },
            null,
            exchangePolicy = policy,
        ).use { proxy ->
            val address = proxy.start()
            Socket(address.address, address.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(
                    "GET http://google.cn/search?q=a%2Fb HTTP/1.1\r\nHost: google.cn\r\nAccept: text/html\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1),
                )
                val response = Http1Reader(socket.getInputStream()).readResponse("GET")!!
                assertEquals(307, response.head.status)
                assertEquals(listOf("https://www.google.com.hk/search?q=a%2Fb"), response.head.headers.values("Location"))
                assertEquals(0, resolutions.get())
            }
        }
    }

    @Test
    fun runtimeReloadsNavigationAndServesAuthorizedScriptsWithoutDns() {
        val file = temporaryFolder.newFile("navigation.json")
        file.writeText(defaults)
        val store = NativeNavigationRuleStore(defaults, file)
        val config = mapOf("server" to mapOf(
            "dns" to mapOf("providers" to mapOf("fixture" to mapOf(
                "type" to "udp", "server" to "127.0.0.1", "port" to 9, "family" to 4,
            ))),
            "setting" to mapOf("script" to mapOf("enabled" to true)),
        ))
        val scripts = NativeRuntimeScriptRoutes(
            mapOf("sample" to CatalogSource("window.sample = true;")),
            setOf("http://google.cn"),
        )
        NativeRuntime.fromDecoded(
            config, EphemeralTestCa.create().authority(), navigationRules = store::getRules, scriptRoutes = scripts,
        ).use { runtime ->
            val address = runtime.start()
            for (destination in listOf("https://www.google.com.hk", "https://destination.test")) {
                file.writeText(defaults.replace("https://www.google.com.hk", destination))
                Socket(address.address, address.port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write(
                        "GET /search?q=%2F HTTP/1.1\r\nHost: google.cn\r\nAccept: text/html\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1),
                    )
                    val response = Http1Reader(socket.getInputStream()).readResponse("GET")!!
                    assertEquals(307, response.head.status)
                    assertEquals(listOf("$destination/search?q=%2F"), response.head.headers.values("Location"))
                }
            }
            Socket(address.address, address.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(
                    "GET /____ds_script____/sample HTTP/1.1\r\nHost: google.cn\r\nSec-Fetch-Dest: script\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1),
                )
                val reader = Http1Reader(socket.getInputStream())
                assertEquals(200, reader.readResponse("GET")!!.head.status)
                val body = ByteArrayOutputStream()
                reader.transferBody(body, Http1ChunkMode.DECODE)
                assertEquals("window.sample = true;", body.toString("UTF-8"))
            }
            assertEquals(0L, runtime.routes.state().selections)
        }
    }
}
