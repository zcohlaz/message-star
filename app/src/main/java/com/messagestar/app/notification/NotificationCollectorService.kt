package com.messagestar.app.notification

import android.app.Notification
import android.provider.Telephony
import com.messagestar.app.sms.SmsAlertDedupe
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.messagestar.app.alert.AlertCoordinator
import com.messagestar.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class NotificationCollectorService : NotificationListenerService() {
    companion object { private const val TAG = "MessageStarListener" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handled = ConcurrentHashMap<String, Int>()
    private val observedAt = ConcurrentHashMap<String, Long>()
    private val store by lazy { NotificationArchiveStore(applicationContext) }
    @Volatile private var connected = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.packageName == "android" || sbn.packageName.startsWith("com.android.")) return
        val original = sbn.notification
        val facts = NotificationFacts(
            packageName = sbn.packageName,
            title = original.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(200),
            body = (original.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: original.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(1000),
            dismissible = sbn.isClearable,
            isProtected = original.category == Notification.CATEGORY_CALL ||
                original.category == Notification.CATEGORY_ALARM ||
                original.flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR) != 0
        )
        val key = sbn.key
        val fingerprint = listOf(facts.title, facts.body).hashCode()
        if (handled.put(key, fingerprint) == fingerprint) return

        scope.launch {
            try {
                val appLabel = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrDefault(sbn.packageName)
                val now = System.currentTimeMillis()
                val lastObserved = observedAt[sbn.packageName] ?: 0L
                if (now - lastObserved > 60 * 60 * 1000L) {
                    observedAt[sbn.packageName] = now
                    store.observeApp(sbn.packageName, appLabel, now)
                }

                val settings = SettingsRepository(applicationContext)
                val evaluation = NotificationPolicy.evaluate(
                    facts,
                    settings.currentNotificationFiltering(),
                    settings.currentManagedPackages(),
                    settings.currentNotificationRules()
                )
                if (handled[key] != fingerprint) return@launch
                when (evaluation.decision) {
                    NotificationDecision.ARCHIVE -> {
                        store.archive(ArchivedNotification(key, sbn.packageName, appLabel, facts.title, facts.body, evaluation.reason, sbn.postTime))
                        if (connected && handled[key] == fingerprint) cancelNotification(key)
                    }
                    NotificationDecision.REMIND -> {
                        if (!duplicateSmsAlert(sbn.packageName, facts.body)) {
                            NotificationLightAlert.show(applicationContext, appLabel, facts.title)
                        }
                    }
                    NotificationDecision.CRITICAL -> {
                        if (duplicateSmsAlert(sbn.packageName, facts.body)) return@launch
                        if (!AlertCoordinator.trigger(applicationContext, "$appLabel：${facts.title}", sourceType = "通知")) {
                            NotificationLightAlert.show(applicationContext, appLabel, facts.title, criticalFallback = true)
                        }
                    }
                    else -> Unit
                }
            } catch (error: Exception) {
                handled.remove(key, fingerprint)
                Log.e(TAG, "Unable to process notification from ${sbn.packageName}", error)
            }
        }
    }

    private suspend fun duplicateSmsAlert(packageName: String, body: String): Boolean {
        if (packageName != Telephony.Sms.getDefaultSmsPackage(this)) return false
        delay(1_200L)
        return SmsAlertDedupe.recentlyAlerted(this, body)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        handled.remove(sbn.key)
    }

    override fun onDestroy() {
        connected = false
        scope.cancel()
        store.close()
        super.onDestroy()
    }
}
