package com.messagestar.app.alert

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.messagestar.app.MainActivity
import com.messagestar.app.R
import com.messagestar.app.data.AlertStateStore

object AlertNotification {
    const val CHANNEL_ID = "critical_sms_alert"
    const val ACTION_STOP = "com.messagestar.app.action.STOP_ALERT"
    const val ACTION_UPDATE = "com.messagestar.app.action.UPDATE_ALERT"

    fun build(context: Context, useFullScreenIntent: Boolean = true): android.app.Notification {
        val state = AlertStateStore.snapshot(context)
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
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("重要短信")
            .setContentText("收到 ${state.count.coerceAtLeast(1)} 条重要短信")
            .setSubText(if (state.latestSender.isBlank()) "请立即查看" else "发送号码：${state.latestSender}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .apply {
                if (useFullScreenIntent) setFullScreenIntent(fullScreenIntent, true)
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭提醒", stopIntent)
            .setContentIntent(PendingIntent.getActivity(context, 102, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
}
