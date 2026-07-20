package com.agentcontrolcenter.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agentcontrolcenter.app.core.security.KeystoreManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @javax.inject.Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val FONT_SIZE = stringPreferencesKey("font_size")
        private val E2E_ENABLED = booleanPreferencesKey("e2e_enabled")
        private val E2E_KEY = stringPreferencesKey("e2e_key")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val ANALYTICS_ENABLED = booleanPreferencesKey("analytics_enabled")
        private val FEATURE_FLAG_OVERRIDES = stringPreferencesKey("feature_flag_overrides")
        private val AUTO_BACKUP_SCHEDULE = stringPreferencesKey("auto_backup_schedule")
        private val DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")

        private const val DEFAULT_THEME = "system"
        private const val DEFAULT_FONT_SIZE = "medium"
        private const val DEFAULT_E2E_ENABLED = false
        private const val DEFAULT_E2E_KEY = ""
        private const val DEFAULT_ANALYTICS_ENABLED = true
        private const val DEFAULT_FEATURE_FLAG_OVERRIDES = "{}"
        // P3-3: 自动备份调度默认 MANUAL（手动），避免在用户未感知时占用后台资源。
        // 取值与 BackupManager.BackupSchedule.storageValue 对齐：daily / weekly / manual。
        private const val DEFAULT_AUTO_BACKUP_SCHEDULE = "manual"
        private const val DEFAULT_DYNAMIC_COLOR_ENABLED = false
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE] ?: DEFAULT_THEME
    }

    val fontSize: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[FONT_SIZE] ?: DEFAULT_FONT_SIZE
    }

    /** 是否已完成首次启动引导 */
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setFontSize(size: String) {
        context.dataStore.edit { it[FONT_SIZE] = size }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }

    // ── E2E Encryption ──
    // e2eKey 通过 Android Keystore 硬件级加密存储，防止 root 或数据库导出导致密钥泄露。
    // 读取时自动处理旧版明文数据（decryptOrRaw），写入时强制加密。

    val e2eEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[E2E_ENABLED] ?: DEFAULT_E2E_ENABLED
    }

    val e2eKey: Flow<String> = context.dataStore.data.map { prefs ->
        val stored = prefs[E2E_KEY] ?: DEFAULT_E2E_KEY
        if (stored.isBlank()) stored else KeystoreManager.decryptOrRaw(stored)
    }.flowOn(Dispatchers.IO)

    suspend fun setE2eEnabled(enabled: Boolean) {
        context.dataStore.edit { it[E2E_ENABLED] = enabled }
    }

    suspend fun setE2eKey(key: String) {
        val encrypted = if (key.isBlank()) key else KeystoreManager.encrypt(key)
        context.dataStore.edit { it[E2E_KEY] = encrypted }
    }

    // ── Analytics ──
    // 隐私优先的本地埋点开关。默认开启，用户可在设置页关闭。
    // 关闭后 AnalyticsManager 不再写入 ring buffer，已记录的事件仍可通过设置页导出。

    /** 是否启用本地埋点（默认 true）。 */
    val analyticsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ANALYTICS_ENABLED] ?: DEFAULT_ANALYTICS_ENABLED
    }

    /**
     * 设置埋点开关。
     *
     * @param enabled true 启用本地埋点，false 禁用
     */
    suspend fun setAnalyticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[ANALYTICS_ENABLED] = enabled }
    }

    // ── Feature Flag Overrides ──
    // Feature Flag 用户覆盖存储为 JSON 字符串，key 为枚举名，value 为布尔值。
    // 未在 map 中出现的标志使用 [FeatureFlagManager.FeatureFlag.defaultEnabled]。
    // 使用 JSON 字符串而非单个 boolean key 是因为需要区分「未设置」与「显式设为 false」。

    /**
     * Feature Flag 用户覆盖（JSON 字符串），由 [FeatureFlagManager] 解析。
     * 返回空字符串表示尚无任何覆盖。
     */
    val featureFlagOverrides: Flow<Map<String, Boolean>> = context.dataStore.data.map { prefs ->
        val json = prefs[FEATURE_FLAG_OVERRIDES] ?: DEFAULT_FEATURE_FLAG_OVERRIDES
        parseOverrides(json)
    }.flowOn(Dispatchers.IO)

    /**
     * 覆盖整个 Feature Flag 覆盖表。
     *
     * @param overrides 标志名到布尔值的映射
     */
    suspend fun setFeatureFlagOverrides(overrides: Map<String, Boolean>) {
        val json = JSONObject().apply {
            overrides.forEach { (key, value) -> put(key, value) }
        }.toString()
        context.dataStore.edit { it[FEATURE_FLAG_OVERRIDES] = json }
    }

    /**
     * 将 JSON 字符串解析为标志覆盖表。
     * 解析失败时返回空 map（容错，避免损坏的存储数据阻塞应用启动）。
     */
    private fun parseOverrides(json: String): Map<String, Boolean> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, obj.getBoolean(key))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── Auto Backup (P3-3) ──
    // 自动备份调度，存储为字符串以保持前向兼容：新增枚举值时无需 schema 迁移。
    // 取值与 com.agentcontrolcenter.app.data.backup.BackupManager.BackupSchedule.storageValue 对齐。

    /**
     * 自动备份调度（默认 MANUAL）。
     *
     * 取值：`daily` / `weekly` / `manual`，由 [BackupManager] 解析为
     * [com.agentcontrolcenter.app.data.backup.BackupManager.BackupSchedule] 枚举。
     */
    val autoBackupSchedule: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[AUTO_BACKUP_SCHEDULE] ?: DEFAULT_AUTO_BACKUP_SCHEDULE
    }

    /**
     * 设置自动备份调度。
     *
     * @param schedule 调度字符串，应取自
     * [com.agentcontrolcenter.app.data.backup.BackupManager.BackupSchedule.storageValue]，
     * 非法值由枚举的 `fromStorageValue` 回退为 MANUAL。
     */
    suspend fun setAutoBackup(schedule: String) {
        context.dataStore.edit { it[AUTO_BACKUP_SCHEDULE] = schedule }
    }

    // ── Material You 动态取色 ──
    // Android 12+ (API 31+) 支持从系统壁纸提取色板。
    // 默认关闭，用户可在设置页开启。开启后覆盖 AccentBlue 调色板。

    /** 是否启用 Material You 动态取色（默认 false）。 */
    val dynamicColorEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DYNAMIC_COLOR_ENABLED] ?: DEFAULT_DYNAMIC_COLOR_ENABLED
    }

    /**
     * 设置动态取色开关。
     *
     * @param enabled true 启用动态取色，false 使用应用自带调色板
     */
    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR_ENABLED] = enabled }
    }
}
