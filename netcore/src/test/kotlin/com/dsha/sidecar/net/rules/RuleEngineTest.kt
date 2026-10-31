package com.dsha.sidecar.net.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleEngineTest {

    @Test
    fun `内置规则命中 GitHub 各子域`() {
        val engine = RuleEngine()
        listOf(
            "github.com",
            "api.github.com",
            "raw.githubusercontent.com",
            "codeload.github.com",
            "objects.githubusercontent.com",
            "github.githubassets.com",
        ).forEach {
            assertEquals(RuleAction.ACCELERATE, engine.policy(it).action, "应加速: $it")
        }
    }

    @Test
    fun `普通站点走直连`() {
        val engine = RuleEngine()
        assertEquals(RuleAction.DIRECT, engine.policy("www.example.com").action)
        assertEquals(RuleAction.DIRECT, engine.policy("notgithub.com").action)
        assertEquals(RuleAction.DIRECT, engine.policy("github.com.evil.cn").action)
    }

    @Test
    fun `ABORT 与 PROXY 规则生效且顺序优先`() {
        val engine = RuleEngine(
            listOf(
                Rule("^(?:[a-z0-9-]+\\.)*ads\\.example\\.com$", RuleAction.ABORT),
                Rule(
                    "^(?:[a-z0-9-]+\\.)*github\\.com$",
                    RuleAction.PROXY,
                    target = "mirror.example.org:8443",
                ),
            ),
        )
        assertEquals(RuleAction.ABORT, engine.policy("t.ads.example.com").action)

        val p = engine.policy("api.github.com")
        assertEquals(RuleAction.PROXY, p.action)
        assertEquals("mirror.example.org", p.targetHost)
        assertEquals(8443, p.targetPort)
    }

    @Test
    fun `PROXY 未写端口时沿用原端口`() {
        val engine = RuleEngine(listOf(Rule(".*", RuleAction.PROXY, target = "mirror.example.org")))
        val p = engine.policy("github.com", 443)
        assertEquals("mirror.example.org", p.targetHost)
        assertEquals(443, p.targetPort)
    }

    @Test
    fun `域名大小写与末尾点不影响匹配`() {
        val engine = RuleEngine()
        assertEquals(RuleAction.ACCELERATE, engine.policy("API.GitHub.com.").action)
    }

    @Test
    fun `空规则库全部直连`() {
        assertTrue(RuleEngine(emptyList()).policy("github.com").action == RuleAction.DIRECT)
    }
}
