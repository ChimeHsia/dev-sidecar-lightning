package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.config.native.BoundedJson5
import com.dsha.sidecar.net.config.native.ConfigLimits
import com.dsha.sidecar.net.dns.IpAddress
import com.dsha.sidecar.net.proxy.http.Http1Header
import com.dsha.sidecar.net.proxy.http.Http1Headers
import com.dsha.sidecar.net.proxy.http.Http1ResponseHead
import com.dsha.sidecar.net.proxy.http.Http1Target
import com.dsha.sidecar.net.proxy.native.NativeExchangePolicy
import com.dsha.sidecar.net.proxy.native.NativeProxyRequest
import com.dsha.sidecar.net.proxy.native.NativeRequestDecision
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.Locale

class NativeNavigationRules private constructor(private val rules: Map<String, String>) {
    internal fun needsMitm(request: NativeProxyRequest): Boolean =
        request.head.method == "CONNECT" && request.authority.port == 443 &&
            "https://${request.authority.host.lowercase(Locale.ROOT)}" in rules
    fun target(request: NativeProxyRequest): String? {
        if (request.head.method !in setOf("GET", "HEAD")) return null
        val headers = request.head.headers
        if (headers.values("Transfer-Encoding").isNotEmpty()) return null
        val lengths = headers.values("Content-Length")
        if (lengths.isNotEmpty() && lengths != listOf("0")) return null
        val destinations = headers.values("Sec-Fetch-Dest")
        if (destinations.size > 1) return null
        val destination = destinations.singleOrNull()?.lowercase(Locale.ROOT).orEmpty()
        val acceptsHtml = headers.values("Accept").any { HTML_ACCEPT.containsMatchIn(it) }
        if (destination != "document" && destination != "iframe" && (destination.isNotEmpty() || !acceptsHtml)) {
            return null
        }
        val scheme = if (request.secure) "https" else "http"
        if (request.authority.port != if (request.secure) 443 else 80) return null
        val target = when (val value = request.head.target) {
            is Http1Target.Origin -> value.raw.takeUnless { it.startsWith("//") }
            is Http1Target.Absolute -> {
                val absolute = runCatching { URI(value.raw) }.getOrNull() ?: return null
                if (!absolute.scheme.equals(scheme, true)) return null
                value.originForm
            }
            else -> null
        } ?: return null
        if (!target.startsWith("/") || target.any { it == '\\' || it == '\r' || it == '\n' || it == '\t' }) {
            return null
        }
        val destinationOrigin = rules["$scheme://${request.authority.host.lowercase(Locale.ROOT)}"] ?: return null
        val queryIndex = target.indexOf('?')
        val path = if (queryIndex < 0) target else target.substring(0, queryIndex)
        val query = if (queryIndex < 0) "" else target.substring(queryIndex)
        return destinationOrigin + normalizePath(path) + query
    }

    companion object {
        private val HTML_ACCEPT = Regex("(?:^|,)\\s*text/html(?:\\s*;|\\s*,|$)", RegexOption.IGNORE_CASE)
        private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", RegexOption.IGNORE_CASE)

        fun compile(document: Map<String, Any?>): NativeNavigationRules {
            require((document["version"] as? Number)?.toDouble() == 1.0)
            val entries = document["rules"] as? List<*> ?: throw IllegalArgumentException("Invalid navigation rules")
            require(entries.size <= 128)
            val result = linkedMapOf<String, String>()
            for (entry in entries) {
                val rule = entry as? Map<*, *> ?: throw IllegalArgumentException("Invalid navigation rule")
                val enabled = rule["enabled"]
                require(enabled == null || enabled is Boolean)
                if (enabled == false) continue
                val hosts = rule["sourceHosts"] as? List<*> ?: throw IllegalArgumentException("Invalid source hosts")
                val schemes = rule["sourceSchemes"] as? List<*> ?: throw IllegalArgumentException("Invalid source schemes")
                require(hosts.isNotEmpty() && hosts.size <= 128 && hosts.all { it is String && validHost(it) })
                require(schemes.isNotEmpty() && schemes.all { it == "http" || it == "https" })
                val origin = targetOrigin(rule["targetOrigin"] as? String ?: throw IllegalArgumentException("Invalid target"))
                for (host in hosts) {
                    for (scheme in schemes) {
                        val source = "$scheme://${(host as String).lowercase(Locale.ROOT)}"
                        require(result.put(source, origin) == null) { "Duplicate navigation source" }
                    }
                }
            }
            val checked = mutableSetOf<String>()
            for (source in result.keys) {
                val visited = mutableSetOf<String>()
                var next: String? = source
                while (next != null && next !in checked) {
                    require(visited.add(next)) { "Navigation redirect loop" }
                    next = result[next]
                }
                checked.addAll(visited)
            }
            return NativeNavigationRules(Collections.unmodifiableMap(result))
        }

        private fun validHost(host: String): Boolean =
            host.length <= 253 && !IpAddress.isIpLiteral(host) && host.split('.').all { LABEL.matches(it) }

        private fun targetOrigin(value: String): String {
            val uri = URI(value)
            val host = uri.host ?: throw IllegalArgumentException("Invalid target host")
            require(uri.scheme.equals("https", true) && validHost(host) && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.rawPath.orEmpty() in setOf("", "/") &&
                uri.rawQuery == null && uri.rawFragment == null)
            return "https://${host.lowercase(Locale.ROOT)}"
        }

        private fun normalizePath(path: String): String {
            val segments = path.split('/')
            val result = mutableListOf<String>()
            for ((index, segment) in segments.withIndex()) {
                if (index == 0) continue
                val dots = segment.replace("%2e", ".", ignoreCase = true)
                when (dots) {
                    "." -> if (index == segments.lastIndex) result.add("")
                    ".." -> {
                        if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                        if (index == segments.lastIndex) result.add("")
                    }
                    else -> result.add(segment)
                }
            }
            return "/" + result.joinToString("/")
        }
    }
}

class NativeNavigationRuleStore(defaults: String, private val file: File? = null) {
    private val parser = BoundedJson5(ConfigLimits(maxDocumentBytes = 65_536), strictJson = true)
    private var current = NativeNavigationRules.compile(parser.parseObject(defaults))

    init {
        file?.let { configFile ->
            runCatching {
                configFile.parentFile?.mkdirs()
                Files.newOutputStream(configFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                    it.write(defaults.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    @Synchronized
    fun getRules(): NativeNavigationRules {
        if (file == null) return current
        runCatching {
            val bytes = FileInputStream(file).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= 65_536)
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            current = NativeNavigationRules.compile(parser.parseObject(bytes))
        }
        return current
    }
}

class NativeNavigationPolicy(
    private val downstream: NativeExchangePolicy,
    private val rules: () -> NativeNavigationRules,
) : NativeExchangePolicy by downstream {
    override fun onRequest(request: NativeProxyRequest): NativeRequestDecision {
        val current = rules()
        if (current.needsMitm(request)) return NativeRequestDecision.Forward(request.head,
            com.dsha.sidecar.net.proxy.native.NativeProxyRoute(
                java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), request.authority.port)))
        val target = current.target(request) ?: return downstream.onRequest(request)
        return NativeRequestDecision.LocalResponse(
            Http1ResponseHead(
                request.head.version,
                307,
                "Temporary Redirect",
                Http1Headers(listOf(
                    Http1Header("Location", " $target"),
                    Http1Header("Cache-Control", " no-store"),
                    Http1Header("Content-Length", " 0"),
                    Http1Header("DS-Interceptor", " https-navigation"),
                )),
            ),
        )
    }
}
