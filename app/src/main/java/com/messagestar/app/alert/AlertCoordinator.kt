package com.messagestar.app.alert

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.messagestar.app.data.AlertStateStore

object AlertCoordinator {
    private val lock = Any()

    fun trigger(context: Context, sender: String, testMode: Boolean = false) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (!testMode && AlertStateStore.isCoolingDown(appContext)) return
            val current = AlertStateStore.snapshot(appContext)
            if (current.isAlerting) {
                AlertStateStore.append(appContext, sender)
                appContext.sendBroadcast(Intent(AlertNotification.ACTION_UPDATE).setPackage(appContext.packageName))
                runCatching {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, AlertService::class.java).setAction(AlertNotification.ACTION_UPDATE)
                    )
                }
                return
            }
            AlertStateStore.begin(appContext, sender)
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, AlertService::class.java).setAction(AlertNotification.ACTION_UPDATE)
                )
            }
            appContext.sendBroadcast(Intent(AlertNotification.ACTION_UPDATE).setPackage(appContext.packageName))
        }
    }

    fun stop(context: Context, cooldown: Boolean = true) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val state = AlertStateStore.snapshot(appContext)
            if (!state.isAlerting && !cooldown) return
            AlertStateStore.stop(appContext, cooldown)
            appContext.stopService(Intent(appContext, AlertService::class.java))
            appContext.sendBroadcast(Intent(AlertNotification.ACTION_UPDATE).setPackage(appContext.packageName))
        }
    }
}
