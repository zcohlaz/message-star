package com.messagestar.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.messagestar.app.alert.AlertCoordinator
import com.messagestar.app.data.SettingsRepository
import com.messagestar.app.rules.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = SettingsRepository(appContext)
                if (!repository.currentMasterEnabled()) return@launch
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) return@launch
                val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
                val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()
                if (sender.isBlank() || body.isBlank()) return@launch
                val rules = repository.currentRules()
                if (RuleEngine.match(rules, sender, body).matched) {
                    AlertCoordinator.trigger(appContext, sender)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
