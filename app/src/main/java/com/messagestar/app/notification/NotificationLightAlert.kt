package com.messagestar.app.notification

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.messagestar.app.MainActivity
import com.messagestar.app.R
import android.app.PendingIntent

object NotificationLightAlert {
    const val CHANNEL_ID = "important_notifications_v1"

    fun show(context: Context, appLabel: String, title: String, criticalFallback: Boolean = false) {
        val openApp = PendingIntent.getActivity(
            context, 220, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (criticalFallback) "重要通知提醒" else "${appLabel}的重要消息")
            .setContentText(title.ifBlank { "请查看原应用通知" })
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(if (criticalFallback) 60_000L else 20_000L)
            .setContentIntent(openApp)
            .build()
        context.getSystemService(NotificationManager::class.java).notify((System.nanoTime() and 0x7fffffff).toInt(), notification)
    }
}
