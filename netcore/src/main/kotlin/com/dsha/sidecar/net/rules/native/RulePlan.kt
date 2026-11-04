// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.rules.native

data class RuleRequest(
    val host: String,
    val path: String,
    val method: String = "GET",
    val headers: Map<String, Any?> = emptyMap(),
    val scheme: String = "https",
    val port: Int = if (scheme == "https") 443 else 80,
)

data class SelectedImplementation(
    val name: String,
    val priority: Int,
    val pathPattern: String,
    val order: Double,
    val pathCaptures: List<String?>,
    val namedPathCaptures: Map<String, String?>?,
    val hostnameCaptures: List<String?>?,
    val namedHostnameCaptures: Map<String, String?>?,
)

enum class IssueKind {
    UNSUPPORTED_ACTION, UNSUPPORTED_VALUE, UNSUPPORTED_PATTERN, ECH_PROXY_SNI_CONFLICT,
    GLOBAL_SCRIPT_MIDDLEWARE,
}

data class PlanningIssue(
    val kind: IssueKind,
    val hostnamePattern: String?,
    val pathPattern: String?,
    val action: String,
    val message: String,
)

data class ConfigurationAudit(val issues: List<PlanningIssue>) {
    val hasBlockingIssues: Boolean get() = issues.isNotEmpty()
}

data class RouteOverride(
    val targetUrl: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val authority: String,
    val path: String,
)

sealed interface ServerName {
    data class Send(val host: String) : ServerName
    data object Suppress : ServerName
}

enum class TrustMode { VERIFY_CHAIN_AND_HOST, UPSTREAM_COMPAT_UNVERIFIED }
enum class TrustReason { SERVER_SETTING, SNI_IMPLEMENTATION, PROXY_INLINE_SNI, EXPLICIT_UNVERIFY_SSL }

/** Data only. No trust manager or SSLContext is created or modified by planning. */
data class TlsPolicy(
    val applicable: Boolean,
    val serverName: ServerName,
    val verificationHost: String,
    val trustMode: TrustMode,
    val compatibilityReasons: List<TrustReason>,
)

data class CacheDescriptor(
    val maxAgeSeconds: Double,
    val controlType: String,
    val immutable: Boolean,
    val minimumStatus: Int,
    val maximumStatus: Int,
    val excludedStatuses: Set<String>,
    val onlyGet: Boolean = true,
    val requiresExecutionTime: Boolean = true,
)

data class RetryDescriptor(
    val times: Int,
    val statuses: List<Int>,
    val methods: List<String>,
    val requestBodyEligible: Boolean,
    val maximumBufferedBodyBytes: Int = 1_048_576,
    val requiresSuccessfulBodyBuffering: Boolean = true,
)

data class BackupDescriptor(
    val primary: String,
    val alternatives: List<String>,
    val counterKey: String,
    val requiresRuntimeRanking: Boolean = true,
)

enum class StaticResponseKind { SUCCESS, ABORT, REDIRECT, OPTIONS }

data class StaticResponse(
    val kind: StaticResponseKind,
    val status: Int,
    val headers: Map<String, Any?>,
    val body: String,
    /** OPTIONS Date is a descriptor, not a clock read in the planner. */
    val addDateAtExecution: Boolean = false,
)

sealed interface RequestAction {
    data class Retry(val descriptor: RetryDescriptor?) : RequestAction
    data class Preflight(val response: StaticResponse?) : RequestAction
    data class CacheLookup(val descriptor: CacheDescriptor) : RequestAction
    data class Transform(
        val replacementHeaders: Map<String, Any?>,
        val stripDownloadMarker: Boolean,
    ) : RequestAction
    data class Proxy(val primaryRoute: RouteOverride, val backup: BackupDescriptor?) : RequestAction
    data class Tls(val policy: TlsPolicy) : RequestAction
    data class Respond(val response: StaticResponse) : RequestAction
    data class Unsupported(val name: String, val configuration: Any?) : RequestAction
}

sealed interface ResponseAction {
    data class Cors(val crossOriginResourcePolicy: Any?) : ResponseAction
    data class Cache(val descriptor: CacheDescriptor) : ResponseAction
    /** Only applies to status 200, matching the pinned responseReplace implementation. */
    data class Replace(
        val headers: Map<String, Any?>,
        val doDownload: Boolean,
        val requestPath: String,
        val hasCachePolicy: Boolean,
        val onlyStatus: Int = 200,
    ) : ResponseAction
    data class ScriptInjection(val script: Any?, val tampermonkeyScript: Any?) : ResponseAction
    /** No body executor exists here, including for unknown nested body options. */
    data class UnsupportedBody(val implementation: String, val configuration: Map<String, Any?>) : ResponseAction
}

data class RequestStep(val source: SelectedImplementation, val action: RequestAction)
data class ResponseStep(val source: SelectedImplementation, val action: ResponseAction)

data class RequestPlan(
    val selected: List<SelectedImplementation>,
    val requestSteps: List<RequestStep>,
    val responseSteps: List<ResponseStep>,
    /** Primary route only; runtime backup selection may choose another candidate. */
    val routeOverride: RouteOverride?,
    val requestPath: String,
    val requestHeaders: Map<String, Any?>,
    val tls: TlsPolicy,
    /** Reached only if earlier conditional steps, such as a cache lookup, fall through. */
    val localResponse: StaticResponse?,
    val retry: RetryDescriptor?,
    val doDownload: Boolean,
    val echRequired: Boolean,
    val issues: List<PlanningIssue>,
) {
    val hasBlockingIssues: Boolean get() = issues.isNotEmpty()

    /** Integration must check this before executing a plan. Descriptors still require executors. */
    fun requireSupported(): RequestPlan {
        if (hasBlockingIssues) throw UnsupportedRulePlanException(issues)
        return this
    }
}

class UnsupportedRulePlanException(val issues: List<PlanningIssue>) :
    IllegalArgumentException("Rule plan contains unsupported or conflicting enabled configuration")
