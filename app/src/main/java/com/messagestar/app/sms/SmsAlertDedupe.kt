package com.messagestar.app.sms

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest

/** Keeps only a short-lived digest so the notification listener does not repeat an SMS alert. */
object SmsAlertDedupe {
    private const val PREFS = "recent_sms_alert"
    private const val WINDOW_MS = 90_000L

    private fun digest(body: String): String {
        val normalized = body.trim().replace(Regex("\\s+"), " ").lowercase()
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun mark(context: Context, body: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("digest", digest(body))
            .putLong("at", SystemClock.elapsedRealtime())
            .apply()
    }

    fun recentlyAlerted(context: Context, body: String): Boolean {
        if (body.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val elapsed = SystemClock.elapsedRealtime() - prefs.getLong("at", 0L)
        return elapsed in 0..WINDOW_MS && prefs.getString("digest", null) == digest(body)
    }
}
