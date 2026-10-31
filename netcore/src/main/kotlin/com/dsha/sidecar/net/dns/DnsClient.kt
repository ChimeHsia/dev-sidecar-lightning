package com.dsha.sidecar.net.dns

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.random.Random

/**
 * 自建 DNS 客户端。不依赖系统解析器，这样才能拿到**全部**候选 IP 用于测速优选。
 *
 * 三级通道（真实网络里缺一不可）：
 *  1. UDP 53 —— 最快，但校园网/企业网常被封；
 *  2. DNS over HTTPS —— UDP 被拦时仍可用（走 443，几乎不会被封）；
 *  3. 系统解析 —— 最后兜底（Captive Portal、只能走系统 DNS 的环境）。
 *
 * 会记住哪种通道上次成功（[preferDoh]），后续查询直接走通的通道，避免每次都在超时上耗时间。
 */
class DnsClient(
    val servers: List<String> = DEFAULT_SERVERS,
    private val timeoutMs: Int = 1200,
    private val tcpFallback: Boolean = true,
    private val dohEnabled: Boolean = true,
) {

    /** 一次查询的结果；[server] 为命中的通道（DoH 时是 URL）。 */
    data class Lookup(val response: DnsPacket.Response, val server: String)

    fun query(name: String, type: Int = DnsPacket.TYPE_A): Lookup? {
        val id = Random.nextInt(0, 0xFFFF)
        val packet = try {
            DnsPacket.buildQuery(name, type, id)
        } catch (_: IllegalArgumentException) {
            return null
        }

        if (dohEnabled && preferDoh) {
            dohQuery(packet, id)?.let { return it }
            return udpQuery(packet, id, servers)
        }
        // 首包只试最快的那个上游，失败就立刻转 DoH，避免在"被封的 UDP"上耗掉 5 秒
        udpQuery(packet, id, servers.take(1))?.let { return it }
        if (dohEnabled) {
            dohQuery(packet, id)?.let { return it }
        }
        return udpQuery(packet, id, servers.drop(1))
    }

    /** 解析出该域名的 IPv4 列表（去重，按 DNS 返回顺序）。 */
    fun resolveIpv4(name: String): List<Pair<String, Int>> {
        val custom = tryCustom(name)
        if (custom.isNotEmpty()) return custom
        return systemResolve(name)
    }

    /**
     * 广撒网解析：向多个 DoH 端点分别查询并合并结果。
     *
     * 为什么需要：某些域名（典型如 Google）国内解析结果被污染或被导向不可达 IP，
     * 单一来源往往只给 1~2 个地址，测速没得选。多源合并后候选更多，
     * "总有一个能连上"的概率显著提高——这正是 dev-sidecar「dns优选」的核心思路。
     */
    fun resolveIpv4Broad(name: String, maxIps: Int = 8): List<Pair<String, Int>> {
        val merged = LinkedHashMap<String, Int>()
        fun absorb(records: List<Pair<String, Int>>) {
            records.forEach { (ip, ttl) -> if (merged.size < maxIps) merged.putIfAbsent(ip, ttl) }
        }

        absorb(tryCustom(name))
        if (merged.size < 3 && dohEnabled) {
            val id = Random.nextInt(0, 0xFFFF)
            val packet = runCatching { DnsPacket.buildQuery(name, DnsPacket.TYPE_A, id) }.getOrNull()
            if (packet != null) {
                for (endpoint in DOH_ENDPOINTS) {
                    val resp = dohQueryTo(endpoint, packet, id)?.response ?: continue
                    if (resp.rcode == DnsPacket.RCODE_NOERROR) {
                        absorb(resp.addresses(DnsPacket.TYPE_A))
                    }
                    if (merged.size >= 5) break
                }
            }
        }
        if (merged.isEmpty()) return systemResolve(name)
        return merged.map { it.key to it.value }
    }

    private fun udpQuery(packet: ByteArray, id: Int, targets: List<String>): Lookup? {
        for (server in targets) {
            val resp = tryUdp(server, packet, id) ?: continue
            preferDoh = false
            if (resp.truncated && tcpFallback) {
                return tryTcp(server, packet, id)?.let { Lookup(it, server) } ?: Lookup(resp, server)
            }
            return Lookup(resp, server)
        }
        return null
    }

    private fun tryCustom(name: String): List<Pair<String, Int>> {
        val lookup = query(name, DnsPacket.TYPE_A) ?: return emptyList()
        if (lookup.response.rcode != DnsPacket.RCODE_NOERROR) return emptyList()
        return lookup.response.addresses(DnsPacket.TYPE_A).distinctBy { it.first }
    }

    private fun systemResolve(name: String): List<Pair<String, Int>> = try {
        InetAddress.getAllByName(name)
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress to SYSTEM_TTL_SEC }
            .distinctBy { it.first }
    } catch (_: Exception) {
        emptyList()
    }

    private fun tryUdp(server: String, packet: ByteArray, id: Int): DnsPacket.Response? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val addr = InetSocketAddress(InetAddress.getByName(server), 53)
                socket.send(DatagramPacket(packet, packet.size, addr))
                val buf = ByteArray(4096)
                val dp = DatagramPacket(buf, buf.size)
                socket.receive(dp)
                val resp = DnsPacket.parse(buf.copyOf(dp.length))
                if (resp.id != id) null else resp
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryTcp(server: String, packet: ByteArray, id: Int): DnsPacket.Response? {
        return try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(server, 53), timeoutMs)
                val out = DataOutputStream(socket.getOutputStream())
                out.writeShort(packet.size)
                out.write(packet)
                out.flush()
                val input = DataInputStream(socket.getInputStream())
                val len = input.readUnsignedShort()
                val buf = ByteArray(len)
                input.readFully(buf)
                val resp = DnsPacket.parse(buf)
                if (resp.id != id) null else resp
            }
        } catch (_: Exception) {
            null
        }
    }

    /** RFC 8484：把同一个查询报文 POST 给 DoH 端点，响应仍是标准 DNS 报文。 */
    private fun dohQuery(packet: ByteArray, id: Int): Lookup? {
        for (endpoint in DOH_ENDPOINTS) {
            dohQueryTo(endpoint, packet, id)?.let {
                preferDoh = true
                return it
            }
        }
        return null
    }

    private fun dohQueryTo(endpoint: String, packet: ByteArray, id: Int): Lookup? {
        return try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.outputStream.use { it.write(packet) }
            if (conn.responseCode != 200) {
                conn.disconnect()
                null
            } else {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val resp = DnsPacket.parse(bytes)
                if (resp.id == id) Lookup(resp, endpoint) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 国内公共 DNS；顺序即优先级。 */
        val DEFAULT_SERVERS = listOf("223.5.5.5", "119.29.29.29", "1.1.1.1", "8.8.8.8")

        /** DoH 端点（都用 IP 或自带解析，避免"解析 DoH 域名需要 DNS"的鸡生蛋问题）。 */
        val DOH_ENDPOINTS = listOf(
            "https://223.5.5.5/dns-query",
            "https://1.12.12.12/dns-query",
            "https://120.53.53.53/dns-query",
        )

        /** 上次成功的通道是 DoH 时优先用它；进程内共享。 */
        @Volatile
        var preferDoh: Boolean = false

        /** 系统解析回来的地址没有 TTL，给一个保守值。 */
        private const val SYSTEM_TTL_SEC = 300
    }
}
