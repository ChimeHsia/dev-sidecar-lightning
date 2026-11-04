@file:OptIn(ExperimentalJsExport::class)

import com.dsha.sidecar.net.rules.HostnameAllMatcher
import com.dsha.sidecar.net.rules.PathRuleMatcher

/**
 * Config crosses JSON once, on the first All query for a compiled host map.
 * Results are native JS objects: deletion holes and RegExp array properties
 * cannot survive a JSON round trip.
 */
@JsExport
class CompiledHostnameAllMatcher(entriesJson: String) {
    private val entries = parseObject(entriesJson)
    private val matcher = HostnameAllMatcher(entries)
    private val namedPatterns = runtimeNamedPatterns(entries)

    fun match(hostname: String): dynamic {
        val result = matcher.matchWithMetadata(hostname) ?: return null
        val values = runtimeValue(result.values)
        val metadata = result.captureMetadata
        if (metadata != null) {
            val captured = values.matched
            if (metadata.index != null) captured.index = metadata.index
            if (metadata.input != null) captured.input = metadata.input
            captured.groups = if (metadata.groups == null) {
                js("void 0")
            } else {
                // The pure matcher omits unmatched names at its JSON boundary.
                // Native RegExp groups retain those names with undefined values.
                val names = namedPatterns.asReversed().firstOrNull {
                    it.first.find(hostname) != null
                }?.second ?: error("Missing hostname capture metadata")
                // A single RegExp result has a null-prototype groups object.
                // Upstream's lodash merge creates {} after array concatenation.
                val groups: dynamic = if (metadata.index == null) js("({})") else js("Object.create(null)")
                for (name in names.keys) {
                    groups[name] = metadata.groups[name] ?: js("void 0")
                }
                groups
            }
        }
        return values
    }
}

@JsExport
fun createHostnameAllMatcher(entriesJson: String): CompiledHostnameAllMatcher =
    CompiledHostnameAllMatcher(entriesJson)

/**
 * Keep PathRuleMatcher as the authority for matching and supported input types.
 * Its pure list API omits RegExp metadata, so a second Kotlin Regex query
 * supplies the index and names needed by upstream interceptor placeholders.
 */
@JsExport
fun matchPathRule(path: String, pattern: dynamic): dynamic {
    val value: Any? = when (jsTypeOf(pattern)) {
        "string" -> pattern as String
        "boolean" -> pattern as Boolean
        else -> return null
    }
    val captures = PathRuleMatcher.isMatched(path, value) ?: return null
    val result: dynamic = js("[]")
    for ((index, capture) in captures.withIndex()) {
        // Path matching returns present undefined slots; hostname cleanup
        // instead deletes such slots, which runtimeValue restores as holes.
        result[index] = capture ?: js("void 0")
    }
    if (value == true || value == "true" || value == "*" || value == ".*") return result
    val patternString = value as String
    val expression = if (patternString.firstOrNull() in listOf('*', '?', '+')) {
        ".$patternString"
    } else {
        patternString
    }
    val match = Regex(expression).find(path) ?: error("Missing path capture metadata")
    result.index = match.range.first
    result.input = path
    val names = runtimeGroupNames(expression)
    result.groups = if (names.isEmpty()) {
        js("void 0")
    } else {
        val groups: dynamic = js("Object.create(null)")
        for ((name, index) in names) groups[name] = captures[index] ?: js("void 0")
        groups
    }
    return result
}

private fun runtimeValue(value: Any?): dynamic = when (value) {
    is Map<*, *> -> {
        val result: dynamic = js("({})")
        for ((key, item) in value) {
            // HostnameAllMatcher discards __proto__ recursively.
            result[key as String] = runtimeValue(item)
        }
        result
    }
    is List<*> -> {
        val result: dynamic = js("[]")
        result.length = value.size
        for ((index, item) in value.withIndex()) {
            if (item != null) result[index] = runtimeValue(item)
        }
        result
    }
    else -> value
}

private fun runtimeNamedPatterns(entries: Map<String, Any?>): List<Pair<Regex, Map<String, Int>>> {
    val expressions = linkedMapOf<String, Map<String, Int>>()
    for (key in entries.keys) {
        val domain = if (key.startsWith('.')) {
            if (entries["*$key"] != null) continue
            "*$key"
        } else {
            key
        }
        if ('*' !in domain && !domain.startsWith('^')) continue
        val expression = if (domain.startsWith('^')) domain else {
            "^${domain.replace(".", "\\.").replace("*", ".*")}$"
        }
        expressions[expression] = runtimeGroupNames(expression)
    }
    return expressions.mapNotNull { (expression, names) ->
        if (names.isEmpty()) return@mapNotNull null
        try {
            Regex(expression) to names
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

// Same supported named-group grammar as HostnameAllMatcher. Escaped parentheses,
// character classes, noncapturing groups, and lookbehind do not add capture slots.
private fun runtimeGroupNames(expression: String): Map<String, Int> {
    val names = linkedMapOf<String, Int>()
    var escaped = false
    var characterClass = false
    var group = 0
    for (index in expression.indices) {
        val char = expression[index]
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == '[' -> characterClass = true
            char == ']' -> characterClass = false
            char == '(' && !characterClass -> {
                if (expression.getOrNull(index + 1) != '?') {
                    group++
                } else if (expression.getOrNull(index + 2) == '<' &&
                    expression.getOrNull(index + 3) != '=' &&
                    expression.getOrNull(index + 3) != '!'
                ) {
                    group++
                    val end = expression.indexOf('>', index + 3)
                    if (end >= 0) names[expression.substring(index + 3, end)] = group
                }
            }
        }
    }
    return names
}
