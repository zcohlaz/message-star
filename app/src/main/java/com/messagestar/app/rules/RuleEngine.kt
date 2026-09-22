package com.messagestar.app.rules

import com.messagestar.app.data.Rule
import com.messagestar.app.data.RuleMatch
import com.messagestar.app.data.RuleType

object RuleEngine {
    private val mainlandPhone = Regex("1[3-9]\\d{9}")

    fun normalizePhone(input: String): String {
        val compact = input.trim().replace(Regex("[\\s\\-()]"), "")
        return when {
            compact.startsWith("+86") && compact.drop(3).matches(mainlandPhone) -> compact.drop(3)
            compact.startsWith("86") && compact.drop(2).matches(mainlandPhone) -> compact.drop(2)
            else -> compact
        }
    }

    fun match(rules: List<Rule>, sender: String, body: String): RuleMatch {
        rules.filter { it.enabled }.forEach { rule ->
            when (rule.type) {
                RuleType.PHONE -> if (normalizePhone(sender) == normalizePhone(rule.phoneNumber)) {
                    return RuleMatch(true, rule)
                }
                RuleType.PLATFORM -> {
                    val keywords = rule.keywords.map(String::trim).filter(String::isNotEmpty)
                    val missing = keywords.filterNot { body.contains(it, ignoreCase = true) }
                    if (keywords.isNotEmpty() && missing.isEmpty()) return RuleMatch(true, rule)
                }
            }
        }
        return RuleMatch(false)
    }

    fun test(rule: Rule, body: String): RuleMatch {
        if (rule.type != RuleType.PLATFORM) return RuleMatch(false, rule)
        val keywords = rule.keywords.map(String::trim).filter(String::isNotEmpty)
        val missing = keywords.filterNot { body.contains(it, ignoreCase = true) }
        return RuleMatch(missing.isEmpty() && keywords.isNotEmpty(), rule, missing)
    }
}
