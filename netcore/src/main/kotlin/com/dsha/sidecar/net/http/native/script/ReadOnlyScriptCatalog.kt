// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.script

import java.util.Collections

/** Caller supplies reviewed assets as data. No filenames, directory scans, filesystem, or remote reads. */
class ReadOnlyScriptCatalog(sources: Map<String, CatalogSource>, limits: ScriptLimits = ScriptLimits()) : ScriptCatalog {
    private val assets: Map<String, CatalogScript>
    init {
        if (sources.size > limits.maximumCatalogEntries) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
        var total = 0L
        val output = linkedMapOf<String, CatalogScript>()
        for ((key, source) in sources) {
            if (!ScriptSafety.key(key)) throw ScriptInjectionException(ScriptProblem.INVALID_REFERENCE)
            if (source.text.length > limits.maximumAssetBytes) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
            val text = if (source.format == CatalogFormat.USER_SCRIPT) UserScriptCompiler.compile(source.text, key) else source.text
            val bytes = ScriptSafety.utf8(text)
            total += bytes.size
            if (bytes.size > limits.maximumAssetBytes || total > limits.maximumCatalogBytes) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
            output[key] = CatalogScript(bytes)
        }
        assets = Collections.unmodifiableMap(output)
    }
    override fun lookup(key: String): CatalogScript? {
        if (!ScriptSafety.key(key)) throw ScriptInjectionException(ScriptProblem.INVALID_REFERENCE)
        return assets[key]
    }
}

/** Safe metadata subset of monkey.loadScript; output remains JavaScript data for the browser. */
object UserScriptCompiler {
    fun compile(content: String, scriptName: String): String {
        if (!ScriptSafety.key(scriptName) || content.length > 1_048_576) throw ScriptInjectionException(ScriptProblem.INVALID_CONFIGURATION)
        val sections = content.split("// ==/UserScript==")
        if (sections.size < 2) throw ScriptInjectionException(ScriptProblem.INVALID_CONFIGURATION)
        val metadata = linkedMapOf<String, String>()
        val grants = mutableListOf<String>()
        for (line in sections[0].split('\n')) {
            val found = Regex(".*@(\\S+)\\s(.+)").find(line) ?: continue
            val key = found.groupValues[1].trim()
            val value = found.groupValues[2].trim()
            if (key == "grant") grants.add(value) else metadata[key] = value
        }
        if (metadata.values.any { it.length > 4096 || it.any { char -> char == '"' || char == '\\' || char.code < 32 || char == '\u2028' || char == '\u2029' } } ||
            grants.size > 64 || grants.distinct().size != grants.size ||
            grants.any { !it.matches(Regex("GM_[A-Za-z0-9_]+|GM\\.[A-Za-z][A-Za-z0-9_]*")) }) {
            throw ScriptInjectionException(ScriptProblem.INVALID_CONFIGURATION)
        }
        val version = metadata["version"]?.takeIf { it.isNotEmpty() }?.let { "_$it" } ?: ""
        val key = "ds_$scriptName$version:"
        val event = if ((metadata["run-at"]?.takeIf { it.isNotEmpty() } ?: "document-end") == "document-end") {
            "document.addEventListener(\"DOMContentLoaded\""
        } else "window.addEventListener(\"load\""
        val options = listOf("name", "version", "icon").mapNotNull { name -> metadata[name]?.let { "\"$name\":${json(it)}" } }.joinToString(",")
        val init = "\nconst DS_init = (window.__ds_global__ || {})['DS_init']\n" +
            "if (typeof DS_init === 'function') {\n\tconsole.log(\"$key do DS_init\")\n\tDS_init({$options});\n" +
            "} else {\n\tconsole.log(\"$key has no DS_init\")\n}"
        val enabled = "\nif (!((window.__ds_global__ || {}).GM_getValue || (() => true))(\"ds_enabled\", true)) {\n" +
            "\tconsole.log(\"$key tampermonkey disabled\")\n\treturn\n}"
        val grant = grants.joinToString("\r\n") { name ->
            if ('.' in name) "$name = (window.__ds_global__ || {})['$name'];"
            else "const $name = (window.__ds_global__ || {})['$name'] || (() => {});"
        }
        return "$event, () => {$init\r\n$enabled\r\n\r\n" + (if (grant.isEmpty()) "" else "$grant\r\n\r\n") +
            sections[1].trim() + "\r\nconsole.log(\"$key completed\")\r\n})\r\nconsole.log(\"$key loaded\")"
    }

    private fun json(value: String): String = "\"" + value.flatMap { char ->
        when (char) {
            '"' -> "\\\"".toList()
            '\\' -> "\\\\".toList()
            else -> listOf(char)
        }
    }.joinToString("") + "\""
}
