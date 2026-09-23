package com.messagestar.app.notification

object NotificationPolicy {
    fun evaluate(
        facts: NotificationFacts,
        enabled: Boolean,
        managedPackages: Set<String>,
        rules: List<NotificationRule>
    ): NotificationEvaluation {
        if (!enabled || facts.packageName !in managedPackages) {
            return NotificationEvaluation(NotificationDecision.PASS, "未启用管理")
        }
        if (!facts.dismissible || facts.isProtected) {
            return NotificationEvaluation(NotificationDecision.PASS, "系统或持续通知")
        }

        val relevant = rules.filter { it.enabled && it.packageName == facts.packageName }
        for (rule in relevant) {
            if (rule.titleContains.isNotBlank() && !facts.title.contains(rule.titleContains.trim(), ignoreCase = true)) continue
            if (rule.bodyContains.isNotBlank() && !facts.body.contains(rule.bodyContains.trim(), ignoreCase = true)) continue
            if (rule.bodyExcludes.isNotBlank() && facts.body.contains(rule.bodyExcludes.trim(), ignoreCase = true)) continue
            return NotificationEvaluation(
                when (rule.action) {
                    NotificationAction.KEEP -> NotificationDecision.KEEP
                    NotificationAction.REMIND -> NotificationDecision.REMIND
                    NotificationAction.CRITICAL -> NotificationDecision.CRITICAL
                },
                "命中规则 ${rule.id}"
            )
        }

        // Some apps publish redacted or empty content. Keep it rather than losing
        // a potentially important notification because the matching fields are absent.
        if (facts.title.isBlank() && facts.body.isBlank() ||
            (facts.body.isBlank() && relevant.any { it.bodyContains.isNotBlank() })) {
            return NotificationEvaluation(NotificationDecision.KEEP, "通知内容不可用于判断")
        }
        return NotificationEvaluation(NotificationDecision.ARCHIVE, "未命中重要通知规则")
    }
}
