// SPDX-License-Identifier: MPL-2.0
// Adapted from @blue-frontier/mitmproxy src/utils/util.match.js and
// @blue-frontier/dev-sidecar/src/merge.js (local wave-two reference).
package com.dsha.sidecar.net.rules

/**
 * Snapshot matcher for JSON object configurations. Matches merge in pattern
 * order, then *host, *.host, and exact-host order; deletion runs last.
 *
 * Uses Kotlin's regex engine, not an arbitrary JavaScript RegExp interpreter.
 * See docs/migration-wave2/hostname-all.md for the supported boundary, limits,
 * sparse-array transport, and capture metadata.
 */
class HostnameAllMatcher(entries: Map<String, Any?>) {

    data class CaptureMetadata(
        val groups: Map<String, String?>?,
        val index: Int?,
        val input: String?,
    )

    data class MatchResult(
        val values: Map<String, Any?>,
        val captureMetadata: CaptureMetadata?,
    )

    private data class Pattern(
        val regex: Regex,
        val value: Map<String, Any?>?,
        val namedGroups: Map<String, Int>,
    )

    // Metadata belongs to this particular array and disappears on replacement.
    private class CapturedValues(
        private val items: List<Any?>,
        val metadata: CaptureMetadata,
    ) : AbstractList<Any?>() {
        override val size: Int get() = items.size
        override fun get(index: Int): Any? = items[index]
    }

    private val origin = linkedMapOf<String, Map<String, Any?>?>()
    private val patterns: List<Pattern>

    init {
        require(entries.size <= MAX_ENTRIES) { "Too many hostname entries" }
        val budget = Budget()
        val snapshot = copyObject(entries, 0, budget)
        val expressions = linkedMapOf<String, Map<String, Any?>?>()
        for (key in jsonKeys(snapshot)) {
            require(key.length <= MAX_PATTERN_LENGTH) { "Hostname pattern is too long" }
            val value = snapshot[key]
            require(value == null || value is Map<*, *>) { "Hostname entries must be objects or null" }
            val config = value?.let { stringMap(it as Map<*, *>) }
            require(config?.get("matched") == null || config["matched"] is List<*>) {
                "The reserved matched property must be an array or null"
            }
            val domain = if (key.startsWith('.')) {
                if (snapshot["*$key"] != null) continue
                "*$key"
            } else {
                key
            }
            if ('*' in domain || domain.startsWith('^')) {
                val expression = if (domain.startsWith('^')) domain else {
                    "^${domain.replace(".", "\\.").replace("*", ".*")}$"
                }
                expressions[expression] = config
                if (domain.startsWith('*') && domain.lastIndexOf('*') == 0) {
                    origin[domain] = config
                }
            } else {
                origin[domain] = config
            }
        }
        patterns = expressions.mapNotNull { (expression, config) ->
            try {
                Pattern(Regex(expression), config, namedRegexGroups(expression))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    fun match(hostname: String): Map<String, Any?>? = matchWithMetadata(hostname)?.values

    fun matchWithMetadata(hostname: String): MatchResult? {
        require(hostname.length <= MAX_HOSTNAME_LENGTH) { "Hostname is too long" }
        val values = linkedMapOf<String, Any?>()
        for (pattern in patterns) {
            val matched = pattern.regex.find(hostname) ?: continue
            mergeInto(values, pattern.value)
            if (matched.groups.size > 1) {
                val previous = values["matched"] as? List<*>
                val groups = if (pattern.namedGroups.isEmpty()) null else {
                    linkedMapOf<String, String?>().apply {
                        for ((name, index) in pattern.namedGroups) {
                            // JSON drops undefined properties from JS named groups.
                            matched.groups[index]?.value?.let { put(name, it) }
                        }
                    }
                }
                val captures = (0 until matched.groups.size).map { matched.groups[it]?.value }
                values["matched"] = if (previous != null) {
                    // Upstream spread loses old groups/index/input. Only the
                    // newest match's groups are attached to the concatenation.
                    CapturedValues(previous + captures.drop(1), CaptureMetadata(groups, null, null))
                } else {
                    CapturedValues(captures, CaptureMetadata(groups, matched.range.first, hostname))
                }
            }
        }
        mergeInto(values, origin["*$hostname"])
        mergeInto(values, origin["*.$hostname"])
        mergeInto(values, origin[hostname])
        // Upstream checks emptiness before removing deletion markers.
        if (values.isEmpty()) return null
        val metadata = (values["matched"] as? CapturedValues)?.metadata
        return MatchResult(sanitizeObject(values), metadata)
    }

    private fun mergeInto(target: MutableMap<String, Any?>, overlay: Map<String, Any?>?) {
        if (overlay == null) return
        for (key in jsonKeys(overlay)) {
            target[key] = mergeValue(target[key], overlay[key])
        }
    }

    private fun mergeValue(old: Any?, value: Any?): Any? = when {
        // lodash's customizer replaces an existing array even with an object
        // or null, rather than merging into it by index.
        old is List<*> -> copyValue(value, 0, Budget())
        value is Map<*, *> -> {
            val nested = if (old is Map<*, *>) stringMap(old).toMutableMap() else linkedMapOf()
            mergeInto(nested, stringMap(value))
            nested
        }
        value is List<*> && old is Map<*, *> -> {
            val length = (old["length"] as? Number)?.toDouble()
            if (length != null && length >= 0 && length <= 9007199254740991.0 &&
                length % 1.0 == 0.0
            ) {
                require(length <= MAX_JSON_NODES) { "Array-like object exceeds the size limit" }
                // lodash copies an array-like object before merging source
                // indices, so a shorter source can retain the old tail.
                val array = MutableList<Any?>(maxOf(length.toInt(), value.size)) {
                    copyValue(old[it.toString()], 0, Budget())
                }
                for (index in value.indices) array[index] = mergeValue(array[index], value[index])
                array
            } else {
                copyValue(value, 0, Budget())
            }
        }
        else -> copyValue(value, 0, Budget())
    }

    private fun sanitizeObject(value: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            for (key in jsonKeys(value)) {
                val item = value[key]
                if (!isDeletion(item)) put(key, sanitizeValue(item))
            }
        }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> sanitizeObject(stringMap(value))
        // JS deletes array slots without shrinking length; null is the JSON
        // representation of those holes, including unmatched capture slots.
        is List<*> -> value.map { if (isDeletion(it)) null else sanitizeValue(it) }
        else -> value
    }

    private fun isDeletion(value: Any?): Boolean = value == null || value == "[delete]"

    private class Budget(var nodes: Int = 0)

    private fun copyObject(value: Map<*, *>, depth: Int, budget: Budget): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            for ((key, item) in value) {
                require(key is String) { "JSON object keys must be strings" }
                // Match lodash's safeGet boundary; never consult prototypes.
                if (key != "__proto__") put(key, copyValue(item, depth + 1, budget))
            }
        }

    private fun copyValue(value: Any?, depth: Int, budget: Budget): Any? {
        require(depth <= MAX_DEPTH && ++budget.nodes <= MAX_JSON_NODES) {
            "JSON configuration exceeds the depth or size limit"
        }
        return when (value) {
            is Map<*, *> -> copyObject(value, depth, budget)
            is List<*> -> value.map { copyValue(it, depth + 1, budget) }
            null, is String, is Boolean -> value
            is Number -> {
                require(value.toDouble().isFinite()) { "JSON numbers must be finite" }
                value
            }
            else -> throw IllegalArgumentException("Unsupported JSON value type")
        }
    }

    private fun stringMap(value: Map<*, *>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            for ((key, item) in value) {
                require(key is String) { "JSON object keys must be strings" }
                put(key, item)
            }
        }

    private fun jsonKeys(value: Map<String, Any?>): List<String> {
        val (indices, others) = value.keys.partition {
            val index = it.toLongOrNull()
            index != null && index in 0..4294967294L && index.toString() == it
        }
        return indices.sortedBy { it.toLong() } + others
    }

    companion object {
        const val MAX_ENTRIES = 1024
        const val MAX_PATTERN_LENGTH = 4096
        const val MAX_HOSTNAME_LENGTH = 8192
        const val MAX_DEPTH = 64
        const val MAX_JSON_NODES = 100000
    }
}

internal fun namedRegexGroups(expression: String): Map<String, Int> {
    val result = linkedMapOf<String, Int>()
    val commentModes = mutableListOf<Boolean>()
    var comments = false
    var quoted = false
    var classDepth = 0
    var group = 0
    var index = 0
    while (index < expression.length) {
        val char = expression[index]
        when {
            char == '\\' -> {
                val next = expression.getOrNull(index + 1)
                if (next == 'Q' && !quoted) quoted = true
                else if (next == 'E' && quoted) quoted = false
                index += 2
                continue
            }
            quoted -> Unit
            comments && char == '#' -> {
                while (index < expression.length && expression[index] != '\n' && expression[index] != '\r') index++
                continue
            }
            char == '[' -> classDepth++
            char == ']' && classDepth > 0 -> classDepth--
            classDepth == 0 && char == ')' -> {
                if (commentModes.isNotEmpty()) comments = commentModes.removeAt(commentModes.lastIndex)
            }
            classDepth == 0 && char == '(' -> {
                val next = expression.getOrNull(index + 1)
                if (next == '?') {
                    var flagEnd = index + 2
                    while (expression.getOrNull(flagEnd) in listOf('i', 'd', 'm', 's', 'u', 'x', 'U', '-')) flagEnd++
                    if (flagEnd > index + 2 && expression.getOrNull(flagEnd) in listOf(')', ':')) {
                        var enabled = true
                        var updated = comments
                        for (flag in expression.substring(index + 2, flagEnd)) {
                            if (flag == '-') enabled = false
                            else if (flag == 'x') updated = enabled
                        }
                        if (expression[flagEnd] == ':') commentModes.add(comments)
                        comments = updated
                        index = flagEnd + 1
                        continue
                    }
                    if (expression.getOrNull(index + 2) == '<' &&
                        expression.getOrNull(index + 3) != '=' && expression.getOrNull(index + 3) != '!'
                    ) {
                        group++
                        val end = expression.indexOf('>', index + 3)
                        if (end >= 0) result[expression.substring(index + 3, end)] = group
                    }
                } else {
                    group++
                }
                commentModes.add(comments)
            }
        }
        index++
    }
    return result
}
