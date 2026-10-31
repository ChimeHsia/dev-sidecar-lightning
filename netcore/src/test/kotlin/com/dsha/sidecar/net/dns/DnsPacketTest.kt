package com.dsha.sidecar.net.dns

import com.dsha.sidecar.net.util.ByteWriter
import kotlin.test.Test
import kotlin.test.assertEquals
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
