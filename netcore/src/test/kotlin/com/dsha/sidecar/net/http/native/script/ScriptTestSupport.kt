package com.dsha.sidecar.net.http.native.script

import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.ResponseAction
import com.dsha.sidecar.net.rules.native.RuleRequest

internal fun syntheticCatalog(noBootstrap: Boolean = false): ReadOnlyScriptCatalog = ReadOnlyScriptCatalog(
    linkedMapOf(
        "alpha" to CatalogSource("/* synthetic alpha */"),
        "beta" to CatalogSource("/* synthetic beta */"),
    ).also { if (!noBootstrap) it["tampermonkey"] = CatalogSource("/* synthetic bootstrap */") },
)
internal fun scriptRequest() = RuleRequest("example.test", "/document")
internal val scriptHtml = "<html><head><title>caf\u00e9 \u4e2d\ud83d\ude80</title></head><body>content</body></html>".toByteArray()
internal fun scriptHead(bytes: ByteArray = scriptHtml, extra: List<ActionHeader> = emptyList(), status: Int = 200) =
    ActionResponseHead(status, listOf(ActionHeader("Content-Type", "text/html; charset=utf-8"), ActionHeader("Content-Length", bytes.size.toString())) + extra)
internal fun readyTags(
    request: RuleRequest = scriptRequest(),
    head: ActionResponseHead = scriptHead(),
): ScriptTagDecision = ScriptTagPlanner(syntheticCatalog()).plan(request, head, ResponseAction.ScriptInjection(listOf("alpha"), null), true)
internal fun scriptHeader(head: ActionResponseHead, name: String): String? = head.headers.firstOrNull { it.name.equals(name, true) }?.value
