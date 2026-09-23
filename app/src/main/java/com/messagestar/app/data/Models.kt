package com.messagestar.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class RuleType { PHONE, PLATFORM }

@Serializable
data class Rule(
    val id: String,
    val type: RuleType,
    val enabled: Boolean = true,
    val phoneNumber: String = "",
    val name: String = "",
    val keywords: List<String> = emptyList()
) {
    val title: String
        get() = if (type == RuleType.PHONE) phoneNumber else name
}

data class RuleMatch(
    val matched: Boolean,
    val rule: Rule? = null,
    val missingKeywords: List<String> = emptyList()
)

data class AlertSnapshot(
    val isAlerting: Boolean,
    val count: Int,
    val latestSender: String,
    val cooldownUntil: Long,
    val sourceType: String = "短信"
)
