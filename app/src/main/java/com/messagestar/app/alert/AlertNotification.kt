package com.messagestar.app.alert

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.messagestar.app.R
import com.messagestar.app.data.AlertStateStore

object AlertNotification {
    const val CHANNEL_ID = "critical_sms_alert"
    const val NOTIFICATION_CHANNEL_ID = "critical_app_alert"
    const val ACTION_STOP = "com.messagestar.app.action.STOP_ALERT"
    const val ACTION_UPDATE = "com.messagestar.app.action.UPDATE_ALERT"

    fun build(context: Context, useFullScreenIntent: Boolean = true): android.app.Notification {
        val state = AlertStateStore.snapshot(context)
        val sourceType = state.sourceType
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            100,
            Intent(context, AlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context,
            101,
            Intent(context, AlertService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channelId = if (sourceType == "短信") CHANNEL_ID else NOTIFICATION_CHANNEL_ID
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("重要${sourceType}")
            .setContentText("收到 ${state.count.coerceAtLeast(1)} 条重要${sourceType}")
            .setSubText(if (state.latestSender.isBlank()) "请立即查看" else "来源：${state.latestSender}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(if (sourceType == "短信") NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(false)
            .apply {
                if (useFullScreenIntent) setFullScreenIntent(fullScreenIntent, true)
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭提醒", stopIntent)
            .setContentIntent(fullScreenIntent)
            .build()
    }
}
