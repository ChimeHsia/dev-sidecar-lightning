@file:OptIn(ExperimentalJsExport::class)

import com.dsha.sidecar.net.dns.DynamicChoice
import com.dsha.sidecar.net.http.RequestTransform
import com.dsha.sidecar.net.rules.HostnameAllMatcher
import com.dsha.sidecar.net.rules.PathRuleMatcher

// Experimental, explicit entry points for offline differential tests.
// The Android Node adapter continues to call only the first-wave exports.
@JsExport
fun wave2HostnameAll(entriesJson: String, hostname: String): String {
    val result = HostnameAllMatcher(parseObject(entriesJson)).matchWithMetadata(hostname)
    val metadata = result?.captureMetadata
    return encode(linkedMapOf(
        "values" to result?.values,
        "captureMetadata" to metadata?.let {
            linkedMapOf("groups" to it.groups, "index" to it.index, "input" to it.input)
        },
    ))
}

@JsExport
fun wave2PathMatch(inputJson: String): String {
    val input = parseObject(inputJson)
    val path = input.string("path")
    return encode(linkedMapOf(
        "matched" to PathRuleMatcher.isMatched(path, input["pattern"]),
        "excluded" to PathRuleMatcher.isExcluded(path, input["exclusions"]),
    ))
}

@JsExport
fun wave2PathSelect(inputJson: String): String {
    val input = parseObject(inputJson)
    val implementations = input.list("implementations").map { item ->
        val value = objectMap(item)
        PathRuleMatcher.Implementation(
            name = value.string("name"),
            priority = value.integer("priority"),
            enabledConfigKeys = value.list("enabledConfigKeys").map { it as String },
        )
    }
    val result = PathRuleMatcher.select(input.string("path"), objectMap(input["rules"]), implementations)
    return encode(result.map {
        linkedMapOf(
            "name" to it.name, "priority" to it.priority, "key" to it.key,
            "order" to it.order, "matched" to it.matched,
        )
    })
}

@JsExport
fun wave2ChoiceTrace(inputJson: String): String {
    val input = parseObject(inputJson)
    val choice = DynamicChoice("wave-two-offline")
    choice.setBackupList(input.list("candidates").map { it as String })
    val returns = mutableListOf<Boolean>()
    for (item in input.list("operations")) {
        val operation = objectMap(item)
        val args = operation.list("args")
        when (operation.string("method")) {
            "setBackupList" -> choice.setBackupList((args[0] as List<*>).map { it as String })
            "countStart" -> choice.countStart(args[0] as String)
            "doCount" -> choice.doCount(args[0] as String, args.getOrNull(1) as? Boolean ?: false)
            "doRank" -> choice.doRank()
            "resetChoice" -> returns.add(choice.resetChoice(args[0] as String))
            else -> throw IllegalArgumentException("Unsupported choice operation")
        }
    }
    val snapshot = choice.snapshot()
    return encode(linkedMapOf(
        "value" to snapshot.value,
        "backupList" to snapshot.backupList,
        "counts" to snapshot.counts.mapValues { (_, count) ->
            linkedMapOf(
                "total" to count.total, "error" to count.error,
                "keepErrorCount" to count.keepErrorCount, "successRate" to count.successRate,
            )
        },
        "resetResults" to returns,
    ))
}

@JsExport
fun wave2RequestTransform(inputJson: String): String {
    val input = parseObject(inputJson)
    val result = RequestTransform.transform(
        url = input.string("url"),
        ssl = input["ssl"] as Boolean,
        method = input.string("method"),
        headers = objectMap(input["headers"]),
    )
    return encode(linkedMapOf(
        "protocol" to result.protocol, "hostname" to result.hostname,
        "port" to result.port, "path" to result.path, "headers" to result.headers,
    ))
}

private fun Map<String, Any?>.string(key: String): String =
    get(key) as? String ?: throw IllegalArgumentException("Expected string: $key")

private fun Map<String, Any?>.list(key: String): List<*> =
    get(key) as? List<*> ?: throw IllegalArgumentException("Expected list: $key")

private fun Map<String, Any?>.integer(key: String): Int {
    val value = (get(key) as? Number)?.toDouble()
    require(value != null && value.isFinite() && value == value.toInt().toDouble()) {
        "Expected a 32-bit integer: $key"
    }
    return value.toInt()
}

@Suppress("UNCHECKED_CAST")
private fun objectMap(value: Any?): Map<String, Any?> {
    require(value is Map<*, *>) { "Expected JSON object" }
    return value as Map<String, Any?>
}

private fun encode(value: Any?): String = JSON.stringify(toJs(value))
