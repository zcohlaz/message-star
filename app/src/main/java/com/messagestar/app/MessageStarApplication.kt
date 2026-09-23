package com.messagestar.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.messagestar.app.alert.AlertNotification
import com.messagestar.app.notification.NotificationLightAlert

class MessageStarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(NotificationLightAlert.CHANNEL_ID, "重要通知轻提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "白名单通知的一次性提醒"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
            manager.createNotificationChannel(NotificationChannel(AlertNotification.NOTIFICATION_CHANNEL_ID, "重要通知强提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "仅在重要 APP 通知持续提醒期间显示"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
            manager.createNotificationChannel(
                NotificationChannel(
                    AlertNotification.CHANNEL_ID,
                    "短信强提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "仅在重要短信报警期间显示"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }
}
