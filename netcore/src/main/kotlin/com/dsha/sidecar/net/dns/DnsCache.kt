package com.dsha.sidecar.net.dns

import java.io.File

/**
 * 域名 → 优选 IP 的内存缓存，可持久化到本地文件（TSV，无第三方依赖）。
 * 冷启动时直接复用上次测速结果，避免每次打开 App 都重新探测。
 */
class DnsCache(
    private val store: File? = null,
    private val minTtlSec: Int = 60,
    private val maxTtlSec: Int = 86_400,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Entry(val host: String, val ip: String, val latencyMs: Long, val expiresAt: Long) {
        fun expired(now: Long): Boolean = now >= expiresAt
    }

    private val map = HashMap<String, Entry>()

    init {
        load()
    }

    fun get(host: String): Entry? {
        val key = normalize(host)
        val e = synchronized(map) { map[key] } ?: return null
        if (e.expired(now())) {
            synchronized(map) { map.remove(key) }
            return null
        }
        return e
    }

    fun put(host: String, ip: String, ttlSec: Int, latencyMs: Long) {
        val ttl = ttlSec.coerceIn(minTtlSec, maxTtlSec)
        val e = Entry(normalize(host), ip, latencyMs, now() + ttl * 1000L)
        synchronized(map) { map[e.host] = e }
    }

    fun invalidate(host: String) {
        synchronized(map) { map.remove(normalize(host)) }
    }

    fun size(): Int = synchronized(map) { map.size }

    fun snapshot(): List<Entry> = synchronized(map) { map.values.toList() }

    private fun normalize(host: String) = host.trim().trimEnd('.').lowercase()

    fun load() {
        val f = store ?: return
        if (!f.exists() || f.length() == 0L) return
        val cutoff = now() - 7L * 86_400_000
        try {
            val fresh = HashMap<String, Entry>()
            f.forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val parts = line.split('\t')
                if (parts.size < 4) return@forEachLine
                val expiresAt = parts[3].toLongOrNull() ?: return@forEachLine
                if (expiresAt < cutoff) return@forEachLine
                val e = Entry(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L, expiresAt)
                fresh[e.host] = e
            }
            synchronized(map) { map.putAll(fresh) }
        } catch (_: Exception) {
            // 缓存损坏直接忽略，下次写入会覆盖
        }
    }

    fun save() {
        val f = store ?: return
        val entries = snapshot()
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write("# host\tip\tlatencyMs\texpiresAt\n")
                for (e in entries) {
                    w.write("${e.host}\t${e.ip}\t${e.latencyMs}\t${e.expiresAt}\n")
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
            // 持久化失败不影响运行
        }
    }
}
