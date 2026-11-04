// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.rules.native

import java.util.Locale
import com.dsha.sidecar.net.config.ConfigMerge
import com.dsha.sidecar.net.http.HeaderTransforms
import com.dsha.sidecar.net.http.RequestRetryPolicy
import com.dsha.sidecar.net.rules.HostnameAllMatcher
import com.dsha.sidecar.net.rules.PathRuleMatcher

/** Pure planning over decoded layered configuration. No JSON parser, I/O, sockets, or clock. */
class RulePlanner(layers: List<Map<String, Any?>>) {
    private val server: Map<String, Any?>
    private val hostMatcher: HostnameAllMatcher
    private val audit: ConfigurationAudit

    init {
        require(layers.size <= 64) { "Too many configuration layers" }
        var merged: Map<String, Any?> = emptyMap()
        for (layer in layers) merged = ConfigMerge.merge(merged, PlannerJson.obj(PlannerJson.snapshot(layer)))
        merged = ConfigMerge.sanitize(merged, preserveArraySlots = true)
        require(!merged.containsKey("server") || merged["server"] is Map<*, *>) { "server must be an object" }
        val input = if (merged.containsKey("server")) PlannerJson.obj(merged["server"]) else merged
        server = PlannerJson.obj(PlannerJson.snapshot(input))
        require(!server.containsKey("intercepts") || server["intercepts"] is Map<*, *>) { "intercepts must be an object" }
        val intercepts = PlannerJson.obj(server["intercepts"])
        for ((_, paths) in intercepts) {
            require(paths is Map<*, *> && paths.entries.all { it.key == "matched" || it.value is Map<*, *> }) {
                "Hostname and path entries must contain configuration objects"
            }
        }
        hostMatcher = HostnameAllMatcher(RuleTargets.scriptRoutes(intercepts))
        val issues = mutableListOf<PlanningIssue>()
        for ((hostname, paths) in intercepts) {
            if ('*' in hostname || hostname.startsWith('^') || hostname.startsWith('.')) {
                val expression = if (hostname.startsWith('^')) hostname else {
                    val domain = if (hostname.startsWith('.')) "*$hostname" else hostname
                    "^${domain.replace(".", "\\.").replace("*", ".*")}$"
                }
                if (runCatching { Regex(expression) }.isFailure) issues.add(PlanningIssue(
                    IssueKind.UNSUPPORTED_PATTERN, hostname, null, "hostname",
                    "Hostname pattern cannot be represented by the JVM regex prerequisite",
                ))
            }
            for ((pattern, config) in PlannerJson.obj(paths)) {
                if (pattern != "matched") issues.addAll(configIssues(hostname, pattern, PlannerJson.obj(config)))
            }
        }
        if (PlannerJson.truthy(PlannerJson.obj(setting()["script"])["enabled"])) {
            issues.add(PlanningIssue(
                IssueKind.GLOBAL_SCRIPT_MIDDLEWARE, null, null, "setting.script.enabled",
                "InsertScriptMiddleware execution is outside this planner",
            ))
        }
        audit = ConfigurationAudit(issues.toList())
    }

    fun audit(): ConfigurationAudit = ConfigurationAudit(audit.issues.toList())

    @JvmOverloads
    fun plan(
        host: String,
        path: String,
        method: String = "GET",
        headers: Map<String, Any?> = emptyMap(),
        scheme: String = "https",
        port: Int = if (scheme == "https") 443 else 80,
    ): RequestPlan = plan(RuleRequest(host, path, method, headers, scheme, port))

    fun plan(request: RuleRequest): RequestPlan {
        require(request.host.isNotEmpty() && request.host.length <= 8192 &&
            request.path.length <= 8192 && request.scheme in listOf("http", "https") &&
            request.port in 1..65535 && request.method.matches(Regex("[A-Z]+"))) { "Invalid request metadata" }
        val issues = audit.issues.filter { it.hostnamePattern == null }.toMutableList()
        val matchedHost = hostMatcher.matchWithMetadata(request.host)
        val rules = matchedHost?.values.orEmpty()
        val ech = isEch(request.host)
        val hostCaptures = (rules["matched"] as? List<*>)?.map { it as? String }
        val candidates = linkedMapOf<String, SelectedImplementation>()
        for (key in PlannerJson.keys(rules)) {
            if (key == "matched") continue
            val config = PlannerJson.obj(rules[key])
            issues.addAll(configIssues(request.host, key, config).filter { it.kind == IssueKind.UNSUPPORTED_PATTERN })
            val captures = PathRuleMatcher.isMatched(request.path, key) ?: continue
            if (PathRuleMatcher.isExcluded(request.path, config["exclusions"])) continue
            val orderValue = config["order"]
            require(orderValue == null || orderValue is Number) { "Rule order must be a finite number" }
            val order = (orderValue as? Number)?.toDouble() ?: 0.0
            require(order.isFinite()) { "Rule order must be finite" }
            issues.addAll(configIssues(request.host, key, config))
            for ((name, priority) in implementations) {
                if (name == "sni" && ech) continue
                if (!enabled(name, config)) continue
                if (candidates[name]?.let { it.order >= order } == true) continue
                candidates[name] = SelectedImplementation(
                    name, priority, key, order, captures, PlannerJson.namedCaptures(request.path, key),
                    hostCaptures, matchedHost?.captureMetadata?.groups,
                )
            }
        }
        val selected = candidates.values.sortedBy { it.priority }
        var currentPath = request.path
        var headers = linkedMapOf<String, Any?>()
        for ((name, value) in PlannerJson.obj(PlannerJson.snapshot(request.headers))) {
            require(name.all { it.code in 33..126 }) { "Invalid header name" }
            headers[name.lowercase(Locale.ROOT)] = value
        }
        var route: RouteOverride? = null
        var sni: ServerName? = null
        var verifyHost: String? = null
        val trustReasons = mutableListOf<TrustReason>()
        if (setting()["verifySsl"] == false) trustReasons.add(TrustReason.SERVER_SETTING)
        var response: StaticResponse? = null
        var retry: RetryDescriptor? = null
        var download = false
        val requestSteps = mutableListOf<RequestStep>()
        fun tls(): TlsPolicy = TlsPolicy(
            (route?.scheme ?: request.scheme) == "https",
            sni ?: ServerName.Send(route?.host ?: request.host), verifyHost ?: route?.host ?: request.host,
            if (trustReasons.isEmpty()) TrustMode.VERIFY_CHAIN_AND_HOST else TrustMode.UPSTREAM_COMPAT_UNVERIFIED,
            trustReasons.distinct(),
        )
        for (source in selected.filter { it.priority < 200 }) {
            val config = PlannerJson.obj(rules[source.pathPattern])
            fun add(action: RequestAction) { requestSteps.add(RequestStep(source, action)) }
            try {
                when (source.name) {
                    "retry" -> {
                        retry = retry(config["retry"], request.copy(headers = headers))
                        add(RequestAction.Retry(retry))
                    }
                    "options" -> {
                        if (request.method == "OPTIONS" && PlannerJson.truthy(headers["origin"])) {
                            response = preflight(config, headers)
                        }
                        add(RequestAction.Preflight(response))
                        if (response != null) break
                    }
                    "success", "abort" -> {
                        response = staticResponse(source.name, config, headers)
                        add(RequestAction.Respond(response))
                        break
                    }
                    "cacheRequest" -> add(RequestAction.CacheLookup(cache(config)))
                    "redirect" -> {
                        val target = RuleTargets.build(request, currentPath, text(config["redirect"]), config, source)
                        val localHeaders = linkedMapOf<String, Any?>("Location" to target, "DS-Interceptor" to "redirect")
                        cors(localHeaders, headers)
                        val age = PlannerJson.maxAge(config)
                        if (age != null && age > 0) localHeaders["Cache-Control"] = "public, max-age=${PlannerJson.text(age + 1)}, immutable"
                        response = StaticResponse(StaticResponseKind.REDIRECT, 302, localHeaders, "")
                        add(RequestAction.Respond(response))
                        break
                    }
                    "requestReplace" -> {
                        val replacement = PlannerJson.obj(config["requestReplace"])
                        val changes = PlannerJson.obj(replacement["headers"])
                        headers = LinkedHashMap(HeaderTransforms.replaceRequestHeaders(headers, changes))
                        val strip = PlannerJson.truthy(replacement["doDownload"]) &&
                            Regex("DS_DOWNLOAD", RegexOption.IGNORE_CASE).containsMatchIn(currentPath)
                        if (strip) {
                            download = true
                            currentPath = currentPath.replace(Regex("[?&/]?DS_DOWNLOAD(=[^?&/]+)?$", RegexOption.IGNORE_CASE), "")
                        }
                        add(RequestAction.Transform(changes, strip))
                    }
                    "proxy" -> {
                        val proxy = text(config["proxy"])
                        val primary = RuleTargets.build(request, currentPath, proxy, config, source)
                        route = RuleTargets.route(primary)
                        currentPath = route.path
                        headers["host"] = route.authority
                        if (config["verifyHost"] != null && config["verifyHost"] != "") verifyHost = text(config["verifyHost"])
                        if (PlannerJson.truthy(config["sni"])) {
                            sni = ServerName.Send(text(config["sni"]))
                            trustReasons.add(TrustReason.PROXY_INLINE_SNI)
                            if (ech) issues.add(PlanningIssue(
                                IssueKind.ECH_PROXY_SNI_CONFLICT, request.host, source.pathPattern, "proxy.sni",
                                "Upstream only ignores standalone sni; inline proxy sni conflicts with required ECH",
                            ))
                        } else if (config["unVerifySsl"] == true) {
                            trustReasons.add(TrustReason.EXPLICIT_UNVERIFY_SSL)
                        }
                        val alternatives = (config["backup"] as? List<*>)?.map { text(it) }.orEmpty()
                        val backup = if (alternatives.isEmpty()) null else {
                            BackupDescriptor(proxy, alternatives, "${request.host}/${source.pathPattern}")
                        }
                        add(RequestAction.Proxy(route, backup))
                        // proxy returns true: no later request implementation executes.
                        break
                    }
                    "sni" -> {
                        if (config["sni"] != null) {
                            val value = text(config["sni"])
                            sni = if (value.isEmpty()) ServerName.Suppress else ServerName.Send(value)
                        }
                        if (config["verifyHost"] != null && config["verifyHost"] != "") verifyHost = text(config["verifyHost"])
                        if (config["sni"] != "") trustReasons.add(TrustReason.SNI_IMPLEMENTATION)
                        add(RequestAction.Tls(tls()))
                        break
                    }
                    "unVerifySsl" -> {
                        trustReasons.add(TrustReason.EXPLICIT_UNVERIFY_SSL)
                        add(RequestAction.Tls(tls()))
                        break
                    }
                    else -> {
                        add(RequestAction.Unsupported(source.name, config[source.name]))
                        break
                    }
                }
            } catch (failure: IllegalArgumentException) {
                issues.add(PlanningIssue(
                    IssueKind.UNSUPPORTED_VALUE, request.host, source.pathPattern, source.name,
                    failure.message ?: "Unsupported action value",
                ))
                add(RequestAction.Unsupported(source.name, config[source.name]))
                break
            }
        }
        val responseSteps = mutableListOf<ResponseStep>()
        if (response == null) for (source in selected.filter { it.priority >= 200 }) {
            val config = PlannerJson.obj(rules[source.pathPattern])
            val action: ResponseAction = when (source.name) {
                "AfterOPTIONSHeaders" -> ResponseAction.Cors(config["optionsCrossPolicy"])
                "cacheResponse" -> ResponseAction.Cache(cache(config))
                "responseReplace" -> {
                    val replacement = PlannerJson.obj(config["responseReplace"])
                    ResponseAction.Replace(
                        PlannerJson.obj(replacement["headers"]), PlannerJson.truthy(replacement["doDownload"]) || download,
                        currentPath, (PlannerJson.maxAge(config) ?: 0.0) > 0,
                    )
                }
                "script" -> ResponseAction.ScriptInjection(config["script"], config["tampermonkeyScript"])
                else -> continue
            }
            responseSteps.add(ResponseStep(source, action))
            if (source.name == "responseReplace") {
                val extra = PlannerJson.obj(config["responseReplace"]).filterKeys { it !in setOf("headers", "doDownload") }
                if (extra.any { PlannerJson.truthy(it.value) }) {
                    responseSteps.add(ResponseStep(source, ResponseAction.UnsupportedBody(source.name, extra)))
                }
            }
        }
        return RequestPlan(
            selected.toList(), requestSteps.toList(), responseSteps.toList(), route, currentPath, headers.toMap(),
            tls(), response, retry, download, ech, issues.distinct(),
        )
    }

    private fun setting(): Map<String, Any?> = PlannerJson.obj(server["setting"])

    private fun enabled(name: String, config: Map<String, Any?>): Boolean = when (name) {
        "sni" -> !PlannerJson.truthy(config["proxy"]) && (config["sni"] != null || config["verifyHost"] != null)
        "unVerifySsl" -> config["unVerifySsl"] == true || config["ssl"] == false
        "cacheRequest", "cacheResponse" -> (PlannerJson.maxAge(config) ?: 0.0) > 0
        "AfterOPTIONSHeaders" -> PlannerJson.truthy(config["options"])
        else -> PlannerJson.truthy(config[name])
    }

    private fun isEch(host: String): Boolean {
        val dns = PlannerJson.obj(server["dns"])
        val ech = PlannerJson.obj(dns["ech"])
        val nat64 = PlannerJson.obj(dns["nat64"])
        val nat64Active = nat64["enabled"] == true && nat64["domains"] is List<*> &&
            (nat64["domains"] as List<*>).isNotEmpty() && (nat64["prefix"] as? String).orEmpty().trim().isNotEmpty()
        return (nat64Active && inDomains(nat64["domains"], host)) ||
            (ech["enabled"] != false && ech["use"] != false && inDomains(ech["domains"], host))
    }

    private fun inDomains(value: Any?, host: String): Boolean {
        val domains = when (value) {
            is List<*> -> value.filterIsInstance<String>().filter { it.isNotEmpty() }
            is Map<*, *> -> value.entries.filter { it.value != false && it.value != null }.map { it.key as String }
            else -> emptyList()
        }
        // Values are irrelevant; the same hostname grammar is used for ECH membership.
        return HostnameAllMatcher(domains.associateWith { mapOf("member" to true) }).match(host) != null
    }

    private fun configIssues(host: String, pattern: String, config: Map<String, Any?>): List<PlanningIssue> {
        val result = mutableListOf<PlanningIssue>()
        fun issue(action: String, message: String, kind: IssueKind = IssueKind.UNSUPPORTED_ACTION) {
            result.add(PlanningIssue(kind, host, pattern, action, message))
        }
        if (pattern.length > 4096) issue("path", "Pattern exceeds supported length", IssueKind.UNSUPPORTED_PATTERN)
        else if (pattern !in listOf("*", ".*", "true")) {
            val expression = if (pattern.firstOrNull() in listOf('*', '?', '+')) ".$pattern" else pattern
            if (runCatching { Regex(expression) }.isFailure) {
                issue("path", "Pattern cannot be represented by the JVM regex prerequisite", IssueKind.UNSUPPORTED_PATTERN)
            }
        }
        for ((key, value) in config) {
            if (!PlannerJson.truthy(value)) continue
            if (key !in recognizedKeys) issue(key, "Enabled action has no upstream implementation in this source revision")
            if (key in setOf("script", "tampermonkeyScript", "baiduOcr")) {
                issue(key, "Selection/configuration is reported; external/script/body execution is not implemented")
            }
            if (key in setOf("proxy", "redirect", "sni", "verifyHost") && value !is String) {
                issue(key, "String configuration required", IssueKind.UNSUPPORTED_VALUE)
            }
            if (key == "backup" && (value !is List<*> || value.any { it !is String })) {
                issue(key, "Backup must be an array of target strings", IssueKind.UNSUPPORTED_VALUE)
            }
            if (key == "retry" && value !is Number && value !is Map<*, *>) {
                issue(key, "Retry must be a number or object", IssueKind.UNSUPPORTED_VALUE)
            }
            if (key in setOf("requestReplace", "responseReplace")) {
                if (value !is Map<*, *>) issue(key, "Replacement must be an object", IssueKind.UNSUPPORTED_VALUE)
                for ((nested, item) in PlannerJson.obj(value)) {
                    if (nested !in setOf("headers", "doDownload") && PlannerJson.truthy(item)) {
                        issue("$key.$nested", "No native body executor; pinned replacement implementation handles headers/download only")
                    }
                    if (nested == "headers" && item !is Map<*, *>) {
                        issue("$key.headers", "Headers must be an object", IssueKind.UNSUPPORTED_VALUE)
                    }
                }
            }
        }
        return result
    }

    private fun retry(value: Any?, request: RuleRequest): RetryDescriptor? {
        val config = PlannerJson.obj(value)
        if (value !is Number && value !is Map<*, *>) return null
        val count = if (value is Number) value.toDouble() else {
            PlannerJson.number(config["times"] ?: config["retries"] ?: 0) ?: 0.0
        }
        require(count.isFinite() && count <= Int.MAX_VALUE) { "Retry count outside supported bounds" }
        val times = kotlin.math.floor(count).toInt().coerceAtLeast(0)
        if (times == 0) return null
        val statuses = (config["statuses"] as? List<*>)?.mapNotNull {
            Regex("^[\\t\\n\\r ]*([+-]?[0-9]+)").find(PlannerJson.text(it))?.groupValues?.get(1)?.toIntOrNull()
        }?.takeIf { it.isNotEmpty() } ?: listOf(500)
        val methods = (config["methods"] as? List<*>)?.takeIf { it.isNotEmpty() }
            ?.map { PlannerJson.text(it).uppercase(Locale.ROOT) } ?: listOf("GET", "HEAD", "OPTIONS")
        return RetryDescriptor(
            times, statuses, methods, RequestRetryPolicy.canRetryWithBody(request.method, request.headers, methods, 1_048_576),
        )
    }

    private fun cache(config: Map<String, Any?>): CacheDescriptor {
        val low = (PlannerJson.number(config["cacheMinStatusCode"])?.takeIf { it != 0.0 } ?: 200.0).toInt()
        val high = (PlannerJson.number(config["cacheMaxStatusCode"])?.takeIf { it != 0.0 } ?: 303.0).toInt()
        return CacheDescriptor(
            PlannerJson.maxAge(config)!!, config["cacheControlType"]?.let(PlannerJson::text)?.takeIf { it.isNotEmpty() } ?: "public",
            config["cacheImmutable"] != false && config["cacheImmutable"] != "false", minOf(low, high), maxOf(low, high),
            PlannerJson.obj(config["cacheExcludeStatusCodeList"]).filterValues(PlannerJson::truthy).keys,
        )
    }

    private fun preflight(config: Map<String, Any?>, headers: Map<String, Any?>): StaticResponse {
        fun read(key: String, fallback: String): Any? {
            val value = config[key]
            return if (!PlannerJson.truthy(value)) fallback else if (value is List<*>) value.joinToString(",") { PlannerJson.text(it) } else value
        }
        val result = linkedMapOf<String, Any?>(
            "DS-Interceptor" to "options", "Access-Control-Allow-Origin" to headers["origin"],
            "Access-Control-Allow-Headers" to (headers["access-control-request-headers"]?.takeIf(PlannerJson::truthy)
                ?: read("optionsAllowHeaders", "*")),
            "Access-Control-Allow-Methods" to (headers["access-control-request-method"]?.takeIf(PlannerJson::truthy)
                ?: read("optionsAllowMethods", "GET,POST,PUT,DELETE,HEAD,OPTIONS,PATCH")),
            "Access-Control-Max-Age" to (config["optionsMaxAge"]?.takeIf { (PlannerJson.number(it) ?: 0.0) > 0 } ?: 2592000),
            "Vary" to "Origin",
        )
        if (config["optionsCredentials"] != false && config["optionsCredentials"] != "false") {
            result["Access-Control-Allow-Credentials"] = "true"
        }
        return StaticResponse(StaticResponseKind.OPTIONS, 200, result, "", addDateAtExecution = true)
    }

    private fun staticResponse(name: String, config: Map<String, Any?>, headers: Map<String, Any?>): StaticResponse {
        val value = config[name]
        val standard = value == true || value == "true"
        val response = PlannerJson.obj(value)
        val status = if (standard) { if (name == "success") 200 else 403 } else {
            (response["status"]?.takeIf(PlannerJson::truthy) as? Number)?.toInt() ?: if (name == "success") 200 else 403
        }
        require(status in 100..599) { "Static status outside supported range" }
        val bodyValue = listOf("html", "json", "script", "css", "text", "body")
            .map { response[it] }.firstOrNull(PlannerJson::truthy)
        val body = if (status == 204) "" else if (bodyValue == null) defaultMessage(name, status) else text(bodyValue)
        val result = LinkedHashMap(PlannerJson.obj(response["headers"]))
        result["DS-Interceptor"] = name
        if (standard) result["Content-Type"] = "text/plain; charset=utf-8"
        else if (status != 204) {
            if (!PlannerJson.truthy(result["Content-Type"])) result["Content-Type"] = when {
                response["html"] != null -> "text/html"
                response["json"] != null -> "application/json"
                response["script"] != null -> "application/javascript"
                response["css"] != null -> "text/css"
                else -> "text/plain"
            }
            val contentType = text(result["Content-Type"])
            if (!contentType.contains("charset")) result["Content-Type"] = "$contentType; charset=utf-8"
        }
        cors(result, headers)
        val age = PlannerJson.maxAge(config)
        if (name == "success" && age != null && age > 0) result["Cache-Control"] = "public, max-age=${PlannerJson.text(age + 1)}, immutable"
        return StaticResponse(if (name == "success") StaticResponseKind.SUCCESS else StaticResponseKind.ABORT, status, result, body)
    }

    private fun cors(target: MutableMap<String, Any?>, headers: Map<String, Any?>) {
        if (PlannerJson.truthy(headers["origin"]) && !PlannerJson.truthy(target["Access-Control-Allow-Origin"])) {
            target["Access-Control-Allow-Credentials"] = "true"
            target["Access-Control-Allow-Origin"] = headers["origin"]
        }
    }

    private fun text(value: Any?): String = value as? String
        ?: throw IllegalArgumentException("Expected a string action value")

    private fun defaultMessage(name: String, status: Int): String =
        "DevSidecar $status: Request $name.\n\n  This request is matched by $name intercept.\n\n" +
            "  \u56e0\u914d\u7f6e${name}\u62e6\u622a\u5668\uff0c\u672c\u8bf7\u6c42\u76f4\u63a5\u8fd4\u56de$status" +
            if (name == "success") "\u6210\u529f\u3002" else "\u7981\u6b62\u8bbf\u95ee\u3002"

    companion object {
        private val implementations = listOf(
            "options" to 101, "success" to 102, "abort" to 103, "cacheRequest" to 104,
            "redirect" to 105, "requestReplace" to 111, "proxy" to 121, "retry" to 100,
            "sni" to 123, "unVerifySsl" to 124, "baiduOcr" to 131,
            "AfterOPTIONSHeaders" to 201, "cacheResponse" to 202, "responseReplace" to 203, "script" to 211,
        )
        private val recognizedKeys = setOf(
            "options", "success", "abort", "redirect", "requestReplace", "proxy", "retry", "sni", "verifyHost",
            "unVerifySsl", "ssl", "baiduOcr", "responseReplace", "script", "tampermonkeyScript",
            "desc", "order", "exclusions", "key", "replace", "backup", "matched",
            "cacheSeconds", "cacheMaxAge", "cache", "cacheMinutes", "cacheHours", "cacheDays", "cacheWeeks",
            "cacheMonths", "cacheYears", "cacheControlType", "cacheImmutable", "cacheMinStatusCode",
            "cacheMaxStatusCode", "cacheExcludeStatusCodeList", "optionsAllowHeaders", "optionsAllowMethods",
            "optionsMaxAge", "optionsCredentials", "optionsCrossPolicy",
        )
    }
}
