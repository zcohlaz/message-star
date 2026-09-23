package com.messagestar.app.notification

import kotlinx.serialization.Serializable

@Serializable
enum class NotificationAction { KEEP, REMIND, CRITICAL }

@Serializable
data class NotificationRule(
    val id: String,
    val packageName: String,
    val titleContains: String = "",
    val bodyContains: String = "",
    val bodyExcludes: String = "",
    val action: NotificationAction = NotificationAction.KEEP,
    val enabled: Boolean = true
)

data class NotificationFacts(
    val packageName: String,
    val title: String,
    val body: String,
    val dismissible: Boolean,
    val isProtected: Boolean = false
)

enum class NotificationDecision { PASS, KEEP, REMIND, CRITICAL, ARCHIVE }

data class NotificationEvaluation(
    val decision: NotificationDecision,
    val reason: String
)

data class ObservedApp(val packageName: String, val label: String, val lastSeen: Long)

data class ArchivedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val reason: String,
    val postedAt: Long
)
