package com.dsha.sidecar.net.dns

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * IP 测速：对候选 IP 并发做 TCP 握手，用"握手耗时"近似链路质量。
 * 为什么是 TCP 而不是 ICMP：Android 上 ICMP 需要 root，TCP 443 直接反映真实可用性。
 */
class IpBenchmark(
    private val port: Int = 443,
    private val timeoutMs: Int = 1500,
    private val parallelism: Int = 8,
) {

    data class Score(val ip: String, val latencyMs: Long)

    /**
     * 返回按延迟升序排列的结果（只含握手成功的 IP，最多 [keep] 个）。
     * [ips] 为空时返回空列表。
     */
    fun rank(ips: List<String>, keep: Int = 3): List<Score> {
        if (ips.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(parallelism, ips.size))
        try {
            val tasks = ips.map { ip ->
                Callable {
                    val start = System.nanoTime()
                    val ok = try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, port), timeoutMs)
                            true
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (!ok) null else Score(ip, (System.nanoTime() - start) / 1_000_000)
                }
            }
            val futures = pool.invokeAll(tasks, timeoutMs.toLong() + 500, TimeUnit.MILLISECONDS)
            return futures.mapNotNull { f ->
                try {
                    f.get()
                } catch (_: Exception) {
                    null
                }
            }.sortedBy { it.latencyMs }.take(keep)
        } finally {
            pool.shutdownNow()
        }
    }

    fun best(ips: List<String>): Score? = rank(ips, 1).firstOrNull()
}
