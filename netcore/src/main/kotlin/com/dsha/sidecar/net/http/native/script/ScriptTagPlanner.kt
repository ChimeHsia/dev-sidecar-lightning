// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.script

import java.net.URI
import com.dsha.sidecar.net.http.native.ActionHeader
import com.dsha.sidecar.net.http.native.ActionResponseHead
import com.dsha.sidecar.net.rules.native.ResponseAction
import com.dsha.sidecar.net.rules.native.RuleRequest

enum class ScriptCspMode { PRESERVE, EXPLICIT_UPSTREAM_SELF_COMPAT }

class ScriptTagPlanner(
    private val catalog: ScriptCatalog,
    private val references: ScriptReferencePolicy = ScriptReferencePolicy(),
    private val limits: ScriptLimits = ScriptLimits(),
    private val cspMode: ScriptCspMode = ScriptCspMode.PRESERVE,
) {
    fun plan(
        request: RuleRequest,
        head: ActionResponseHead,
        action: ResponseAction.ScriptInjection,
        enabled: Boolean,
    ): ScriptTagDecision {
        try {
            ScriptSafety.validateHead(head)
            if (!enabled) return ScriptTagDecision.Skipped(ScriptSkip.DISABLED)
            if (request.host == "github.com" && request.headers["turbo-frame"] == "repo-content-turbo-frame") {
                return ScriptTagDecision.Skipped(ScriptSkip.GITHUB_TURBO_FRAME)
            }
            val type = ScriptSafety.first(head.headers, "content-type")
            // Preserve script.js's exact text/html gate, not middleware's broader XHTML gate.
            if (type == null || !type.contains("text/html")) return ScriptTagDecision.Skipped(
                ScriptSkip.NON_HTML, listOf(ActionHeader("DS-Script-Interceptor", "Not text/html")),
            )
            val rawKeys = when (val script = action.script) {
                is String -> listOf(script)
                is List<*> -> script
                else -> throw ScriptInjectionException(ScriptProblem.INVALID_CONFIGURATION)
            }
            if (rawKeys.size > limits.maximumScriptsPerResponse) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
            val notices = mutableListOf<ScriptNotice>()
            val urls = mutableListOf<String>()
            for (raw in rawKeys) {
                val key = raw as? String ?: throw ScriptInjectionException(ScriptProblem.INVALID_CONFIGURATION)
                if (key in listOf("global", "tampermonkey")) continue
                if ('/' in key) {
                    references.authorize(key)
                    urls.add(key)
                } else {
                    if (!ScriptSafety.key(key)) throw ScriptInjectionException(ScriptProblem.INVALID_REFERENCE)
                    if (asset(key) == null) notices.add(ScriptNotice.MISSING_OPTIONAL_ASSET)
                    else urls.add(BUILTIN_PREFIX + key)
                }
            }
            if (urls.isEmpty()) return ScriptTagDecision.Skipped(ScriptSkip.NO_TAGS)
            val bootstrap = when (val custom = action.tampermonkeyScript) {
                is String -> custom.also { references.authorize(it) }
                null -> {
                    if (asset("tampermonkey") == null) throw ScriptInjectionException(ScriptProblem.MISSING_ASSET)
                    BUILTIN_PREFIX + "tampermonkey"
                }
                else -> throw ScriptInjectionException(ScriptProblem.INVALID_CONFIGURATION)
            }
            urls.add(0, bootstrap)
            if (request.host == "github.com") {
                references.authorize(GITHUB_COMPAT_URL)
                urls.add(GITHUB_COMPAT_URL)
            }
            val nonce = cspNonce(head, urls, request, notices)
            val tagText = urls.joinToString("") {
                "\r\n\t<script crossorigin=\"anonymous\" defer=\"defer\" type=\"application/javascript\" src=\"$it\"$nonce></script>"
            } + "\r\n"
            if (tagText.length > limits.maximumTagBytes || ScriptSafety.utf8(tagText).size > limits.maximumTagBytes) {
                throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
            }
            return ScriptTagDecision.Ready(
                tagText, notices, listOf(ActionHeader("DS-Script-Interceptor", "true")),
                ScriptSafety.binding(request, head), cspMode == ScriptCspMode.EXPLICIT_UPSTREAM_SELF_COMPAT,
            )
        } catch (failure: ScriptInjectionException) {
            return ScriptTagDecision.Blocked(failure.problem)
        } catch (_: Exception) {
            return ScriptTagDecision.Blocked(ScriptProblem.CATALOG_FAILURE)
        }
    }

    private fun asset(key: String): CatalogScript? {
        val result = catalog.lookup(key)
        if (result != null && result.byteSize > limits.maximumAssetBytes) throw ScriptInjectionException(ScriptProblem.BODY_LIMIT)
        return result
    }

    private fun cspNonce(
        head: ActionResponseHead,
        urls: List<String>,
        request: RuleRequest,
        notices: MutableList<ScriptNotice>,
    ): String {
        val enforced = ScriptSafety.values(head.headers, "content-security-policy")
        val report = ScriptSafety.values(head.headers, "content-security-policy-report-only")
        if (enforced.size > 1 || report.size > 1) throw ScriptInjectionException(ScriptProblem.BLOCKING_CSP)
        val chosen = enforced.firstOrNull()?.takeIf { it.isNotEmpty() } ?: report.firstOrNull()
        val match = chosen?.let { Regex("['\"]nonce-([^'\"]+)['\"]").find(it) }
        val nonce = match?.groupValues?.get(1)
        if (nonce != null && (nonce.length !in 1..256 || !nonce.matches(Regex("[A-Za-z0-9+/_-]+={0,2}")))) {
            throw ScriptInjectionException(ScriptProblem.UNSAFE_NONCE)
        }
        val policy = enforced.firstOrNull()
        if (!policy.isNullOrEmpty()) {
            val directives = policy.split(';').map { it.trim().split(Regex("\\s+")) }.filter { it.firstOrNull()?.isNotEmpty() == true }
            val sandbox = directives.firstOrNull { it.first().equals("sandbox", true) }
            if (sandbox != null && "allow-scripts" !in sandbox) throw ScriptInjectionException(ScriptProblem.BLOCKING_CSP)
            val source = listOf("script-src-elem", "script-src", "default-src").firstNotNullOfOrNull { name ->
                directives.firstOrNull { it.first().equals(name, true) }?.drop(1)
            }
            val validNonce = nonce != null && source?.contains("'nonce-$nonce'") == true
            if (source != null && !validNonce && cspMode == ScriptCspMode.PRESERVE) {
                for (url in urls) {
                    val uri = URI(url)
                    val local = url.startsWith("/") || uri.scheme == request.scheme && uri.host.equals(request.host, true) &&
                        (if (uri.port == -1) 443 else uri.port) == request.port
                    val origin = if (uri.host != null) "${uri.scheme}://${uri.host}" else ""
                    if (!(local && "'self'" in source || !local && origin in source)) {
                        throw ScriptInjectionException(ScriptProblem.BLOCKING_CSP)
                    }
                }
                if ("'strict-dynamic'" in source) throw ScriptInjectionException(ScriptProblem.BLOCKING_CSP)
            }
            if (!validNonce && cspMode == ScriptCspMode.EXPLICIT_UPSTREAM_SELF_COMPAT) {
                notices.add(ScriptNotice.EXISTING_CSP_REQUIRES_REVIEW)
            }
        }
        return nonce?.let { " nonce=\"$it\"" } ?: ""
    }

    companion object {
        const val BUILTIN_PREFIX = "/____ds_script____/"
        const val PROXY_PREFIX = "/____ds_script_proxy____/"
        const val GITHUB_COMPAT_URL = "https://github.githubassets.com/assets/fakefile.js"
    }
}
