package com.agentcontrolcenter.app.core.config

import kotlinx.coroutines.flow.Flow

// MARK: - AppConfiguration
// 统一配置入口的领域模型层。把散落在 SettingsDataStore（DataStore）/
// McpViewModel（SharedPreferences）/ WidgetDataProvider（SharedPreferences）/
// ChatRepository（Room）中的配置收口为按域聚合的不可变快照对象。
//
// 设计原则：
// - 上层（ViewModel / Composable）只依赖本文件中的领域对象 + ConfigRepository，
//   不再直接依赖 SettingsDataStore 或各处 SharedPreferences。
// - 领域对象均为 data class + 不可变字段，便于 Flow<*> 派生组合。
// - 跨端对齐：iOS 端通过 AppPreferences.swift 暴露同构 AppConfiguration。

/**
 * 外观偏好。
 *
 * 与 iOS `AppPreferences.AppearanceConfig` 对齐。
 * 存储键名跨端一致：`theme` / `font_size` / `dynamic_color_enabled`。
 */
data class AppearanceConfig(
    /** 主题模式：`light` / `dark` / `system` */
    val themeMode: String = "system",
    /** 字体大小：`small` / `medium` / `large` */
    val fontSize: String = "medium",
    /** 是否启用 Material You 动态取色（Android 12+） */
    val dynamicColorEnabled: Boolean = false
)

/**
 * 安全与加密偏好。
 *
 * 与 iOS `AppPreferences.SecurityConfig` 对齐。
 * apiKey 不在此对象中（敏感字段由 KeystoreManager / KeychainManager 直管）；
 * 但 E2E 密钥放这里，因为运行时（ConnectionRepository / WebSocketTransport）需要
 * 在用户切换 E2E 开关或重新生成密钥时即时拿到新密钥应用到活动连接。
 */
data class SecurityConfig(
    /** 是否启用端到端传输加密（P2P / 中继模式） */
    val e2eEncryptionEnabled: Boolean = false,
    /** 是否启用本地埋点（隐私开关） */
    val analyticsEnabled: Boolean = true,
    /**
     * 当前 E2E 密钥（已通过 KeystoreManager 解密的明文，空串表示未设置）。
     *
     * 运行时消费方应使用 [effectiveE2eKey] 取「开关开启且密钥非空」的有效值，
     * 而非直接读此字段。
     */
    val e2eKey: String = ""
) {
    /**
     * 计算应应用到活动连接的有效 E2E 密钥。
     *
     * - E2E 关闭 → null（不加密）
     * - E2E 开启但密钥为空 → null（无法加密，退回明文避免崩溃）
     * - E2E 开启且密钥非空 → 密钥
     */
    val effectiveE2eKey: String? get() = e2eKey.takeIf { e2eEncryptionEnabled && it.isNotBlank() }
}

/**
 * Agent 默认配置。
 *
 * 用于 SetupWizard 中预填表单，以及新增 Agent 时的默认值。
 * 与 iOS `AppPreferences.AgentDefaults` 对齐：键名一致（`defaultModel` /
 * `temperature` / `maxTokens`）但跨端存储载体不同（Android 用 DataStore，
 * iOS 用 UserDefaults）。
 */
data class AgentDefaults(
    /** 默认模型名称（如 gpt-4 / claude-3-opus） */
    val defaultModel: String = "",
    /** 默认温度（0.0 - 2.0） */
    val defaultTemperature: Float = 0.7f,
    /** 默认 max_tokens（256 - 32768） */
    val defaultMaxTokens: Int = 4096
)

/**
 * 数据与备份偏好。
 *
 * 与 iOS `AppPreferences.BackupConfig` 对齐。
 */
data class BackupConfig(
    /** 自动备份调度：`daily` / `weekly` / `manual` */
    val autoBackupSchedule: String = "manual"
)

/**
 * Onboarding 状态。
 *
 * 与 iOS `AppPreferences.OnboardingState` 对齐：键名 `onboarding_completed`。
 */
data class OnboardingState(
    /** 是否已完成首次启动引导 */
    val completed: Boolean = false
)

/**
 * Feature Flag 用户覆盖表。
 *
 * key 为 [com.agentcontrolcenter.app.core.featureflag.FeatureFlagManager.FeatureFlag] 的枚举名，
 * value 为布尔覆盖值。未出现的标志使用其 `defaultEnabled`。
 * 与 iOS `FeatureFlagManager` 存储的 JSON 表完全对齐。
 */
data class FeatureFlagOverrides(
    val overrides: Map<String, Boolean> = emptyMap()
) {
    /** 查询某个标志的覆盖值，未设置时返回 null（由调用方回退到默认值） */
    fun overrideFor(name: String): Boolean? = overrides[name]
}

/**
 * 全量应用配置快照。
 *
 * 由 [ConfigRepository.appConfig] 暴露的 Flow<*> 元素。任何一个子配置变更都会
 * 触发整个快照重新发射，便于上层用单一 Flow 派生 UI State。
 */
data class AppConfiguration(
    val appearance: AppearanceConfig = AppearanceConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val agentDefaults: AgentDefaults = AgentDefaults(),
    val backup: BackupConfig = BackupConfig(),
    val onboarding: OnboardingState = OnboardingState(),
    val featureFlags: FeatureFlagOverrides = FeatureFlagOverrides()
)

/**
 * 统一配置仓库接口。
 *
 * 把分散的存储入口（SettingsDataStore / McpViewModel SharedPreferences /
 * WidgetDataProvider SharedPreferences / ChatRepository Room）按域聚合为单一
 * Flow<AppConfiguration>。上层只依赖本接口，不再感知底层存储载体。
 *
 * 与 iOS `AppPreferences` 协议对齐。
 */
interface ConfigRepository {
    /** 全量配置快照流。任一子配置变更都会重新发射 */
    val appConfig: Flow<AppConfiguration>

    /**
     * 安全配置子流（仅 [SecurityConfig]）。
     *
     * 供运行时单例（如 [com.agentcontrolcenter.app.transport.ConnectionRepository]）
     * 精确订阅 E2E 开关 / 密钥变更，避免订阅整个 [appConfig] 在主题等无关变更时也收到回调。
     */
    val security: Flow<SecurityConfig>

    /**
     * Agent 默认配置子流（仅 [AgentDefaults]）。
     *
     * 供运行时在连接时回退默认 model/temperature/maxTokens，以及未来「应用到现有 Agent」策略。
     */
    val agentDefaults: Flow<AgentDefaults>

    // ── Appearance ──
    suspend fun setThemeMode(mode: String)
    suspend fun setFontSize(size: String)
    suspend fun setDynamicColorEnabled(enabled: Boolean)

    // ── Security ──
    suspend fun setE2eEncryptionEnabled(enabled: Boolean)
    suspend fun setAnalyticsEnabled(enabled: Boolean)

    // ── Agent Defaults ──
    suspend fun setAgentDefaults(defaults: AgentDefaults)

    // ── Backup ──
    suspend fun setAutoBackupSchedule(schedule: String)

    // ── Onboarding ──
    suspend fun setOnboardingCompleted(completed: Boolean)

    // ── Feature Flags ──
    suspend fun setFeatureFlagOverride(name: String, enabled: Boolean?)
    suspend fun clearAllFeatureFlagOverrides()

    /**
     * 清空所有应用级偏好（不动 AgentConfig / MCP / 聊天记录）。
     *
     * 对应 iOS `AppPreferences.clearAllPreferences()`。危险操作，仅在用户
     * 明确点击「清除所有偏好」时调用。
     */
    suspend fun clearAllPreferences()
}
