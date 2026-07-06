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
        private val ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val FONT_SIZE = stringPreferencesKey("font_size")
        private val E2E_ENABLED = booleanPreferencesKey("e2e_enabled")
        private val E2E_KEY = stringPreferencesKey("e2e_key")
        // Custom theme keys
        private val CUSTOM_PRIMARY_COLOR = stringPreferencesKey("custom_primary_color")
        private val CUSTOM_ACCENT_COLOR = stringPreferencesKey("custom_accent_color")
        private val CUSTOM_BACKGROUND_COLOR = stringPreferencesKey("custom_background_color")
        private val CUSTOM_FONT_SIZE = stringPreferencesKey("custom_font_size")
        private val CUSTOM_CORNER_RADIUS = androidx.datastore.preferences.core.intPreferencesKey("custom_corner_radius")
        private val CUSTOM_THEME_ENABLED = booleanPreferencesKey("custom_theme_enabled")

        private const val DEFAULT_THEME = "system"
        private const val DEFAULT_ACCENT = "blue"
        private const val DEFAULT_FONT_SIZE = "medium"
        private const val DEFAULT_E2E_ENABLED = false
        private const val DEFAULT_E2E_KEY = ""
        private const val DEFAULT_CUSTOM_PRIMARY = "#185FA5"
        private const val DEFAULT_CUSTOM_ACCENT = "#535F70"
        private const val DEFAULT_CUSTOM_BACKGROUND = "#FDFBFF"
        private const val DEFAULT_CUSTOM_FONT_SIZE = "medium"
        private const val DEFAULT_CUSTOM_CORNER_RADIUS = 16
        private const val DEFAULT_CUSTOM_THEME_ENABLED = false
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE] ?: DEFAULT_THEME
    }

    val accentColor: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ACCENT_COLOR] ?: DEFAULT_ACCENT
    }

    val fontSize: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[FONT_SIZE] ?: DEFAULT_FONT_SIZE
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setAccentColor(color: String) {
        context.dataStore.edit { it[ACCENT_COLOR] = color }
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

    // ── Custom Theme ──

    val customThemeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_THEME_ENABLED] ?: DEFAULT_CUSTOM_THEME_ENABLED
    }

    val customPrimaryColor: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_PRIMARY_COLOR] ?: DEFAULT_CUSTOM_PRIMARY
    }

    val customAccentColor: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_ACCENT_COLOR] ?: DEFAULT_CUSTOM_ACCENT
    }

    val customBackgroundColor: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_BACKGROUND_COLOR] ?: DEFAULT_CUSTOM_BACKGROUND
    }

    val customFontSize: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_FONT_SIZE] ?: DEFAULT_CUSTOM_FONT_SIZE
    }

    val customCornerRadius: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_CORNER_RADIUS] ?: DEFAULT_CUSTOM_CORNER_RADIUS
    }

    suspend fun setCustomThemeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CUSTOM_THEME_ENABLED] = enabled }
    }

    suspend fun setCustomPrimaryColor(color: String) {
        context.dataStore.edit { it[CUSTOM_PRIMARY_COLOR] = color }
    }

    suspend fun setCustomAccentColor(color: String) {
        context.dataStore.edit { it[CUSTOM_ACCENT_COLOR] = color }
    }

    suspend fun setCustomBackgroundColor(color: String) {
        context.dataStore.edit { it[CUSTOM_BACKGROUND_COLOR] = color }
    }

    suspend fun setCustomFontSize(size: String) {
        context.dataStore.edit { it[CUSTOM_FONT_SIZE] = size }
    }

    suspend fun setCustomCornerRadius(radius: Int) {
        context.dataStore.edit { it[CUSTOM_CORNER_RADIUS] = radius }
    }
}
