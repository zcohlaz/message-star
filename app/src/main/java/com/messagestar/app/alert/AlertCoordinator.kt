package com.messagestar.app.alert

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.messagestar.app.data.AlertStateStore

object AlertCoordinator {
    private val lock = Any()

    fun trigger(context: Context, sender: String, testMode: Boolean = false, sourceType: String = "短信"): Boolean {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (!testMode && AlertStateStore.isCoolingDown(appContext)) return true
            val current = AlertStateStore.snapshot(appContext)
            if (current.isAlerting) {
                if (!AlertService.running) {
                    val restarted = runCatching {
                        ContextCompat.startForegroundService(
                            appContext,
                            Intent(appContext, AlertService::class.java).setAction(AlertNotification.ACTION_UPDATE)
                        )
                    }.onFailure { Log.e("MessageStarAlert", "Unable to resume foreground alert", it) }.isSuccess
                    if (!restarted) {
                        AlertStateStore.stop(appContext, cooldown = false)
                        return false
                    }
                }
                AlertStateStore.append(appContext, sender, sourceType)
                appContext.sendBroadcast(Intent(AlertNotification.ACTION_UPDATE).setPackage(appContext.packageName))
                return true
            }
            AlertStateStore.begin(appContext, sender, sourceType)
            val started = runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, AlertService::class.java).setAction(AlertNotification.ACTION_UPDATE)
                )
            }.onFailure { Log.e("MessageStarAlert", "Unable to start foreground alert", it) }.isSuccess
            if (!started) {
                AlertStateStore.stop(appContext, cooldown = false)
                return false
            }
            appContext.sendBroadcast(Intent(AlertNotification.ACTION_UPDATE).setPackage(appContext.packageName))
            return true
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
