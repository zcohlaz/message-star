package com.messagestar.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.messagestar.app.data.AlertStateStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            AlertStateStore.clearOnBoot(context.applicationContext)
        }
    }
}
