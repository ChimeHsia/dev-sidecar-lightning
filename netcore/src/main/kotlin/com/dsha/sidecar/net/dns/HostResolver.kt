package com.dsha.sidecar.net.dns

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 解析编排：缓存 → 自建 DNS 拿全部候选 IP → 并发测速 → 取最快。
 * 这是"加速"的实际来源：绕开运营商 DNS 污染/慢节点，直连响应最快的边缘 IP。
 */
class HostResolver(
    private val dns: DnsClient = DnsClient(),
    private val cache: DnsCache = DnsCache(),
    private val benchmark: IpBenchmark = IpBenchmark(),
    private val ttlSec: Int = 600,
) {

    data class Result(
        val host: String,
        val ip: String,
        val latencyMs: Long,
        val cached: Boolean,
        val candidates: List<String>,
    )

    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val pinned = ConcurrentHashMap<String, String>()

    @Volatile
    var lastResult: Result? = null
        private set

    /** 规则可固定某个域名的出口 IP（例如镜像）。 */
    fun pin(host: String, ip: String) {
        pinned[host.lowercase()] = ip
    }

    fun resolve(host: String): Result? {
        val key = host.trim().trimEnd('.').lowercase()
        if (isIpLiteral(key)) return Result(key, key, 0, false, listOf(key))

        pinned[key]?.let { return Result(key, it, 0, true, listOf(it)) }

        cache.get(key)?.let { entry ->
            return Result(key, entry.ip, entry.latencyMs, true, listOf(entry.ip)).also { lastResult = it }
        }

        // 同一域名的探测串行化：并发请求共享第一次结果，避免测速风暴
        val lock = locks.getOrPut(key) { ReentrantLock() }
        lock.lock()
        try {
            cache.get(key)?.let { entry ->
                return Result(key, entry.ip, entry.latencyMs, true, listOf(entry.ip)).also { lastResult = it }
            }
            val seed = IpSeed.forHost(key)
            val records = dns.resolveIpv4Broad(key)
            val dnsIps = records.map { it.first }.filter { it !in seed }
            if (seed.isEmpty() && dnsIps.isEmpty()) return null

            // 种子优先：被投毒的 DNS 会返回"能连但连错"的 IP（例如 Google 域名解析到 Facebook 的机器），
            // 那种 IP 测速反而很快，参与竞争就会选错出口。种子全都连不上时才回退到 DNS 结果。
            var best = benchmark.best(seed)
            if (best == null) best = benchmark.best(dnsIps)
            val ordered = (seed + dnsIps).distinct()
            val chosen = best?.ip ?: ordered.first()
            val latency = best?.latencyMs ?: -1L
            val ttl = if (records.isEmpty()) ttlSec else records.minOf { it.second }.coerceAtLeast(ttlSec / 2)
            cache.put(key, chosen, ttl, latency)
            return Result(key, chosen, latency, false, ordered).also { lastResult = it }
        } finally {
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }

    /**
     * 快速解析：只取 DNS 返回的第一个可用 IP，不做并发测速。
     * 给"非加速域名"用——目的不是挑最快，而是让**系统 DNS 坏掉**的网络（校园网、企业网）
     * 也能打开网页。SNI 不变，所以 TLS 依旧正常。
     */
    fun resolveQuick(host: String): Result? {
        val key = host.trim().trimEnd('.').lowercase()
        if (isIpLiteral(key)) return Result(key, key, 0, false, listOf(key))
        pinned[key]?.let { return Result(key, it, 0, true, listOf(it)) }
        cache.get(key)?.let { e ->
            return Result(key, e.ip, e.latencyMs, true, listOf(e.ip))
        }
        val records = dns.resolveIpv4(key)
        if (records.isEmpty()) return null
        val ip = records.first().first
        cache.put(key, ip, records.first().second, -1)
        return Result(key, ip, -1, false, records.map { it.first })
    }

    /** 丢弃某个域名的缓存（缓存 IP 失效、连不上时用）。 */
    fun invalidate(host: String) {
        val key = host.trim().trimEnd('.').lowercase()
        cache.invalidate(key)
        lastResult = null
    }

    fun persist() = cache.save()

    companion object {
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        fun isIpLiteral(host: String): Boolean =
            IPV4.matches(host) || host.contains(':')
    }
}
