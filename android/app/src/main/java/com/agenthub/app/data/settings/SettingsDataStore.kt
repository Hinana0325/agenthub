package com.agenthub.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val FONT_SIZE = stringPreferencesKey("font_size")
        private val E2E_ENABLED = booleanPreferencesKey("e2e_enabled")
        private val E2E_KEY = stringPreferencesKey("e2e_key")

        private const val DEFAULT_THEME = "system"
        private const val DEFAULT_FONT_SIZE = "medium"
        private const val DEFAULT_E2E_ENABLED = false
        private const val DEFAULT_E2E_KEY = ""
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE] ?: DEFAULT_THEME
    }

    val fontSize: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[FONT_SIZE] ?: DEFAULT_FONT_SIZE
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setFontSize(size: String) {
        context.dataStore.edit { it[FONT_SIZE] = size }
    }

    // ── E2E Encryption ──

    val e2eEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[E2E_ENABLED] ?: DEFAULT_E2E_ENABLED
    }

    val e2eKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[E2E_KEY] ?: DEFAULT_E2E_KEY
    }

    suspend fun setE2eEnabled(enabled: Boolean) {
        context.dataStore.edit { it[E2E_ENABLED] = enabled }
    }

    suspend fun setE2eKey(key: String) {
        context.dataStore.edit { it[E2E_KEY] = key }
    }
}
