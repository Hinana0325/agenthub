package com.agentcontrolcenter.app.core.config

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agentcontrolcenter.app.core.datastore.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - ConfigRepositoryImpl
// 统一配置仓库实现。包装 [SettingsDataStore]（向后兼容，所有现有偏好继续走原存储）
// + 新增 agent_defaults / mcp_meta 等子域。原有调用方可继续直接用 SettingsDataStore；
// 新代码（SetupWizard / FeatureFlag UI / 新增 ViewModel）应改为依赖 [ConfigRepository]。

/**
 * Agent 默认值专用 DataStore 文件。
 *
 * 与 settings 数据分离，避免与 [SettingsDataStore] 的 key 命名空间冲突；
 * 同时与 iOS `AppPreferences.agentDefaults` 跨端 key 对齐（`defaultModel` /
 * `temperature` / `maxTokens`）。
 */
private val Context.agentDefaultsDataStore by preferencesDataStore(name = "agent_defaults")

@Singleton
class ConfigRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) : ConfigRepository {

    companion object {
        // Agent defaults keys（与 iOS AppPreferences.AgentDefaults 对齐）
        private val DEFAULT_MODEL = stringPreferencesKey("defaultModel")
        private val DEFAULT_TEMPERATURE = floatPreferencesKey("temperature")
        private val DEFAULT_MAX_TOKENS = intPreferencesKey("maxTokens")

        private const val DEFAULT_MODEL_STR = ""
        private const val DEFAULT_TEMPERATURE_VAL = 0.7f
        private const val DEFAULT_MAX_TOKENS_VAL = 4096
    }

    // ── AppearanceConfig ──
    private val appearanceFlow: Flow<AppearanceConfig> = combine(
        settingsDataStore.themeMode,
        settingsDataStore.fontSize,
        settingsDataStore.dynamicColorEnabled
    ) { theme, font, dynamic ->
        AppearanceConfig(themeMode = theme, fontSize = font, dynamicColorEnabled = dynamic)
    }

    // ── SecurityConfig ──
    private val securityFlow: Flow<SecurityConfig> = combine(
        settingsDataStore.e2eEnabled,
        settingsDataStore.analyticsEnabled
    ) { e2e, analytics ->
        SecurityConfig(e2eEncryptionEnabled = e2e, analyticsEnabled = analytics)
    }

    // ── AgentDefaults ──
    private val agentDefaultsFlow: Flow<AgentDefaults> =
        context.agentDefaultsDataStore.data.map { prefs ->
            AgentDefaults(
                defaultModel = prefs[DEFAULT_MODEL] ?: DEFAULT_MODEL_STR,
                defaultTemperature = prefs[DEFAULT_TEMPERATURE] ?: DEFAULT_TEMPERATURE_VAL,
                defaultMaxTokens = prefs[DEFAULT_MAX_TOKENS] ?: DEFAULT_MAX_TOKENS_VAL
            )
        }

    // ── BackupConfig ──
    private val backupFlow: Flow<BackupConfig> = settingsDataStore.autoBackupSchedule
        .map { BackupConfig(autoBackupSchedule = it) }

    // ── OnboardingState ──
    private val onboardingFlow: Flow<OnboardingState> = settingsDataStore.onboardingCompleted
        .map { OnboardingState(completed = it) }

    // ── FeatureFlagOverrides ──
    private val featureFlagFlow: Flow<FeatureFlagOverrides> = settingsDataStore.featureFlagOverrides
        .map { FeatureFlagOverrides(overrides = it) }

    /**
     * 全量配置快照：6 个子 Flow 合并为单个 AppConfiguration。
     *
     * 任一子配置变更都会重新发射整个快照，便于上层用单一 Flow 派生 UI State。
     * 组合 6 路 Flow 是必要的开销——比让 ViewModel 自己分别 collect 6 个流更简单。
     */
    override val appConfig: Flow<AppConfiguration> = combine(
        combine(appearanceFlow, securityFlow) { appearance, security -> appearance to security },
        combine(agentDefaultsFlow, backupFlow) { agentDefaults, backup -> agentDefaults to backup },
        combine(onboardingFlow, featureFlagFlow) { onboarding, featureFlags -> onboarding to featureFlags }
    ) { (appearance, security), (agentDefaults, backup), (onboarding, featureFlags) ->
        AppConfiguration(
            appearance = appearance,
            security = security,
            agentDefaults = agentDefaults,
            backup = backup,
            onboarding = onboarding,
            featureFlags = featureFlags
        )
    }.flowOn(Dispatchers.IO)

    // ── Appearance ──

    override suspend fun setThemeMode(mode: String) = settingsDataStore.setThemeMode(mode)
    override suspend fun setFontSize(size: String) = settingsDataStore.setFontSize(size)
    override suspend fun setDynamicColorEnabled(enabled: Boolean) =
        settingsDataStore.setDynamicColorEnabled(enabled)

    // ── Security ──

    override suspend fun setE2eEncryptionEnabled(enabled: Boolean) =
        settingsDataStore.setE2eEnabled(enabled)
    override suspend fun setAnalyticsEnabled(enabled: Boolean) =
        settingsDataStore.setAnalyticsEnabled(enabled)

    // ── Agent Defaults ──

    override suspend fun setAgentDefaults(defaults: AgentDefaults) {
        context.agentDefaultsDataStore.edit { prefs ->
            prefs[DEFAULT_MODEL] = defaults.defaultModel
            prefs[DEFAULT_TEMPERATURE] = defaults.defaultTemperature
            prefs[DEFAULT_MAX_TOKENS] = defaults.defaultMaxTokens
        }
    }

    // ── Backup ──

    override suspend fun setAutoBackupSchedule(schedule: String) =
        settingsDataStore.setAutoBackup(schedule)

    // ── Onboarding ──

    override suspend fun setOnboardingCompleted(completed: Boolean) =
        settingsDataStore.setOnboardingCompleted(completed)

    // ── Feature Flags ──

    override suspend fun setFeatureFlagOverride(name: String, enabled: Boolean?) {
        // 读取当前覆盖表，按需增删单条覆盖，再写回。`first()` 在 suspend 上下文中安全。
        val current = settingsDataStore.featureFlagOverrides.first()
        val newOverrides = current.toMutableMap().apply {
            if (enabled == null) {
                remove(name) // null = 删除覆盖，回退到默认值
            } else {
                put(name, enabled)
            }
        }
        settingsDataStore.setFeatureFlagOverrides(newOverrides)
    }

    override suspend fun clearAllFeatureFlagOverrides() {
        settingsDataStore.setFeatureFlagOverrides(emptyMap())
    }

    // ── Clear All Preferences ──

    /**
     * 清空所有应用级偏好（外观 / 安全 / Agent 默认值 / Onboarding / Feature Flag /
     * MCP 服务器配置 / Widget 数据）。
     *
     * 与 iOS `AppPreferences.clearAllPreferences()` 行为对齐（iOS allPreferenceKeys
     * 已补 mcp_servers / deviceSyncAutoSync）。注意：**不动 AgentConfig（Room）/
     * 聊天记录**，这些由 [ChatRepository] 单独触发。
     */
    override suspend fun clearAllPreferences() {
        // 1. Settings DataStore — 通过 SettingsDataStore 的 setter 重置为默认值
        settingsDataStore.setThemeMode("system")
        settingsDataStore.setFontSize("medium")
        settingsDataStore.setDynamicColorEnabled(false)
        settingsDataStore.setE2eEnabled(false)
        settingsDataStore.setAnalyticsEnabled(true)
        settingsDataStore.setAutoBackup("manual")
        settingsDataStore.setOnboardingCompleted(false)
        settingsDataStore.setFeatureFlagOverrides(emptyMap())

        // 2. Agent Defaults DataStore — 清空所有 key
        context.agentDefaultsDataStore.edit { it.clear() }

        // 3. MCP 服务器配置 SharedPreferences（与 McpViewModel.MCP_PREFS_NAME 对齐）
        context.getSharedPreferences("mcp_prefs", Context.MODE_PRIVATE).edit().clear().apply()

        // 4. Widget 数据 SharedPreferences（与 WidgetDataProvider.PREFS_NAME 对齐）
        context.getSharedPreferences("agent_control_center_widget_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()

        // 5. E2E Key — 通过 KeystoreManager 直接清，避免引入对 SettingsDataStore
        // 内部 key 的耦合（SettingsDataStore 暴露的 setE2eKey("") 也能清空，但语义
        // 是「设置空 passphrase」而非「清除」）
        settingsDataStore.setE2eKey("")
    }

    // ── Private Helpers ──

    /**
     * 把 JSON 字符串解析为覆盖表（容错）。复用 SettingsDataStore 的相同逻辑——
     * 这里重复实现而非暴露 SettingsDataStore 的 private 方法，是为了保持
     * ConfigRepository 是 SettingsDataStore 之上的抽象层。
     */
    @Suppress("unused")
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

    @Suppress("unused")
    private fun Preferences.toJsonString(): String {
        val obj = JSONObject()
        for (key in this.asMap().keys) {
            val v = this[key]
            if (v is Boolean) obj.put(key.name, v)
        }
        return obj.toString()
    }
}
