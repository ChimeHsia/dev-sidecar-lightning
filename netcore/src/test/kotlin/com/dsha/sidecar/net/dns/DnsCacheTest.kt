package com.dsha.sidecar.net.dns

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DnsCacheTest {

    private var clock = 1_000_000L

    private fun cache(file: File? = null) = DnsCache(
        store = file,
        minTtlSec = 60,
        maxTtlSec = 3600,
        now = { clock },
    )

    @Test
    fun `TTL 到期后失效`() {
        val c = cache()
        c.put("github.com", "20.205.243.166", 120, 88)
        assertEquals("20.205.243.166", c.get("github.com")?.ip)

        clock += 119_000
        assertNotNull(c.get("github.com"))

        clock += 2_000
        assertNull(c.get("github.com"))
    }

    @Test
    fun `TTL 被夹在上下限之间`() {
        val c = cache()
        c.put("a.com", "1.1.1.1", 5, 10)      // 低于下限 60s
        clock += 30_000
        assertNotNull(c.get("a.com"))

        c.put("b.com", "2.2.2.2", 999_999, 10) // 高于上限 3600s
        clock += 3_000_000
        assertNotNull(c.get("b.com"))
        clock += 700_000
        assertNull(c.get("b.com"))
    }

    @Test
    fun `写入后能持久化再读回`() {
        val dir = Files.createTempDirectory("dnscache").toFile()
        val f = File(dir, "dns.tsv")
        val c1 = cache(f)
        c1.put("raw.githubusercontent.com", "185.199.108.133", 600, 120)
        c1.save()
        assertEquals(1, f.readLines().count { it.isNotBlank() && !it.startsWith("#") })

        val c2 = cache(f)
        val e = c2.get("raw.githubusercontent.com")
        assertNotNull(e)
        assertEquals("185.199.108.133", e.ip)
        assertEquals(120, e.latencyMs)
        dir.deleteRecursively()
    }

    @Test
    fun `域名键不区分大小写`() {
        val c = cache()
        c.put("GitHub.COM", "1.2.3.4", 600, 1)
        assertNotNull(c.get("github.com"))
        assertNotNull(c.get("GiThUb.CoM"))
    }

    @Test
    fun `expiry is inclusive and removes only the expired entry`() {
        val c = cache()
        c.put(" Example.COM. ", "192.0.2.1", 60, 10)
        c.put("other.example", "192.0.2.2", 120, 20)

        clock += 59_999
        assertNotNull(c.get("example.com"))
        clock += 1
        assertNull(c.get("EXAMPLE.COM."))
        assertEquals(1, c.size())
        assertNotNull(c.get("other.example"))
    }

    @Test
    fun `replacing and invalidating normalized host does not affect others`() {
        val c = cache()
        c.put("Example.COM.", "192.0.2.1", 60, 1)
        c.put(" example.com ", "192.0.2.2", 120, 2)
        c.put("other.example", "192.0.2.3", 120, 3)

        assertEquals(2, c.size())
        assertEquals("192.0.2.2", c.get("EXAMPLE.COM.")?.ip)
        c.invalidate(" ExAmPlE.CoM. ")
        assertNull(c.get("example.com"))
        assertNotNull(c.get("other.example"))
    }

    @Test
    fun `ttl clamps at both exact expiration boundaries`() {
        val c = cache()
        c.put("min.example", "192.0.2.1", -1, 0)
        c.put("max.example", "192.0.2.2", Int.MAX_VALUE, 0)

        clock += 60_000
        assertNull(c.get("min.example"))
        clock += 3_539_999
        assertNotNull(c.get("max.example"))
        clock += 1
        assertNull(c.get("max.example"))
    }
}
