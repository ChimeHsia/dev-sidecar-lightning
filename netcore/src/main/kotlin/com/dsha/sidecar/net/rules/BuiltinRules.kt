package com.dsha.sidecar.net.rules

/**
 * 内置规则。默认策略刻意保守：
 * 只有 GitHub 相关域名走"优选 IP"，其它站点一律直连——不做无谓的 DNS 探测，
 * 避免给普通网页增加首包延迟。
 *
 * 镜像类规则（PROXY）默认不启用：镜像站的存活率不稳定，且多数镜像需要改写 URL 路径，
 * 本类转发器只做端口层面的转发，贸然重定向反而会把链接打断。需要时在用户侧加规则即可。
 */
object BuiltinRules {

    /** GitHub 主域与静态资源域。 */
    private val GITHUB_DOMAINS = listOf(
        "github.com",
        "githubusercontent.com",
        "githubassets.com",
        "github.io",
        "githubapp.com",
        "ghcr.io",
    )

    /**
     * Google 系：**默认不启用**，原因是它需要 MITM 才能用，而本项目当前不做 MITM。
     *
     * 2026-11-01 真机实测链路（历次结论都在这里，别再重复踩）：
     * - 系统/DNS 解析 `www.google.com.hk` → `31.13.x`（Facebook）、`2001::1`（投毒签名）；
     * - 换干净 IP（`142.251.189.206`）后 TCP 通、TLS 也能握手，但 Google 返回的是
     *   **诱饵证书** `CN=invalid.invalid / OU=This cert should never been seen`，浏览器必然
     *   报 `ERR_CERT_AUTHORITY_INVALID`（实测 WebView 日志 `net_error -202`）；
     * - 换其它 Google IP 或换 `www.google.com` / `www.youtube.com` 直接 RST 或超时。
     *
     * 即：**不是 DNS 慢，也不是 SNI 被断，而是没有可用证书**。只有三条路能走通：
     * ① 自己 MITM（dev-sidecar 的做法：`sni 伪装 + unVerifySsl + 预设 IP`）；
     * ② 上游中转（用户自备节点，不改 TLS）；
     * ③ 在 WebView 拦截层用自建客户端代取（`shouldInterceptRequest`，不校验证书）。
     * 三条都超出"纯字节转发"的范围，需要产品层面决策后再放开这里。
     */
    @Suppress("unused")
    private val GOOGLE_DOMAINS = listOf(
        "google.com",
        "google.com.hk",
        "google.com.tw",
        "google.co.jp",
        "gstatic.com",
        "googleapis.com",
        "googleusercontent.com",
        "youtube.com",
        "ytimg.com",
        "ggpht.com",
        "googlevideo.com",
        "gvt1.com",
        "recaptcha.net",
    )

    val DEFAULT: List<Rule> = Rule.accelerate(*GITHUB_DOMAINS.toTypedArray())

    /** 示例：把 raw 域名交给自建镜像（需镜像路径兼容，谨慎开启）。 */
    val SAMPLE_MIRROR: List<Rule> = listOf(
        Rule(
            hostPattern = "^(?:[a-z0-9-]+\\.)*raw\\.githubusercontent\\.com$",
            action = RuleAction.PROXY,
            target = "gh-proxy.example.com",
            note = "示例：raw 镜像转发",
        ),
    )

    /** 示例：拦截。 */
    val SAMPLE_ABORT: List<Rule> = listOf(
        Rule(
            hostPattern = "^(?:[a-z0-9-]+\\.)*doubleclick\\.net$",
            action = RuleAction.ABORT,
            note = "示例：拦截广告域名",
        ),
    )
}
