package com.agentcontrolcenter.app.core.featureflag

import com.agentcontrolcenter.app.core.datastore.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature Flag 管理器 — 功能开关的统一入口。
 *
 * 职责：
 * - 为每个功能标志提供默认值（开发中的功能默认关闭）
 * - 支持用户通过设置页覆盖默认值，覆盖持久化到 [SettingsDataStore]
 * - 提供同步查询 [isEnabled] 和响应式观察 [observeFlag] 两种访问方式
 *
 * 设计理念：
 * - 默认值在 [FeatureFlag] 枚举中集中定义，避免散落在各业务模块
 * - 用户覆盖存储为 JSON 字符串（见 [SettingsDataStore.featureFlagOverrides]），
 *   支持区分「未设置」（使用默认值）与「显式设为 false」
 * - 内存中维护覆盖表的 [StateFlow] 快照，[isEnabled] 同步读取无需挂起
 *
 * 线程安全：
 * - [overrides] 使用 [MutableStateFlow]，所有写入通过原子赋值完成
 * - 后台协程从 [SettingsDataStore] 同步覆盖表到内存，解耦持久层与查询层
 *
 * @param settingsDataStore 用于持久化用户覆盖
 */
@Singleton
class FeatureFlagManager @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) {

    /**
     * Feature Flag 枚举 — 应用中所有可通过开关控制的功能。
     *
     * @property defaultEnabled 默认是否启用。
     *   - 已发布且稳定的功能默认为 true
     *   - 开发中或实验性功能默认为 false（如 [DEVICE_SYNC]）
     */
    enum class FeatureFlag(val defaultEnabled: Boolean) {
        /** 工作流引擎 — Agent 任务编排 */
        WORKFLOW_ENGINE(defaultEnabled = true),

        /** Agent 市场 — 浏览和安装第三方 Agent */
        MARKETPLACE(defaultEnabled = true),

        /** 设备同步 — P2P 跨设备数据同步（开发中，默认关闭） */
        DEVICE_SYNC(defaultEnabled = false),

        /** 数据洞察 — 使用统计与分析 */
        INSIGHTS(defaultEnabled = true),

        /** 对比模式 — 多 Agent 输出对比 */
        COMPARE_MODE(defaultEnabled = true),

        /** MCP 服务器 — Model Context Protocol 集成 */
        MCP_SERVERS(defaultEnabled = true),

        /** 自定义主题 — 用户自定义配色 */
        CUSTOM_THEME(defaultEnabled = true),

        /** 语音输入 — Speech-to-Text */
        VOICE_INPUT(defaultEnabled = true),

        /** 端到端加密 — 消息加密传输 */
        E2E_ENCRYPTION(defaultEnabled = true)
    }

    /**
     * 用户覆盖表的内存快照。
     *
     * key 为 [FeatureFlag.name]（枚举名），value 为用户设置的覆盖值。
     * 未出现在此 map 中的标志使用 [FeatureFlag.defaultEnabled]。
     *
     * 通过后台协程从 [SettingsDataStore.featureFlagOverrides] 同步，
     * [isEnabled] 直接读取此快照，避免每次查询都发起异步 IO。
     */
    private val _overrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** 用户覆盖表的只读 StateFlow，可用于 UI 展示。 */
    val overrides: StateFlow<Map<String, Boolean>> = _overrides.asStateFlow()

    /**
     * Manager 内部协程作用域。
     *
     * 使用 SupervisorJob + IO 调度器，子协程异常不会相互取消。
     * 生命周期与 @Singleton 实例一致（即应用进程生命周期）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 后台同步 DataStore 中的覆盖表到内存快照
        scope.launch {
            settingsDataStore.featureFlagOverrides.collect { persisted ->
                _overrides.value = persisted
            }
        }
    }

    /**
     * 同步查询某个功能标志是否启用。
     *
     * 查询顺序：
     * 1. 用户覆盖（[overrides] 中存在则使用覆盖值）
     * 2. 默认值（[FeatureFlag.defaultEnabled]）
     *
     * @param flag 要查询的功能标志
     * @return true 表示功能已启用
     */
    fun isEnabled(flag: FeatureFlag): Boolean {
        return _overrides.value[flag.name] ?: flag.defaultEnabled
    }

    /**
     * 响应式观察某个功能标志的启用状态。
     *
     * 当用户通过 [setOverride] 修改覆盖值时，此 Flow 会发出新值。
     * 使用 [distinctUntilChanged] 过滤重复值，避免下游收到冗余通知。
     *
     * @param flag 要观察的功能标志
     * @return 布尔值 Flow，初始值为当前状态
     */
    fun observeFlag(flag: FeatureFlag): Flow<Boolean> {
        return _overrides.map { overrides ->
            overrides[flag.name] ?: flag.defaultEnabled
        }.distinctUntilChanged()
    }

    /**
     * 设置用户覆盖值并持久化。
     *
     * 覆盖值写入 [SettingsDataStore]，后台协程同步到内存 [_overrides]，
     * 之后 [isEnabled] 和 [observeFlag] 会立即反映新值。
     *
     * @param flag 要覆盖的功能标志
     * @param enabled 覆盖值（true 启用 / false 禁用）
     */
    suspend fun setOverride(flag: FeatureFlag, enabled: Boolean) {
        val updated = _overrides.value.toMutableMap().apply {
            this[flag.name] = enabled
        }
        settingsDataStore.setFeatureFlagOverrides(updated)
        // _overrides 会通过 init 中的 collector 自动更新，
        // 但为降低延迟（避免等待 DataStore 写入 + 回读的往返），
        // 此处也直接更新内存快照。
        _overrides.value = updated
    }

    /**
     * 清除某个功能标志的用户覆盖，恢复为默认值。
     *
     * @param flag 要清除覆盖的功能标志
     */
    suspend fun clearOverride(flag: FeatureFlag) {
        val updated = _overrides.value.toMutableMap().apply {
            remove(flag.name)
        }
        settingsDataStore.setFeatureFlagOverrides(updated)
        _overrides.value = updated
    }
}
