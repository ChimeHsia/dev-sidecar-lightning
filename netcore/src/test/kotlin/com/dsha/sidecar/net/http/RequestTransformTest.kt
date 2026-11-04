package com.dsha.sidecar.net.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTransformTest {
    private fun transform(
        url: String = "/",
        ssl: Boolean = false,
        method: String = "GET",
        headers: Map<String, Any?> = mapOf("host" to "example.test"),
    ): RequestTransform.Result = RequestTransform.transform(url, ssl, method, headers)

    @Test
    fun `origin and absolute paths preserve escapes query order and plus signs`() {
        val path = "/a%2fb/%25?q=a%20b&next=%2F%3F&plus=a+b&x=%23"
        assertEquals(path, transform(path).path)
        assertEquals(path, transform("http://url.test$path").path)
        assertEquals("/?q=%252F&empty=", transform("https://url.test?q=%252F&empty=").path)
        assertEquals("/", transform("http://url.test").path)
        assertEquals("/a/../b/./c?x=..", transform("/a/../b/./c?x=..").path)
    }

    @Test
    fun `legacy query and path escaping does not decode existing percent escapes`() {
        assertEquals(
            "/a%20b?q=hello%20world&v=%2F+%25",
            transform("/a b?q=hello world&v=%2F+%25").path,
        )
        assertEquals(
            "/a%22%3C%3E%60%7B%7D%7C%5E%27z?x=%22%3C%3E%60%7B%7D%7C%5E%27y",
            transform("http://url.test/a\"<>`{}|^'z?x=\"<>`{}|^'y").path,
        )
        val simpleOrigin = "/a\"<>`{}|^'z?x=\"<>`{}|^'y"
        assertEquals(simpleOrigin, transform(simpleOrigin).path)
        assertEquals("/a%27b@x?q=1", transform("/a'b@x?q=1").path)
        assertEquals("/a'b?x=@v", transform("/a'b?x=@v").path)
        assertEquals("/a%27b?x=1", transform("/a'b?x=1#fragment").path)
        assertEquals("/?next=%0d%0aX%3Atest", transform("/?next=%0d%0aX%3Atest").path)
        assertEquals("/x?q=1", transform("/x?q=1#local").path)
        assertEquals("/", transform("http://url.test#local").path)
    }

    @Test
    fun `ssl chooses protocol except absolute http overrides ssl`() {
        assertEquals("http:", transform("/").protocol)
        assertEquals(80, transform("/").port)
        assertEquals("https:", transform("/", ssl = true).protocol)
        assertEquals(443, transform("/", ssl = true).port)
        for (url in listOf("http://url.test/x", "HTTP://url.test/x", "HtTp://url.test/x")) {
            assertEquals("http:", transform(url, ssl = true).protocol)
            assertEquals(80, transform(url, ssl = true).port)
        }
        assertEquals("http:", transform("https://url.test/x").protocol)
        assertEquals(80, transform("https://url.test/x").port)
        assertEquals("https:", transform("https://url.test/x", ssl = true).protocol)
        assertEquals(443, transform("https://url.test/x", ssl = true).port)
    }

    @Test
    fun `Host supplies destination independently of absolute authority and preserves spelling`() {
        val result = transform(
            "https://ignored.test:9443/a?x=1",
            headers = mapOf("host" to "Route.TEST:00081", "authorization" to "Bearer retained"),
        )
        assertEquals("http:", result.protocol)
        assertEquals("Route.TEST", result.hostname)
        assertEquals(81, result.port)
        assertEquals("Route.TEST:00081", result.headers["host"])
        assertEquals("Bearer retained", result.headers["authorization"])
        assertEquals("/a?x=1", result.path)
        assertEquals("Example.TEST.", transform(headers = mapOf("host" to "Example.TEST.")).hostname)
        assertEquals("localhost", transform(headers = mapOf("host" to "localhost")).hostname)
        assertEquals("192.0.2.5", transform(headers = mapOf("host" to "192.0.2.5:8080")).hostname)
    }

    @Test
    fun `IPv6 remains bracketed and explicit ports override defaults`() {
        val defaultPort = transform(ssl = true, headers = mapOf("host" to "[2001:DB8::1]"))
        assertEquals("[2001:DB8::1]", defaultPort.hostname)
        assertEquals(443, defaultPort.port)
        val explicitPort = transform(
            "http://[::1]:8888/v6?x=%3A",
            ssl = true,
            headers = mapOf("host" to "[2001:db8::2]:8443"),
        )
        assertEquals("[2001:db8::2]", explicitPort.hostname)
        assertEquals(8443, explicitPort.port)
        assertEquals("http:", explicitPort.protocol)
        assertEquals("/v6?x=%3A", explicitPort.path)
        assertEquals(
            "[::ffff:192.0.2.2]",
            transform("https://[::ffff:192.0.2.1]/", headers = mapOf("host" to "[::ffff:192.0.2.2]")).hostname,
        )
        for (port in listOf(1, 65535)) {
            assertEquals(port, transform(headers = mapOf("host" to "example.test:$port")).port)
        }
    }

    @Test
    fun `close token detection is case insensitive and preserves original scalar`() {
        for (connection in listOf("close", "CLOSE", " keep-alive, ClOsE , upgrade ", "close,\tUPGRADE")) {
            assertEquals(connection, transform(headers = mapOf(
                "host" to "example.test",
                "connection" to connection,
            )).headers["connection"])
        }
    }

    @Test
    fun `multi value close token follows JavaScript comma coercion and retains array`() {
        val connection = listOf("Upgrade", " Keep-Alive, CLOSE ")
        val result = transform(headers = mapOf("host" to "example.test", "connection" to connection))
        assertEquals(connection, result.headers["connection"])
        assertNotSame(connection, result.headers["connection"])
    }

    @Test
    fun `non close tokens and empty values become scalar keep alive`() {
        for (connection in listOf("", "disclose, x-close, closed", "upgrade", listOf("keep-alive", "Upgrade"), emptyList<String>())) {
            assertEquals("keep-alive", transform(headers = mapOf(
                "host" to "example.test",
                "connection" to connection,
            )).headers["connection"])
        }
        assertEquals("keep-alive", transform().headers["connection"])
    }

    @Test
    fun `pseudo headers and proxy connection are filtered without blanket hop header removal`() {
        val result = transform(headers = mapOf(
            "host" to "example.test",
            ":method" to "POST",
            ":authority" to "ignored.test",
            ":path" to "/ignored",
            ":scheme" to "http",
            ":custom" to "also filtered",
            "proxy-connection" to "close",
            "connection" to "upgrade",
            "upgrade" to "websocket",
            "keep-alive" to "timeout=5",
            "authorization" to "Bearer retained",
            "proxy-authorization" to "Basic retained",
            "x-list" to listOf("a", "b"),
        ))
        assertFalse(result.headers.keys.any { it.startsWith(':') })
        assertFalse("proxy-connection" in result.headers)
        assertEquals("keep-alive", result.headers["connection"])
        assertEquals("websocket", result.headers["upgrade"])
        assertEquals("timeout=5", result.headers["keep-alive"])
        assertEquals("Bearer retained", result.headers["authorization"])
        assertEquals("Basic retained", result.headers["proxy-authorization"])
        assertEquals(listOf("a", "b"), result.headers["x-list"])
    }

    @Test
    fun `requires Host even with absolute URL or authority pseudo header`() {
        for (headers in listOf(
            emptyMap(),
            mapOf(":authority" to "example.test"),
            mapOf("host" to null),
            mapOf("host" to listOf("a.test")),
            mapOf("host" to listOf("a.test", "b.test")),
        )) {
            assertThrows(IllegalArgumentException::class.java) { transform("http://url.test/", headers = headers) }
        }
    }

    @Test
    fun `rejects malformed and unsupported authorities instead of partial parsing`() {
        val invalid = listOf(
            "", " a.test", "a.test ", "a.test\t", "a.test\r\nx: y", "user@a.test",
            "user:pass@a.test", "a.test/path", "a.test?x=1", "a.test#x", "a\\b.test",
            "a%2Etest", "a.test:", "a.test:0", "a.test:65536", "a.test:-1",
            "a.test:+80", "a.test:80junk", "a.test:99999999999999", "a.test:80:90",
            "a.test,other.test", "a..test", ".test", "-a.test", "a-.test", "a_b.test",
            "a".repeat(64) + ".test", "a.".repeat(127) + "test", "caf\u00e9.test",
            "127.1", "2130706433", "999.0.0.1", "192.000.2.1",
            "::1", "2001:db8::1:443", "[::1", "::1]", "[[::1]]", "[]",
            "[not-ipv6]", "[12345::1]", "[1::2::3]", "[::1]junk", "[::1]:",
            "[::1]:80junk", "[::1]:65536", "[::1]:443:80", "[fe80::1%eth0]",
            "[fe80::1%25eth0]",
        )
        for (host in invalid) {
            assertThrows("Host: $host", IllegalArgumentException::class.java) {
                transform(headers = mapOf("host" to host))
            }
            // URL delimiters end an authority; the same characters in Host
            // are forbidden rather than interpreted as path/query/fragment.
            if (host.none { it == '/' || it == '?' || it == '#' }) {
                assertThrows("Absolute authority: $host", IllegalArgumentException::class.java) {
                    transform("http://$host/")
                }
            }
        }
    }

    @Test
    fun `rejects unsupported target forms raw controls backslashes and malformed escapes`() {
        for (url in listOf(
            "", "relative/path", "?q=only", "#only", "//example.test/path",
            "example.test:443", "ftp://example.test/a", "http:/example.test/a",
            "http:///missing", "https://", "/bad%", "/bad%0", "/bad%GG",
            "/bad?x=%xx", "/a\r\nX-Injected: yes", "/a\tb", "/a\u0000b", "/a\u007fb",
            "/a\\b", "/?x=\\y", " /path", "/path ", "/caf\u00e9",
        )) {
            assertThrows(url, IllegalArgumentException::class.java) { transform(url) }
        }
    }

    @Test
    fun `rejects invalid header shapes names and injection even in discarded headers`() {
        for ((name, value) in listOf(
            "" to "x", "Bad-Case" to "x", "host " to "x", "x:y" to "x",
            "x\r\ninjected" to "x", ":" to "x", "x" to null, "x" to 1,
            "x" to true, "x" to listOf("a", null), "x" to listOf(1),
            "x" to mapOf("a" to "b"), "x" to "a\r\ninjected: b",
            "x" to "a\u0000b", "x" to "a\u007fb", "x" to "\u0100",
            "proxy-connection" to "close\r\ninjected: x", ":path" to "/\r\nbad",
        )) {
            val headers = mapOf("host" to "example.test", name to value)
            assertThrows(name, IllegalArgumentException::class.java) { transform(headers = headers) }
        }
        assertEquals("tab\tand\u00ff", transform(headers = mapOf(
            "host" to "example.test",
            "x-byte" to "tab\tand\u00ff",
        )).headers["x-byte"])
    }

    @Test
    fun `asterisk requires OPTIONS and CONNECT is outside the contract`() {
        assertEquals("*", transform("*", method = "OPTIONS").path)
        assertEquals("/x", transform("/x", method = "PATCH").path)
        for ((url, method) in listOf(
            "*" to "GET", "example.test:443" to "CONNECT", "/" to "CONNECT",
            "/" to "", "/" to "GET\r\nX", "/" to "GE T", "/" to "G\u00c9T",
        )) {
            assertThrows(method, IllegalArgumentException::class.java) { transform(url, method = method) }
        }
    }

    @Test
    fun `inputs results and repeated calls do not share mutable containers`() {
        val values = mutableListOf("a", "b")
        val connection = mutableListOf("upgrade", "CLOSE")
        val headers = linkedMapOf<String, Any?>(
            "host" to "example.test",
            "x-values" to values,
            "connection" to connection,
            "proxy-connection" to "close",
            ":path" to "/ignored",
        )
        val original = headers.toMap()
        val first = transform(headers = headers)
        val second = transform(headers = headers)
        assertEquals(original, headers)
        assertNotSame(headers, first.headers)
        assertNotSame(first.headers, second.headers)
        assertNotSame(values, first.headers["x-values"])
        assertNotSame(first.headers["x-values"], second.headers["x-values"])
        assertNotSame(connection, first.headers["connection"])
        values.add("source-change")
        connection.clear()
        headers["host"] = "changed.test"
        assertEquals(listOf("a", "b"), first.headers["x-values"])
        assertEquals(listOf("upgrade", "CLOSE"), first.headers["connection"])
        assertEquals("example.test", first.headers["host"])
        assertTrue("proxy-connection" in headers)
        assertTrue(":path" in headers)
    }
}
