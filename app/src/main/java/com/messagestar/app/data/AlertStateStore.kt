package com.messagestar.app.data

import android.content.Context
import android.os.SystemClock

object AlertStateStore {
    private const val PREFS = "temporary_alert_state"
    private const val IS_ALERTING = "is_alerting"
    private const val COUNT = "count"
    private const val SENDER = "latest_sender"
    private const val COOLDOWN_UNTIL = "cooldown_until"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun snapshot(context: Context): AlertSnapshot {
        val p = prefs(context)
        return AlertSnapshot(
            isAlerting = p.getBoolean(IS_ALERTING, false),
            count = p.getInt(COUNT, 0),
            latestSender = p.getString(SENDER, "") ?: "",
            cooldownUntil = p.getLong(COOLDOWN_UNTIL, 0L)
        )
    }

    @Synchronized
    fun begin(context: Context, sender: String) {
        prefs(context).edit()
            .putBoolean(IS_ALERTING, true)
            .putInt(COUNT, 1)
            .putString(SENDER, sender)
            .apply()
    }

    @Synchronized
    fun append(context: Context, sender: String) {
        val current = snapshot(context)
        prefs(context).edit()
            .putBoolean(IS_ALERTING, true)
            .putInt(COUNT, current.count + 1)
            .putString(SENDER, sender)
            .apply()
    }

    @Synchronized
    fun stop(context: Context, cooldown: Boolean) {
        prefs(context).edit()
            .putBoolean(IS_ALERTING, false)
            .putInt(COUNT, 0)
            .putString(SENDER, "")
            .putLong(COOLDOWN_UNTIL, if (cooldown) SystemClock.elapsedRealtime() + 60_000L else 0L)
            .apply()
    }

    @Synchronized
    fun clearOnBoot(context: Context) {
        prefs(context).edit().putBoolean(IS_ALERTING, false).putInt(COUNT, 0).putString(SENDER, "").putLong(COOLDOWN_UNTIL, 0L).apply()
    }

    fun isCoolingDown(context: Context): Boolean =
        SystemClock.elapsedRealtime() < snapshot(context).cooldownUntil
}
