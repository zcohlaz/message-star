package com.messagestar.app.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPolicyTest {
    private val shopping = NotificationFacts("com.shop", "限时促销", "今晚优惠", dismissible = true)
    private val rule = NotificationRule("important", "com.shop", titleContains = "订单", action = NotificationAction.REMIND)

    @Test fun disabledDoesNotArchive() {
        assertEquals(NotificationDecision.PASS, NotificationPolicy.evaluate(shopping, false, setOf("com.shop"), emptyList()).decision)
    }

    @Test fun unmanagedAppPassesThrough() {
        assertEquals(NotificationDecision.PASS, NotificationPolicy.evaluate(shopping, true, emptySet(), emptyList()).decision)
    }

    @Test fun unmatchedManagedNotificationIsArchived() {
        assertEquals(NotificationDecision.ARCHIVE, NotificationPolicy.evaluate(shopping, true, setOf("com.shop"), listOf(rule)).decision)
    }

    @Test fun importantRuleReminds() {
        assertEquals(NotificationDecision.REMIND, NotificationPolicy.evaluate(shopping.copy(title = "订单已送达"), true, setOf("com.shop"), listOf(rule)).decision)
    }

    @Test fun exclusionAvoidsFalseMatch() {
        val excluded = rule.copy(titleContains = "", bodyExcludes = "优惠", action = NotificationAction.CRITICAL)
        assertEquals(NotificationDecision.ARCHIVE, NotificationPolicy.evaluate(shopping, true, setOf("com.shop"), listOf(excluded)).decision)
    }

    @Test fun redactedBodyFailsOpen() {
        val bodyRule = rule.copy(titleContains = "", bodyContains = "紧急")
        assertEquals(NotificationDecision.KEEP, NotificationPolicy.evaluate(shopping.copy(body = ""), true, setOf("com.shop"), listOf(bodyRule)).decision)
    }

    @Test fun ongoingNotificationPassesThrough() {
        assertEquals(NotificationDecision.PASS, NotificationPolicy.evaluate(shopping.copy(dismissible = false), true, setOf("com.shop"), emptyList()).decision)
    }
}
