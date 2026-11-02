package com.dsha.sidecar.net.config.native

import java.nio.file.Files
import java.nio.file.Path

/** Test-only bridge: Node independently compares values and every object-key sequence. */
object NativeConfigOracleMain {
    @JvmStatic fun main(args: Array<String>) {
        val parser = BoundedJson5()
        val input = parser.parseObject(Files.readAllBytes(Path.of(args[0])))
        val bootstrap = input["bootstrap"] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val defaults = RemoteFetchDefaults(
            bootstrap["dohUrl"] as String, bootstrap["providerName"] as String,
            bootstrap["providerHostMatch"] as String,
            bootstrap["presetIpLists"] as Map<String, Any?>,
        )
        fun rows(name: String): List<Map<*, *>> = (input[name] as List<*>).map { it as Map<*, *> }
        fun result(id: Any?, value: Any?): Map<String, Any?> =
            linkedMapOf("id" to id, "value" to value, "orders" to orders(value))
        val output = linkedMapOf(
            "parse" to rows("parse").map { row ->
                result(row["id"], parser.parseObject(row["source"] as String))
            },
            "reject" to rows("reject").map { row ->
                val rejected = try {
                    parser.parseObject(row["source"] as String)
                    false
                } catch (_: ConfigRejected) { true }
                mapOf("id" to row["id"], "rejected" to rejected)
            },
            "layers" to rows("layers").map { row ->
                val documents = row["documents"] as Map<*, *>
                val snapshot = applied(NativeConfigRepository(remoteFetchDefaults = defaults).reload(
                    ConfigDocuments(
                        documents["defaults"] as String, documents["user"] as String?,
                        documents["personal"] as String?, documents["shared"] as String?,
                    ),
                ))
                result(row["id"], snapshot.configuration)
            },
        )
        Files.writeString(Path.of(args[1]), OracleJson.write(output))
    }

    private fun orders(value: Any?, pointer: String = "$", rows: MutableList<Any?> = mutableListOf()): List<Any?> {
        when (value) {
            is Map<*, *> -> {
                rows.add(listOf(pointer, value.keys.toList()))
                for ((key, item) in value) orders(item, "$pointer/$key", rows)
            }
            is List<*> -> value.forEachIndexed { index, item -> orders(item, "$pointer/$index", rows) }
        }
        return rows
    }
}

/** Emits JSON for the external oracle, without using the parser under test. */
internal object OracleJson {
    fun write(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> if (value.toDouble() == 0.0) "0" else value.toString()
        is String -> buildString {
            append('"')
            for (char in value) when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
            append('"')
        }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { write(it.key) + ":" + write(it.value) }
        is List<*> -> value.joinToString(",", "[", "]", transform = ::write)
        else -> error("Unexpected synthetic oracle type")
    }
}
