package com.messagestar.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
    }

    val masterEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.masterEnabled] ?: true }
    val vibrationEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.vibrationEnabled] ?: true }
    val ringtoneUri: Flow<String?> = context.settingsDataStore.data.map { it[Keys.ringtoneUri] }
    val rules: Flow<List<Rule>> = context.settingsDataStore.data.map { prefs ->
        runCatching { json.decodeFromString<List<Rule>>(prefs[Keys.rules] ?: "[]") }.getOrDefault(emptyList())
    }

    suspend fun setMasterEnabled(value: Boolean) = context.settingsDataStore.edit { it[Keys.masterEnabled] = value }
    suspend fun setVibrationEnabled(value: Boolean) = context.settingsDataStore.edit { it[Keys.vibrationEnabled] = value }
    suspend fun setRingtoneUri(value: String?) = context.settingsDataStore.edit {
        if (value == null) it.remove(Keys.ringtoneUri) else it[Keys.ringtoneUri] = value
    }
    suspend fun saveRules(value: List<Rule>) = context.settingsDataStore.edit {
        it[Keys.rules] = json.encodeToString(value)
    }

    suspend fun currentRules(): List<Rule> = rules.first()
    suspend fun currentMasterEnabled(): Boolean = masterEnabled.first()
    suspend fun currentVibrationEnabled(): Boolean = vibrationEnabled.first()
    suspend fun currentRingtoneUri(): String? = ringtoneUri.first()
}
