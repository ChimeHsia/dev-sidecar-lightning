package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.script.*
import com.dsha.sidecar.net.proxy.http.*
import com.dsha.sidecar.net.proxy.native.*
import com.dsha.sidecar.net.rules.native.*
import java.io.ByteArrayInputStream
import java.util.Locale

/** Immutable assets and exact remote references derived from the same accepted configuration epoch. */
class NativeRuntimeScripts(
    config: NativeRuntimeConfig,
    sources: Map<String, CatalogSource> = emptyMap(),
    private val limits: ScriptLimits = ScriptLimits(),
) {
    private val catalog = ReadOnlyScriptCatalog(sources, limits)
    private val planner: ScriptTagPlanner
    private val injector = HtmlScriptInjector(limits)

    init {
        val references = linkedSetOf(ScriptTagPlanner.GITHUB_COMPAT_URL)
        val server = NativeRuntimeConfig.objectAt(config.decoded, "server").ifEmpty { config.decoded }
        for (paths in NativeRuntimeConfig.objectAt(server, "intercepts").values) {
            for (raw in PlannerJson.obj(paths).values) {
                val rule = PlannerJson.obj(raw)
                for (key in listOf("script", "tampermonkeyScript")) {
                    val value = rule[key]
                    for (entry in if (value is List<*>) value else listOf(value)) {
                        if (entry is String && '/' in entry) {
                            references.add(entry)
                            references.add(localReference(entry))
                        }
                    }
                }
            }
        }
        planner = ScriptTagPlanner(catalog, ScriptReferencePolicy(references), limits,
            ScriptCspMode.EXPLICIT_UPSTREAM_SELF_COMPAT)
    }

    fun prepare(
        request: RuleRequest,
        plan: RequestPlan,
        head: Http1ResponseHead,
        entity: NativeResponseEntity,
        enabled: Boolean,
    ): NativePreparedResponse? {
        if (!enabled || request.method == "HEAD" || head.status in setOf(204, 205, 304)) return null
        val actions = plan.responseSteps.mapNotNull { it.action as? ResponseAction.ScriptInjection }
        if (actions.isEmpty()) return null
        val actionHead = ActionResponseHead(head.status, head.headers.fields.map {
            ActionHeader(it.name, it.value)
        }, head.reason)
        // Merge tags before consuming the entity so multiple configured injections remain atomic.
        val ready = actions.map { action ->
            val script = when (val value = action.script) {
                is String -> localReference(value)
                is List<*> -> value.map { if (it is String) localReference(it) else it }
                else -> value
            }
            val bootstrap = (action.tampermonkeyScript as? String)?.let(::localReference)
                ?: action.tampermonkeyScript
            planner.plan(request, actionHead, action.copy(script = script, tampermonkeyScript = bootstrap), true)
        }
        ready.filterIsInstance<ScriptTagDecision.Blocked>().firstOrNull()?.let {
            throw ScriptInjectionException(it.problem)
        }
        val tags = ready.filterIsInstance<ScriptTagDecision.Ready>()
        if (tags.isEmpty()) return null
        val bytes = entity.read(limits.maximumEncodedBytes)
        // The injector validates the original head/length against the complete dechunked entity.
        val merged = ScriptTagDecision.Ready(tags.joinToString("") { it.headText },
            tags.flatMap { it.notices }, tags.flatMap { it.diagnosticHeaders },
            tags.first().binding, tags.any { it.repairCsp })
        return when (val prepared = injector.prepare(request, actionHead, merged, ByteArrayInputStream(bytes))) {
            is ScriptResponseDecision.Prepared -> NativePreparedResponse(
                head.copy(headers = Http1Headers(prepared.response.head.headers.map {
                    Http1Header(it.name, " " + it.value)
                })), prepared.response.bodyBytes())
            is ScriptResponseDecision.Blocked -> throw ScriptInjectionException(prepared.problem)
            is ScriptResponseDecision.PassThrough -> NativePreparedResponse(
                head.copy(headers = Http1Headers(prepared.head.headers.filterNot {
                    it.name.equals("Transfer-Encoding", true) || it.name.equals("Trailer", true) ||
                        it.name.equals("Content-Length", true)
                }.map { Http1Header(it.name, " " + it.value) } +
                    Http1Header("Content-Length", " ${bytes.size}"))), bytes)
        }
    }

    private fun localReference(value: String): String =
        if (value.startsWith("https:") || value.startsWith("http:"))
            ScriptTagPlanner.PROXY_PREFIX + value.replaceFirst(".js", "")
                .replace(Regex("[^A-Za-z0-9]+"), "_") + ".js" else value
}
