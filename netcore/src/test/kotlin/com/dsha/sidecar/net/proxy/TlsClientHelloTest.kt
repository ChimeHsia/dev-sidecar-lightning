package com.dsha.sidecar.net.proxy

import com.dsha.sidecar.net.util.ByteWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TlsClientHelloTest {

    @Test
    fun `能解析 ClientHello 里的 SNI`() {
        val record = clientHello("raw.githubusercontent.com")
        assertEquals("raw.githubusercontent.com", TlsClientHello.sniOf(record))
    }

    @Test
    fun `改写成更长域名后各层长度一致`() {
        val record = clientHello("github.com")
        val out = TlsClientHello.rewriteSni(record, "raw.githubusercontent.com")
        assertEquals("raw.githubusercontent.com", TlsClientHello.sniOf(out))
        assertConsistent(out)
        // 尾部扩展（0x000b）必须完整保留
        assertEquals(record.size + ("raw.githubusercontent.com".length - "github.com".length), out.size)
        assertTrue(out.size == 5 + readU16(out, 3))
    }

    @Test
    fun `改写成更短域名同样自洽`() {
        val record = clientHello("very-long-subdomain.example.com")
        val out = TlsClientHello.rewriteSni(record, "gh.io")
        assertEquals("gh.io", TlsClientHello.sniOf(out))
        assertConsistent(out)
    }

    @Test
    fun `域名未变化时原样返回`() {
        val record = clientHello("github.com")
        assertSame(record, TlsClientHello.rewriteSni(record, "github.com"))
    }

    @Test
    fun `非 TLS 数据不被误处理`() {
        val plain = "GET / HTTP/1.1\r\nHost: github.com\r\n\r\n".toByteArray()
        assertEquals(null, TlsClientHello.sniOf(plain))
        assertSame(plain, TlsClientHello.rewriteSni(plain, "x.com"))
    }

    @Test
    fun `握手被切断时不改写`() {
        val record = clientHello("github.com")
        val cut = record.copyOf(record.size - 10)
        assertEquals(null, TlsClientHello.sniOf(cut))
    }

    private fun assertConsistent(r: ByteArray) {
        val recordLen = readU16(r, 3)
        assertEquals(5 + recordLen, r.size, "record 长度应等于实际字节数")
        val hsLen = ((r[6].toInt() and 0xFF) shl 16) or ((r[7].toInt() and 0xFF) shl 8) or (r[8].toInt() and 0xFF)
        assertEquals(recordLen - 4, hsLen, "握手长度应等于 record 载荷减 4 字节握手头")
    }

    private fun readU16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /** 构造一条真实结构的最小 ClientHello record。 */
    private fun clientHello(sni: String): ByteArray {
        val body = ByteWriter(256)
        body.u8(0x03).u8(0x03)                       // client_version TLS1.2
        repeat(32) { body.u8(it) }                   // random
        body.u8(0)                                   // session_id 长度 0
        body.u16(2).u8(0x13).u8(0x01)                // cipher_suites: TLS_AES_128_GCM_SHA256
        body.u8(1).u8(0)                             // compression_methods: null

        val exts = ByteWriter(128)
        // SNI 扩展
        val sniData = ByteWriter(64)
        val nameBytes = sni.toByteArray(Charsets.US_ASCII)
        sniData.u16(nameBytes.size + 3).u8(0).u16(nameBytes.size).bytes(nameBytes)
        val sniBytes = sniData.toByteArray()
        exts.u16(0x0000).u16(sniBytes.size).bytes(sniBytes)
        // 后面再挂一个扩展，验证改写不影响其后的数据
        exts.u16(0x000B).u16(2).u8(1).u8(0)          // ec_point_formats
        val extBytes = exts.toByteArray()
        body.u16(extBytes.size).bytes(extBytes)

        val bodyBytes = body.toByteArray()
        val hs = ByteWriter(bodyBytes.size + 4)
        hs.u8(0x01).u24(bodyBytes.size).bytes(bodyBytes)
        val hsBytes = hs.toByteArray()

        val rec = ByteWriter(hsBytes.size + 5)
        rec.u8(0x16).u8(0x03).u8(0x01).u16(hsBytes.size).bytes(hsBytes)
        return rec.toByteArray()
    }
}
