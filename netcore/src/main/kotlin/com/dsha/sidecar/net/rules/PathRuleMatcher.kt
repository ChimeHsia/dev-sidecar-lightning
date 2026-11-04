// SPDX-License-Identifier: MPL-2.0
// Ported from @blue-frontier/mitmproxy src/utils/util.match.js and src/options.js.
package com.dsha.sidecar.net.rules

/**
 * Pure path matching and descriptor selection over already merged hostname rules.
 * Paths are used verbatim, including queries. Patterns are strings or Boolean true;
 * unsupported pattern types and invalid regexes return null.
 */
object PathRuleMatcher {

    /** Full match followed by captures; an unmatched optional capture remains null. */
    fun isMatched(path: String, pattern: Any?): List<String?>? {
        if (pattern == true || pattern == "true" || pattern == "*" || pattern == ".*") {
            return listOf(path)
        }
        if (pattern !is String) return null
        val expression = if (pattern.firstOrNull() in listOf('*', '?', '+')) {
            ".$pattern"
        } else {
            pattern
        }
        return try {
            Regex(expression).find(path)?.groups?.map { it?.value }
        } catch (_: Throwable) {
            null
        }
    }

    /** Object KEYS are patterns regardless of their values, including false/null. */
    fun getExclusionArray(exclusions: Any?): List<Any?>? = when (exclusions) {
        is List<*> -> exclusions.toList().takeIf { it.isNotEmpty() }
        is Map<*, *> -> exclusions.keys.filterIsInstance<String>().takeIf { it.isNotEmpty() }
        else -> null
    }

    fun isExcluded(path: String, exclusions: Any?): Boolean =
        getExclusionArray(exclusions)?.any { isMatched(path, it) != null } == true

    /**
     * A descriptor is enabled when ANY supplied config key has a truthy JSON value.
     * Callers provide descriptors in upstream implementation order and must handle
     * implementation-specific predicates (such as sni/cache/ssl) before using this API.
     */
    data class Implementation(
        val name: String,
        val priority: Int,
        val enabledConfigKeys: List<String>,
    )

    /** [key] identifies the original rule; no caller-owned config is returned or mutated. */
    data class Selection(
        val name: String,
        val priority: Int,
        val key: String,
        val order: Double,
        val matched: List<String?>,
    )

    /**
     * Traverses rules in caller iteration order, skipping reserved "matched".
     * Highest numeric order wins per implementation name; first wins ties.
     * Replacements keep their original position before a stable ascending priority sort.
     *
     * Rule values must be config maps. Non-map values are ignored. Orders must be
     * finite numbers or null/absent (zero); unsupported order values are rejected.
     */
    fun select(
        path: String,
        rules: Map<String, Any?>,
        implementations: List<Implementation>,
    ): List<Selection> {
        val selected = mutableListOf<Selection>()
        val positions = mutableMapOf<String, Int>()
        for ((key, value) in rules) {
            if (key == "matched") continue
            val matched = isMatched(path, key) ?: continue
            val config = value as? Map<*, *> ?: continue
            if (isExcluded(path, config["exclusions"])) continue
            val orderValue = config["order"]
            require(orderValue == null || orderValue is Number) { "Rule order must be a finite number" }
            val order = (orderValue as? Number)?.toDouble() ?: 0.0
            require(order.isFinite()) { "Rule order must be a finite number" }
            for (implementation in implementations) {
                if (!implementation.enabledConfigKeys.any { isTruthy(config[it]) }) continue
                val position = positions[implementation.name]
                if (position != null && selected[position].order >= order) continue
                val selection = Selection(
                    implementation.name, implementation.priority, key, order, matched.toList(),
                )
                if (position == null) {
                    positions[implementation.name] = selected.size
                    selected.add(selection)
                } else {
                    selected[position] = selection
                }
            }
        }
        return selected.sortedBy { it.priority }
    }

    private fun isTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
        is List<*>, is Map<*, *> -> true
        else -> false
    }
}
