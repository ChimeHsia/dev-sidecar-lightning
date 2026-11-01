package com.dsha.sidecar.net.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    @Test
    fun `normalized host uses first matching rule and retains its source`() {
        val first = Rule("^api\\.example\\.com$", RuleAction.ABORT)
        val second = Rule(".*", RuleAction.PROXY, target = "mirror.example:8443")
        val engine = RuleEngine(listOf(first, second))

        val policy = engine.policy("  API.Example.COM...  ")
        assertEquals(RuleAction.ABORT, policy.action)
        assertSame(first, policy.source)
        assertNull(policy.targetHost)
        assertNull(policy.targetPort)
    }

    @Test
    fun `proxy without target is skipped and next matching rule wins`() {
        val fallback = Rule(".*", RuleAction.ACCELERATE)
        val engine = RuleEngine(listOf(Rule(".*", RuleAction.PROXY), fallback))

        val policy = engine.policy("example.com")
        assertEquals(RuleAction.ACCELERATE, policy.action)
        assertSame(fallback, policy.source)
    }

    @Test
    fun `proxy target scheme and port are handled independently of requested port`() {
        val withPort = RuleEngine(listOf(Rule(".*", RuleAction.PROXY, "https://mirror.example:8443/")))
        val withoutPort = RuleEngine(listOf(Rule(".*", RuleAction.PROXY, "http://mirror.example/")))

        assertEquals("mirror.example", withPort.policy("example.com", 9443).targetHost)
        assertEquals(8443, withPort.policy("example.com", 9443).targetPort)
        assertEquals("mirror.example", withoutPort.policy("example.com", 9443).targetHost)
        assertEquals(9443, withoutPort.policy("example.com", 9443).targetPort)
    }

    @Test
    fun `accelerate escapes domain metacharacters and matches only whole labels`() {
        val engine = RuleEngine(Rule.accelerate("example.com"))
        listOf("example.com", "A-B.example.com", "a.b.example.com").forEach {
            assertEquals(RuleAction.ACCELERATE, engine.policy(it).action, it)
        }
        listOf("exampleXcom", "badexample.com", "example.com.evil", "a_b.example.com").forEach {
            assertEquals(RuleAction.DIRECT, engine.policy(it).action, it)
        }
    }

    @Test
    fun `engine snapshots mutable rule list at construction`() {
        val rules = mutableListOf(Rule(".*", RuleAction.ABORT))
        val engine = RuleEngine(rules)
        rules.clear()

        assertEquals(1, engine.size)
        assertEquals(RuleAction.ABORT, engine.policy("example.com").action)
    }
}
