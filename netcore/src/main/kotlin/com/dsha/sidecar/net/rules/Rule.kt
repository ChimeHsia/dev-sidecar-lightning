package com.dsha.sidecar.net.rules

/** 命中一条规则后要做的动作。 */
enum class RuleAction {
    /** 走自建 DNS + IP 测速，连最优 IP；SNI 与证书保持原域名，链路上不改任何字节。 */
    ACCELERATE,

    /** 系统解析直连，不做任何额外探测（普通站点默认）。 */
    DIRECT,

    /** 直接断开，不发起连接（拦截）。 */
    ABORT,

    /**
     * 改连另一个目标（镜像/中转）。仅当目标能出示**原域名**的证书时才可用，
     * 因为链路上不会改写 SNI（改写会破坏 TLS 握手，详见 LocalProxyServer 注释）。
     */
    PROXY,
}

/**
 * 一条加速规则。语义对齐 dev-sidecar 的 JSON 规则：host 用正则描述，
 * 命中后由 action 决定去向。缓存天数沿用 cacheDays。
 */
data class Rule(
    val hostPattern: String,
    val action: RuleAction,
    /** action=PROXY 时的目标，host 或 host:port。 */
    val target: String? = null,
    val cacheDays: Int = 1,
    val note: String = "",
) {
    val regex: Regex by lazy { Regex(hostPattern, RegexOption.IGNORE_CASE) }

    fun matches(host: String): Boolean = regex.matches(host)

    companion object {
        /** 常用简写：[accelerate]="github.com" 等价于 ^(子域.)*github\.com$ */
        fun accelerate(vararg domains: String): List<Rule> = domains.map {
            val escaped = Regex.escape(it)
            Rule(
                hostPattern = "^(?:[a-z0-9-]+\\.)*$escaped$",
                action = RuleAction.ACCELERATE,
                note = "GitHub 加速",
            )
        }
    }
}

/** 规则匹配结果。 */
data class Policy(
    val action: RuleAction,
    val targetHost: String? = null,
    val targetPort: Int? = null,
    val source: Rule? = null,
)
