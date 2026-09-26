package com.messagestar.app.notification

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class NotificationArchiveStore(context: Context) : SQLiteOpenHelper(context, "notification_archive.db", null, 1) {
    companion object {
        private val revision = MutableStateFlow(0L)
        private val generation = AtomicLong()
        val changes = revision.asStateFlow()
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_ARCHIVED = 1000
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE observed_apps (package_name TEXT PRIMARY KEY, label TEXT NOT NULL, last_seen INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE archived_notifications (notification_key TEXT PRIMARY KEY, package_name TEXT NOT NULL, app_label TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, reason TEXT NOT NULL, posted_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX archive_posted_at ON archived_notifications(posted_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun observeApp(packageName: String, label: String, now: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("package_name", packageName)
            put("label", label)
            put("last_seen", now)
        }
        writableDatabase.insertWithOnConflict("observed_apps", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        revision.value = generation.incrementAndGet()
    }

    fun observedApps(): List<ObservedApp> = buildList {
        readableDatabase.rawQuery("SELECT package_name, label, last_seen FROM observed_apps ORDER BY last_seen DESC", null).use { cursor ->
            while (cursor.moveToNext()) add(ObservedApp(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
        }
    }

    fun archive(entry: ArchivedNotification) {
        val values = ContentValues().apply {
            put("notification_key", entry.key)
            put("package_name", entry.packageName)
            put("app_label", entry.appLabel)
            put("title", entry.title)
            put("body", entry.body)
            put("reason", entry.reason)
            put("posted_at", entry.postedAt)
        }
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.insertWithOnConflict("archived_notifications", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("archived_notifications", "posted_at < ?", arrayOf((System.currentTimeMillis() - RETENTION_MS).toString()))
            // Remove only overflow rows without materializing a set of retained keys.
            db.execSQL(
                "DELETE FROM archived_notifications WHERE notification_key IN " +
                    "(SELECT notification_key FROM archived_notifications " +
                    "ORDER BY posted_at DESC, notification_key DESC LIMIT -1 OFFSET $MAX_ARCHIVED)"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        revision.value = generation.incrementAndGet()
    }

    fun archived(): List<ArchivedNotification> = buildList {
        writableDatabase.delete("archived_notifications", "posted_at < ?", arrayOf((System.currentTimeMillis() - RETENTION_MS).toString()))
        readableDatabase.rawQuery("SELECT notification_key, package_name, app_label, title, body, reason, posted_at FROM archived_notifications ORDER BY posted_at DESC LIMIT 1000", null).use { cursor ->
            while (cursor.moveToNext()) {
                add(ArchivedNotification(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getLong(6)))
            }
        }
    }

    fun delete(key: String) {
        writableDatabase.delete("archived_notifications", "notification_key = ?", arrayOf(key))
        revision.value = generation.incrementAndGet()
    }

    fun clearArchive() {
        writableDatabase.delete("archived_notifications", null, null)
        revision.value = generation.incrementAndGet()
    }
}
