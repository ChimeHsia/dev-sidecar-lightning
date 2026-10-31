package com.dsha.sidecar.net.rules

/**
 * 规则引擎：按 host 匹配，返回该连接的处置策略。
 * 规则顺序敏感——先写的优先（便于用户把 ABORT 放最前面）。
 */
class RuleEngine(rules: List<Rule> = BuiltinRules.DEFAULT) {

    private val rules: List<Rule> = rules.toList()

    val size: Int get() = rules.size

    fun policy(host: String, port: Int = 443): Policy {
        val h = host.trim().trimEnd('.').lowercase()
        for (rule in rules) {
            if (!rule.matches(h)) continue
            return when (rule.action) {
                RuleAction.DIRECT -> Policy(RuleAction.DIRECT, source = rule)
                RuleAction.ABORT -> Policy(RuleAction.ABORT, source = rule)
                RuleAction.ACCELERATE -> Policy(RuleAction.ACCELERATE, source = rule)
                RuleAction.PROXY -> {
                    val t = rule.target ?: continue
                    val (th, tp) = splitHostPort(t, port)
                    Policy(RuleAction.PROXY, th, tp, rule)
                }
            }
        }
        return Policy(RuleAction.DIRECT)
    }

    private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int> {
        val v = value.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val colon = v.lastIndexOf(':')
        if (colon > 0 && v.indexOf(':') == colon) {
            val port = v.substring(colon + 1).toIntOrNull()
            if (port != null) return v.substring(0, colon) to port
        }
        return v to defaultPort
    }
}
