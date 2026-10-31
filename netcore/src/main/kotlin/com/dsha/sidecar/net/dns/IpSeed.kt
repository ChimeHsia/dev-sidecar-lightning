package com.dsha.sidecar.net.dns

/**
 * 内置种子 IP 池。
 *
 * 为什么需要：国内 DNS 对 GitHub 常常只返回 **一个** A 记录（典型是新加坡的 20.205.243.166），
 * 那台机器晚上经常不可达，此时"优选"根本没有备选可挑。dev-sidecar / fastGithub 的做法也是
 * 备一份 IP 池。这里只把种子当作**额外候选**，仍然要经过 TCP 测速才会被选中——
 * 不存在"写死就一定优先"的问题。
 *
 * 维护策略：宁少勿滥，只覆盖最关键的几个 GitHub 域名；条目失效时测速会自然淘汰。
 */
object IpSeed {

    private val table: List<Pair<String, List<String>>> = listOf(
        "github.com" to listOf(
            "140.82.112.3", "140.82.113.3", "140.82.114.3",
            "140.82.116.3", "20.205.243.166", "20.27.177.113",
        ),
        "api.github.com" to listOf(
            "140.82.112.6", "140.82.113.6", "140.82.114.6", "20.205.243.168",
        ),
        "codeload.github.com" to listOf(
            "140.82.112.9", "140.82.113.9", "140.82.114.9", "20.205.243.165",
        ),
        "raw.githubusercontent.com" to listOf(
            "185.199.108.133", "185.199.109.133", "185.199.110.133", "185.199.111.133",
        ),
        "github.githubassets.com" to listOf(
            "185.199.108.154", "185.199.109.154", "185.199.110.154", "185.199.111.154",
        ),
        "objects.githubusercontent.com" to listOf(
            "185.199.108.133", "185.199.109.133", "185.199.110.133", "185.199.111.133",
        ),
    )

    /**
     * Google 系种子。来源是 dev-sidecar 的 `preSetIpList`（标注"自动地区官方"）。
     *
     * 注意：**仅靠干净 IP 打不开 Google**。实测这些 IP 的 TCP/TLS 都能通，但 Google 回的是
     * 诱饵证书 `CN=invalid.invalid`，浏览器必拒绝（详见 `BuiltinRules.GOOGLE_DOMAINS` 注释）。
     * 这里保留种子是为了将来接入 MITM / 拦截层代取时可直接复用同一份 IP 池。
     *
     * 另外，被投毒的 IP 常常"能连、还很快"（例如连到 Facebook 的机器），
     * 一旦让它参与测速竞争就会选错出口，所以种子存在时优先只用种子。
     */
    private val GOOGLE_IPS = listOf("142.251.189.206", "172.217.204.206", "142.251.190.206")

    private val googleTable: List<Pair<String, List<String>>> = listOf(
        "google.com.hk", "google.com.tw", "google.co.jp", "google.com",
        "googleapis.com", "googleusercontent.com", "gstatic.com", "gvt1.com",
        "youtube.com", "ytimg.com", "ggpht.com", "googlevideo.com",
    ).map { it to GOOGLE_IPS }

    /** 名称 → IP 列表（按名称后缀匹配，最长匹配优先）。 */
    fun forHost(host: String): List<String> {
        val h = host.trim().trimEnd('.').lowercase()
        var best: List<String> = emptyList()
        var bestLen = 0
        for ((name, ips) in table + googleTable) {
            val hit = h == name || h.endsWith(".$name")
            if (hit && name.length > bestLen) {
                best = ips
                bestLen = name.length
            }
        }
        return best
    }
}
