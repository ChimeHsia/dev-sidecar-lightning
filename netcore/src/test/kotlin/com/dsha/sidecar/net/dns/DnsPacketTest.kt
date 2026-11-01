package com.dsha.sidecar.net.dns

import com.dsha.sidecar.net.util.ByteWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DnsPacketTest {

    @Test
    fun `buildQuery 编码可被自身解析`() {
        val q = DnsPacket.buildQuery("raw.githubusercontent.com", DnsPacket.TYPE_A, 0x1234)
        val r = DnsPacket.parse(q)
        assertEquals(0x1234, r.id)
        assertEquals(1, r.questions.size)
        assertEquals("raw.githubusercontent.com", r.questions[0].name)
        assertEquals(DnsPacket.TYPE_A, r.questions[0].type)
        // EDNS0: ARCOUNT=1，报文尾部有 OPT
        assertEquals(1, ((q[10].toInt() and 0xFF) shl 8) or (q[11].toInt() and 0xFF))
    }

    @Test
    fun `响应解析 A 记录`() {
        val resp = buildResponse(
            question = "github.com",
            answers = listOf(Answer("github.com", DnsPacket.TYPE_A, "20.205.243.166")),
        )
        val parsed = DnsPacket.parse(resp)
        val ips = parsed.addresses(DnsPacket.TYPE_A)
        assertEquals(listOf("20.205.243.166"), ips.map { it.first })
        assertEquals(60, ips[0].second)
    }

    @Test
    fun `CNAME 链能跟随到最终 A 记录`() {
        val resp = buildResponse(
            question = "raw.githubusercontent.com",
            answers = listOf(
                Answer("raw.githubusercontent.com", DnsPacket.TYPE_CNAME, target = "raw.githubmap.com", pointerTo = 12),
                Answer("raw.githubmap.com", DnsPacket.TYPE_CNAME, target = "edge.cdn.example"),
                Answer("edge.cdn.example", DnsPacket.TYPE_A, "185.199.108.133"),
                Answer("edge.cdn.example", DnsPacket.TYPE_A, "185.199.109.133"),
            ),
        )
        val parsed = DnsPacket.parse(resp)
        assertEquals(
            listOf("185.199.108.133", "185.199.109.133"),
            parsed.addresses(DnsPacket.TYPE_A).map { it.first },
        )
    }

    @Test
    fun `截断标志与 rcode 能读出`() {
        val resp = buildResponse("a.com", emptyList(), flags = 0x8383) // TC + NXDOMAIN
        val parsed = DnsPacket.parse(resp)
        assertTrue(parsed.truncated)
        assertEquals(DnsPacket.RCODE_NXDOMAIN, parsed.rcode)
    }

    @Test
    fun `非法标签长度不会抛到调用方`() {
        val q = DnsPacket.buildQuery("ok.example", DnsPacket.TYPE_A, 1)
        assertNotNull(DnsPacket.parse(q))
        assertTrue(runCatching { DnsPacket.buildQuery("a".repeat(64) + ".com", 1, 1) }.isFailure)
    }

    @Test
    fun `query validates label and name boundaries and supports root without EDNS`() {
        val label = "a".repeat(63)
        val longestAccepted = listOf(label, label, label, "b".repeat(60)).joinToString(".")
        val packet = DnsPacket.buildQuery("$longestAccepted.", DnsPacket.TYPE_AAAA, 0x12345, edns = false)
        val parsed = DnsPacket.parse(packet)
        assertEquals(0x2345, parsed.id)
        assertEquals(longestAccepted, parsed.questions.single().name)
        assertEquals(DnsPacket.TYPE_AAAA, parsed.questions.single().type)
        assertEquals(0, packet[11].toInt())
        assertEquals("", DnsPacket.parse(DnsPacket.buildQuery(".", DnsPacket.TYPE_A, 1)).questions.single().name)
        assertFailsWith<IllegalArgumentException> { DnsPacket.buildQuery("a..example", 1, 1) }
        assertFailsWith<IllegalArgumentException> {
            DnsPacket.buildQuery(listOf(label, label, label, "b".repeat(61)).joinToString("."), 1, 1)
        }
    }

    @Test
    fun `compressed cname normalizes case and excludes unrelated addresses`() {
        val response = buildResponse(
            question = "WWW.Example.COM",
            answers = listOf(
                Answer("ignored", DnsPacket.TYPE_CNAME, target = "Edge.Example.COM", pointerTo = 12),
                Answer("EDGE.example.com", DnsPacket.TYPE_A, "192.0.2.1"),
                Answer("unrelated.example.com", DnsPacket.TYPE_A, "192.0.2.2"),
            ),
        )
        val parsed = DnsPacket.parse(response)
        assertEquals(listOf("192.0.2.1" to 60), parsed.addresses(DnsPacket.TYPE_A))
        assertEquals(emptyList(), parsed.addresses(DnsPacket.TYPE_AAAA))
    }

    @org.junit.Test(timeout = 1_000)
    fun `cyclic cname chain without addresses terminates`() {
        val response = buildResponse(
            "a.example",
            listOf(
                Answer("a.example", DnsPacket.TYPE_CNAME, target = "b.example"),
                Answer("b.example", DnsPacket.TYPE_CNAME, target = "a.example"),
            ),
        )
        assertEquals(emptyList(), DnsPacket.parse(response).addresses(DnsPacket.TYPE_A))
    }

    @Test
    fun `addresses ignore records of wrong length and decode IPv6 separately`() {
        val w = ByteWriter()
        w.u16(42).u16(0x8180).u16(1).u16(3).u16(0).u16(0)
        DnsPacket.writeName(w, "example.com")
        w.u16(DnsPacket.TYPE_AAAA).u16(1)
        w.u16(0xc00c).u16(DnsPacket.TYPE_A).u16(1).u16(0).u16(1).u16(3)
            .bytes(byteArrayOf(1, 2, 3))
        w.u16(0xc00c).u16(DnsPacket.TYPE_AAAA).u16(1).u16(0).u16(123).u16(16)
            .bytes(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
        w.u16(0xc00c).u16(DnsPacket.TYPE_AAAA).u16(1).u16(0).u16(1).u16(4)
            .bytes(byteArrayOf(1, 2, 3, 4))
        val response = DnsPacket.parse(w.toByteArray())

        assertEquals(emptyList(), response.addresses(DnsPacket.TYPE_A))
        assertEquals(listOf("2001:db8:0:0:0:0:0:1" to 123), response.addresses(DnsPacket.TYPE_AAAA))
    }

    @Test
    fun `truncated header question and compression pointer are rejected`() {
        val query = DnsPacket.buildQuery("example.com", DnsPacket.TYPE_A, 1, edns = false)
        for (size in 0 until query.size) {
            assertFailsWith<IndexOutOfBoundsException>("size=$size") {
                DnsPacket.parse(query.copyOf(size))
            }
        }
        val invalidPointer = ByteWriter().u16(1).u16(0).u16(1).u16(0).u16(0).u16(0)
            .u16(0xc0ff).u16(1).u16(1).toByteArray()
        assertFailsWith<IndexOutOfBoundsException> { DnsPacket.parse(invalidPointer) }
    }

    // --- 构造应答报文的辅助 ---

    private data class Answer(
        val name: String,
        val type: Int,
        val ip: String? = null,
        val target: String? = null,
        /** 用压缩指针指向报文 offset 12 的名字（模拟真实应答）。 */
        val pointerTo: Int? = null,
    )

    private fun buildResponse(question: String, answers: List<Answer>, flags: Int = 0x8180): ByteArray {
        val w = ByteWriter(128)
        w.u16(0x4321).u16(flags).u16(1).u16(answers.size).u16(0).u16(0)
        DnsPacket.writeName(w, question)
        w.u16(DnsPacket.TYPE_A).u16(1)
        for (a in answers) {
            if (a.pointerTo != null) {
                w.u16(0xC000 or a.pointerTo)
            } else {
                DnsPacket.writeName(w, a.name)
            }
            w.u16(a.type).u16(1).u16(0).u16(60)
            when {
                a.type == DnsPacket.TYPE_A -> {
                    w.u16(4)
                    a.ip!!.split('.').forEach { w.u8(it.toInt()) }
                }
                else -> {
                    val inner = ByteWriter()
                    DnsPacket.writeName(inner, a.target!!)
                    val bytes = inner.toByteArray()
                    w.u16(bytes.size).bytes(bytes)
                }
            }
        }
        return w.toByteArray()
    }
}
