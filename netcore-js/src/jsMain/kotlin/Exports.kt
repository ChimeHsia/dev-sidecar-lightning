@file:OptIn(ExperimentalJsExport::class)

import com.dsha.sidecar.net.config.ConfigMerge
import com.dsha.sidecar.net.dns.Nat64Address
import com.dsha.sidecar.net.rules.HostnameMatcher
import kotlin.js.Json

@JsExport
class CompiledHostnameMatcher(keys: Array<String>, present: Array<Boolean>) {
    private val matcher = HostnameMatcher(
        keys.mapIndexed { index, key -> key to if (present[index]) index else null }.toMap(),
    )

    fun match(hostname: String): Int? = matcher.match(hostname)
}

@JsExport
fun createHostnameMatcher(keys: Array<String>, present: Array<Boolean>): CompiledHostnameMatcher =
    CompiledHostnameMatcher(keys, present)

@JsExport
fun mergeConfig(baseJson: String, overlayJson: String): String =
    JSON.stringify(toJs(ConfigMerge.merge(parseObject(baseJson), parseObject(overlayJson))))

@JsExport
fun sanitizeConfig(json: String): String =
    JSON.stringify(toJs(ConfigMerge.sanitize(parseObject(json), preserveArraySlots = true)))

@JsExport
fun toNat64Address(prefix: String?, ipv4: String?): String? =
    Nat64Address.toNat64Address(prefix, ipv4)

internal fun parseObject(json: String): Map<String, Any?> {
    val value = fromJs(JSON.parse<dynamic>(json))
    require(value is Map<*, *>) { "Expected a JSON object" }
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
}

private fun fromJs(value: dynamic): Any? = when {
    value == null -> null
    jsTypeOf(value) == "string" -> value as String
    jsTypeOf(value) == "boolean" -> value as Boolean
    jsTypeOf(value) == "number" -> value as Double
    js("Array.isArray(value)") as Boolean ->
        (value as Array<dynamic>).map(::fromJs)
    else -> {
        val keys: Array<String> = js("Object.keys(value)")
        keys.associateWith { fromJs(value[it]) }
    }
}

internal fun toJs(value: Any?): dynamic = when (value) {
    is Map<*, *> -> {
        // A null prototype also preserves JSON keys such as "__proto__".
        val obj: Json = js("Object.create(null)")
        value.forEach { (key, item) -> obj[key as String] = toJs(item) }
        obj
    }
    is List<*> -> value.map(::toJs).toTypedArray()
    else -> value
}
