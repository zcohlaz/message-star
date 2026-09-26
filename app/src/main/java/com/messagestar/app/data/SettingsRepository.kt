package com.messagestar.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.messagestar.app.notification.NotificationRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "message_star_settings")

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val masterEnabled = booleanPreferencesKey("master_enabled")
        val vibrationEnabled = booleanPreferencesKey("vibration_enabled")
        val ringtoneUri = stringPreferencesKey("ringtone_uri")
        val rules = stringPreferencesKey("rules_json")
        val notificationFiltering = booleanPreferencesKey("notification_filtering")
        val managedPackages = stringPreferencesKey("managed_packages_json")
        val notificationRules = stringPreferencesKey("notification_rules_json")
    }

    val masterEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.masterEnabled] ?: true }
    val vibrationEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.vibrationEnabled] ?: true }
    val ringtoneUri: Flow<String?> = context.settingsDataStore.data.map { it[Keys.ringtoneUri] }
    val rules: Flow<List<Rule>> = context.settingsDataStore.data.map { prefs ->
        runCatching { json.decodeFromString<List<Rule>>(prefs[Keys.rules] ?: "[]") }.getOrDefault(emptyList())
    }

    val notificationFiltering: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.notificationFiltering] ?: false }
    val managedPackages: Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        runCatching { json.decodeFromString<List<String>>(prefs[Keys.managedPackages] ?: "[]").toSet() }.getOrDefault(emptySet())
    }
    val notificationRules: Flow<List<NotificationRule>> = context.settingsDataStore.data.map { prefs ->
        runCatching { json.decodeFromString<List<NotificationRule>>(prefs[Keys.notificationRules] ?: "[]") }.getOrDefault(emptyList())
    }

    val notificationFilterConfig: Flow<NotificationFilterConfig> = context.settingsDataStore.data.map { prefs ->
        NotificationFilterConfig(
            enabled = prefs[Keys.notificationFiltering] ?: false,
            managedPackages = runCatching { json.decodeFromString<List<String>>(prefs[Keys.managedPackages] ?: "[]").toSet() }.getOrDefault(emptySet()),
            rules = runCatching { json.decodeFromString<List<NotificationRule>>(prefs[Keys.notificationRules] ?: "[]") }.getOrDefault(emptyList())
        )
    }.distinctUntilChanged()

    suspend fun setMasterEnabled(value: Boolean) = context.settingsDataStore.edit { it[Keys.masterEnabled] = value }
    suspend fun setVibrationEnabled(value: Boolean) = context.settingsDataStore.edit { it[Keys.vibrationEnabled] = value }
    suspend fun setRingtoneUri(value: String?) = context.settingsDataStore.edit {
        if (value == null) it.remove(Keys.ringtoneUri) else it[Keys.ringtoneUri] = value
    }
    suspend fun saveRules(value: List<Rule>) = context.settingsDataStore.edit {
        it[Keys.rules] = json.encodeToString(value)
    }

    suspend fun setNotificationFiltering(value: Boolean) = context.settingsDataStore.edit {
        it[Keys.notificationFiltering] = value
    }
    suspend fun saveManagedPackages(value: Set<String>) = context.settingsDataStore.edit {
        it[Keys.managedPackages] = json.encodeToString(value.sorted())
    }
    suspend fun saveNotificationRules(value: List<NotificationRule>) = context.settingsDataStore.edit {
        it[Keys.notificationRules] = json.encodeToString(value)
    }

    suspend fun currentNotificationFiltering(): Boolean = notificationFiltering.first()
    suspend fun currentManagedPackages(): Set<String> = managedPackages.first()
    suspend fun currentNotificationRules(): List<NotificationRule> = notificationRules.first()
    suspend fun currentRules(): List<Rule> = rules.first()
    suspend fun currentMasterEnabled(): Boolean = masterEnabled.first()
    suspend fun currentVibrationEnabled(): Boolean = vibrationEnabled.first()
    suspend fun currentRingtoneUri(): String? = ringtoneUri.first()
}

data class NotificationFilterConfig(
    val enabled: Boolean,
    val managedPackages: Set<String>,
    val rules: List<NotificationRule>
) {
    fun shouldEvaluate(packageName: String): Boolean = enabled && packageName in managedPackages
}
