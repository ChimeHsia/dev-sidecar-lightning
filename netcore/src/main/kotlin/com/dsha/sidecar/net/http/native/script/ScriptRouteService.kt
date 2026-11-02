// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.script

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.RuleRequest

/**
 * Catalog routes only. Proxy-prefix aliases must bind to known catalog keys; they never fetch URLs.
 * Serve only after the runtime has admitted the host/request under its trusted script configuration.
 */
class ScriptRouteService(
    private val catalog: ScriptCatalog,
    proxyAliases: Map<String, String> = emptyMap(),
    private val limits: ScriptLimits = ScriptLimits(),
) {
    private val aliases = Collections.unmodifiableMap(proxyAliases.toMap())
    init {
        require(aliases.size <= limits.maximumCatalogEntries)
        aliases.forEach { (path, key) ->
            ScriptSafety.reference(path)
            require(path.startsWith(ScriptTagPlanner.PROXY_PREFIX) && '?' !in path && ScriptSafety.key(key))
        }
    }

    fun serve(request: RuleRequest, enabled: Boolean, nowMillis: Long): ScriptRouteDecision {
        if (!enabled || !request.path.startsWith(ScriptTagPlanner.BUILTIN_PREFIX) &&
            !request.path.startsWith(ScriptTagPlanner.PROXY_PREFIX)) return ScriptRouteDecision.NotHandled
        try {
            ScriptSafety.reference(request.path)
            if (request.method !in listOf("GET", "HEAD")) return served(405, "Method not allowed", listOf(ActionHeader("Allow", "GET, HEAD")), request.method)
            val key = if (request.path.startsWith(ScriptTagPlanner.BUILTIN_PREFIX)) {
                request.path.removePrefix(ScriptTagPlanner.BUILTIN_PREFIX).let { if (it == "global") "tampermonkey" else it }
                    .also { if (!ScriptSafety.key(it)) throw ScriptInjectionException(ScriptProblem.INVALID_REFERENCE) }
            } else aliases[request.path] ?: return served(404, "DevSidecar: script not found", emptyList(), request.method)
            val script = catalog.lookup(key) ?: return served(
                404, "DevSidecar: script '$key' not found", emptyList(), request.method,
            )
            if (script.byteSize > limits.maximumAssetBytes) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
            val bytes = script.bytes()
            val date = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
            fun format(time: Long) = date.format(Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC))
            val headers = listOf(
                ActionHeader("DS-Middleware", "ds_script"),
                ActionHeader("Content-Type", "application/javascript; charset=utf-8"),
                ActionHeader("Cache-Control", "public, max-age=86401, immutable"),
                ActionHeader("Last-Modified", format(nowMillis)),
                ActionHeader("Expires", format(Math.addExact(nowMillis, 86_400_000))),
                ActionHeader("Date", format(nowMillis)),
                ActionHeader("Content-Length", bytes.size.toString()),
                ActionHeader("X-Content-Type-Options", "nosniff"),
            )
            return ScriptRouteDecision.Served(ScriptResponse(
                ScriptSafety.snapshot(ActionResponseHead(200, headers)), if (request.method == "HEAD") ByteArray(0) else bytes,
            ))
        } catch (failure: ScriptInjectionException) {
            return ScriptRouteDecision.Blocked(failure.problem)
        } catch (_: Exception) {
            return ScriptRouteDecision.Blocked(ScriptProblem.CATALOG_FAILURE)
        }
    }

    private fun served(status: Int, text: String, extra: List<ActionHeader>, method: String): ScriptRouteDecision.Served {
        val bytes = ScriptSafety.utf8(text)
        val head = ActionResponseHead(status, listOf(
            ActionHeader("Content-Type", "text/plain; charset=utf-8"),
            ActionHeader("Content-Length", bytes.size.toString()),
            ActionHeader("X-Content-Type-Options", "nosniff"),
        ) + extra)
        return ScriptRouteDecision.Served(ScriptResponse(ScriptSafety.snapshot(head), if (method == "HEAD") ByteArray(0) else bytes))
    }
}
