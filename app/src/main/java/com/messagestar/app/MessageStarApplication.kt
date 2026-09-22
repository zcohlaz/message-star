package com.messagestar.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.messagestar.app.alert.AlertNotification

class MessageStarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
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
