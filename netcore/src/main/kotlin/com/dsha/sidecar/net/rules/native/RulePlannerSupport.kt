// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.rules.native

import java.net.URI
import java.util.Collections
import java.util.Locale

internal object PlannerJson {
    private class Budget(var nodes: Int = 0)

    fun snapshot(value: Any?): Any? = copy(value, 0, Budget(), mutableListOf())

    private fun copy(value: Any?, depth: Int, budget: Budget, ancestors: MutableList<Any>): Any? {
        require(depth <= 64 && ++budget.nodes <= 100_000) { "JSON size/depth limit exceeded" }
        return when (value) {
            null, is String, is Boolean -> value
            is Number -> {
                require(value.toDouble().isFinite() && kotlin.math.abs(value.toDouble()) <= 9007199254740991.0) {
                    "Expected a finite, safely representable JSON number"
                }
                value
            }
            is Map<*, *>, is List<*> -> {
                require(ancestors.none { it === value }) { "Cyclic configuration" }
                ancestors.add(value)
                val result = if (value is Map<*, *>) {
                    val map = linkedMapOf<String, Any?>()
                    for ((key, item) in value) {
                        require(key is String && key !in setOf("__proto__", "constructor", "prototype")) {
                            "Invalid/prototype-sensitive JSON key"
                        }
                        map[key] = copy(item, depth + 1, budget, ancestors)
                    }
                    Collections.unmodifiableMap(ordered(map))
                } else {
                    Collections.unmodifiableList((value as List<*>).map { copy(it, depth + 1, budget, ancestors) })
                }
                ancestors.removeAt(ancestors.lastIndex)
                result
            }
            else -> throw IllegalArgumentException("Expected decoded JSON data")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        else -> true
    }

    fun keys(value: Map<String, Any?>): List<String> {
        val (indexes, others) = value.keys.partition { key ->
            val number = key.toLongOrNull()
            number != null && number in 0..4294967294L && number.toString() == key
        }
        return indexes.sortedBy { it.toLong() } + others
    }

    fun ordered(value: Map<String, Any?>): Map<String, Any?> =
        keys(value).associateWithTo(linkedMapOf()) { value[it] }

    fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        true -> 1.0
        false -> 0.0
        else -> null
    }

    fun text(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Boolean -> value.toString()
        is Number -> {
            val n = value.toDouble()
            if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
        }
        else -> throw IllegalArgumentException("Object-to-string coercion is unsupported")
    }

    fun maxAge(config: Map<String, Any?>): Double? {
        val seconds = listOf("cacheSeconds", "cacheMaxAge", "cache")
        if (seconds.any { (number(config[it]) ?: 0.0) > 0 }) {
            // Source uses || after the >0 gate: a preceding negative truthy value wins.
            return number(seconds.map { config[it] }.firstOrNull(::truthy))
        }
        for ((key, factor) in listOf(
            "cacheMinutes" to 60, "cacheHours" to 3600, "cacheDays" to 86400,
            "cacheWeeks" to 604800, "cacheMonths" to 2592000, "cacheYears" to 31536000,
        )) {
            val n = number(config[key]) ?: continue
            if (n > 0) return n * factor
        }
        return null
    }

    fun namedCaptures(path: String, pattern: String): Map<String, String?>? {
        val names = Regex("\\(\\?<([A-Za-z][A-Za-z0-9]*)>").findAll(pattern).map { it.groupValues[1] }.toList()
        if (names.isEmpty()) return null
        val expression = if (pattern.firstOrNull() in listOf('*', '?', '+')) ".$pattern" else pattern
        val matched = Regex(expression).find(path) ?: return null
        return names.associateWith { matched.groups[it]?.value }.filterValues { it != null }
    }
}

internal object RuleTargets {
    fun build(
        request: RuleRequest,
        path: String,
        target: String,
        config: Map<String, Any?>,
        source: SelectedImplementation,
    ): String {
        var result = when {
            PlannerJson.truthy(config["replace"]) -> {
                val expression = config["replace"] as? String
                    ?: throw IllegalArgumentException("replace must be a regex string")
                val match = Regex(expression).find(path)
                if (match == null) path else path.replaceRange(match.range, jsReplacement(target, path, match))
            }
            target.startsWith("http:") || target.startsWith("https:") -> target
            else -> {
                val original = if (path.startsWith("http:") || path.startsWith("https:")) {
                    val uri = uri(path)
                    (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                } else path
                target + original
            }
        }
        result = result.replaceFirst("\${host}", request.host)
        for ((prefix, captures, named) in listOf(
            Triple("p", source.pathCaptures, source.namedPathCaptures),
            Triple("h", source.hostnameCaptures, source.namedHostnameCaptures),
        )) {
            captures?.forEachIndexed { index, value ->
                result = result.replaceFirst("\${$prefix[$index]}", value ?: "")
            }
            named?.forEach { (key, value) -> result = result.replaceFirst("\${$key}", value ?: "") }
        }
        result = result.replace(Regex("\\$\\{[^}]+}"), "")
        return if (result.startsWith("http:") || result.startsWith("https:")) result else "${request.scheme}://$result"
    }

    fun route(target: String): RouteOverride {
        val uri = uri(target)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.rawUserInfo == null) {
            "Expected an absolute HTTP(S) route without user-info"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid route port" }
        val authority = uri.rawAuthority.lowercase(Locale.ROOT)
        return RouteOverride(
            target, uri.scheme, uri.host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT),
            if (uri.port == -1) { if (uri.scheme == "https") 443 else 80 } else uri.port,
            authority,
            (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: ""),
        )
    }

    private fun uri(value: String): URI = try {
        URI(value)
    } catch (failure: java.net.URISyntaxException) {
        throw IllegalArgumentException("URL is outside the supported URI syntax", failure)
    }

    private fun jsReplacement(replacement: String, input: String, match: MatchResult): String {
        val out = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            if (replacement[i] != '$' || i + 1 == replacement.length) {
                out.append(replacement[i++])
                continue
            }
            when (val next = replacement[i + 1]) {
                '$' -> { out.append('$'); i += 2 }
                '&' -> { out.append(match.value); i += 2 }
                '`' -> { out.append(input.substring(0, match.range.first)); i += 2 }
                '\'' -> { out.append(input.substring(match.range.last + 1)); i += 2 }
                in '1'..'9' -> {
                    var index = next - '0'
                    var used = 2
                    if (replacement.getOrNull(i + 2) in '0'..'9') {
                        val two = index * 10 + (replacement[i + 2] - '0')
                        if (two < match.groups.size) { index = two; used++ }
                    }
                    if (index < match.groups.size) { out.append(match.groups[index]?.value ?: ""); i += used }
                    else { out.append('$'); i++ }
                }
                '<' -> {
                    val end = replacement.indexOf('>', i + 2)
                    if (end < 0) { out.append('$'); i++ } else {
                        val name = replacement.substring(i + 2, end)
                        out.append(runCatching { match.groups[name]?.value }.getOrNull() ?: "")
                        i = end + 1
                    }
                }
                else -> { out.append('$'); i++ }
            }
        }
        return out.toString()
    }

    fun scriptRoutes(intercepts: Map<String, Any?>): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for (host in PlannerJson.keys(intercepts)) {
            val paths = PlannerJson.obj(intercepts[host]).toMutableMap()
            val urls = mutableListOf<String>()
            for (rule in paths.values.toList()) {
                val config = PlannerJson.obj(rule)
                when (val scripts = config["script"]) {
                    is String -> urls.add(scripts)
                    is List<*> -> urls.addAll(scripts.filterIsInstance<String>())
                }
                (config["tampermonkeyScript"] as? String)?.let(urls::add)
            }
            for (url in urls) {
                if (url.startsWith("https:") || url.startsWith("http:")) {
                    val key = "/____ds_script_proxy____/" +
                        url.replaceFirst(".js", "").replace(Regex("[^A-Za-z0-9]+"), "_") + ".js"
                    val pattern = "^${key.replace(".", "\\.")}$"
                    if (PlannerJson.truthy(paths[pattern])) continue
                    paths[pattern] = linkedMapOf(
                        "proxy" to url,
                        "requestReplace" to mapOf("headers" to mapOf("host" to "[remove]", "referer" to "[remove]", "cookie" to "[remove]")),
                        "responseReplace" to mapOf("headers" to mapOf(
                            "content-type" to "application/javascript; charset=utf-8", "set-cookie" to "[remove]", "server" to "[remove]",
                        )),
                        "cacheDays" to 7,
                    )
                } else if (url.startsWith("/") && !PlannerJson.truthy(paths[url])) {
                    paths[url] = mapOf(
                        "responseReplace" to mapOf("headers" to mapOf("content-type" to "application/javascript; charset=utf-8")),
                        "cacheDays" to 7,
                    )
                }
            }
            result[host] = paths
        }
        return result
    }
}
