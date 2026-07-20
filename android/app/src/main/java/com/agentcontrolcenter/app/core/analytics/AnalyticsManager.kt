package com.agentcontrolcenter.app.core.analytics

import com.agentcontrolcenter.app.core.datastore.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隐私优先的本地埋点管理器。
 *
 * 设计理念：
 * - 纯本地埋点，不集成任何第三方 SDK（Firebase / Sentry Analytics / Amplitude 等）
 * - 所有事件仅写入内存 ring buffer，不落盘，不网络上报
 * - 用户可在设置页关闭埋点（[SettingsDataStore.analyticsEnabled]），关闭后不再记录新事件
 * - 已记录的事件可通过 [exportEvents] 导出为 JSON 供用户自行查看或分享
 *
 * Ring buffer 实现：
 * - 使用 [ArrayDeque] 作为环形缓冲区，容量上限 [MAX_EVENTS]
 * - 超过容量时丢弃最旧的事件（FIFO），保证内存占用可控
 * - 通过 [bufferLock] 同步所有读写，保证 @Singleton 多协程并发安全
 *
 * 线程安全：
 * - [analyticsEnabled] 使用 @Volatile + StateFlow 缓存，避免每次 logEvent 都读 DataStore
 * - ring buffer 通过 synchronized 块保护，读 [getEvents] / 写 [logEvent] 均线程安全
 *
 * @param settingsDataStore 用于读取埋点开关
 */
@Singleton
class AnalyticsManager @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) {

    /**
     * 埋点事件类型枚举。
     *
     * 与 iOS 端 `AnalyticsManager.AnalyticsEvent` 对齐，
     * 事件名使用 snake_case 以保持跨平台一致性。
     *
     * @property eventName 事件的序列化名称（snake_case）
     */
    enum class AnalyticsEvent(val eventName: String) {
        /** 页面浏览 — 携带 screen_name 参数 */
        SCREEN_VIEW("screen_view"),

        /** 按钮点击 — 携带 button_id 参数 */
        BUTTON_CLICK("button_click"),

        /** Agent 连接成功 */
        AGENT_CONNECT("agent_connect"),

        /** Agent 断开连接 */
        AGENT_DISCONNECT("agent_disconnect"),

        /** 用户发送消息 */
        MESSAGE_SENT("message_sent"),

        /** 收到 Agent 回复消息 */
        MESSAGE_RECEIVED("message_received"),

        /** 错误事件 — 携带 error_type / error_message 参数 */
        ERROR("error"),

        /** 会话创建 */
        SESSION_CREATED("session_created"),

        /** 功能使用 — 携带 feature_name 参数 */
        FEATURE_USED("feature_used")
    }

    /**
     * 一条埋点记录。
     *
     * @property timestamp 事件发生时间（epoch 毫秒，跨平台兼容）
     * @property name 事件名称（对应 [AnalyticsEvent.eventName]）
     * @property params 附加参数，key 为参数名，value 为任意可 JSON 序列化的值
     */
    data class AnalyticsRecord(
        val timestamp: Long,
        val name: String,
        val params: Map<String, Any>
    )

    companion object {
        /** Ring buffer 最大容量 — 超出后丢弃最旧的事件。 */
        private const val MAX_EVENTS = 1000
    }

    /** Ring buffer — 使用 ArrayDeque 实现 FIFO 环形缓冲。 */
    private val ringBuffer = ArrayDeque<AnalyticsRecord>()

    /** Ring buffer 读写锁，保证多协程并发安全。 */
    private val bufferLock = Any()

    /**
     * 埋点开关的内存缓存。
     *
     * 初始值为 true（与 DataStore 默认值一致），通过后台协程从
     * [SettingsDataStore.analyticsEnabled] 同步。[logEvent] 直接读取此字段，
     * 避免每次埋点都发起异步 Flow 采集。
     */
    @Volatile
    private var analyticsEnabled: Boolean = true

    /** 暴露给外部的埋点开关状态（可用于 UI 显示当前开关状态）。 */
    private val _enabledState = MutableStateFlow(true)
    val enabledState: StateFlow<Boolean> = _enabledState.asStateFlow()

    /**
     * Manager 内部协程作用域。
     *
     * 使用 SupervisorJob + IO 调度器，子协程异常不会相互取消。
     * 生命周期与 @Singleton 实例一致（即应用进程生命周期）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 后台同步埋点开关状态到内存缓存
        scope.launch {
            settingsDataStore.analyticsEnabled.collect { enabled ->
                analyticsEnabled = enabled
                _enabledState.value = enabled
            }
        }
    }

    /**
     * 记录一条埋点事件。
     *
     * 当埋点开关关闭时（[analyticsEnabled] == false）直接返回，不写入 ring buffer。
     * 写入操作通过 [bufferLock] 同步，超过 [MAX_EVENTS] 时丢弃最旧的事件。
     *
     * @param name 事件名称，建议使用 [AnalyticsEvent.eventName] 以保证一致性
     * @param params 附加参数，默认为空。value 可为 String / Number / Boolean 等可 JSON 序列化类型
     */
    fun logEvent(name: String, params: Map<String, Any> = emptyMap()) {
        if (!analyticsEnabled) return

        val record = AnalyticsRecord(
            timestamp = System.currentTimeMillis(),
            name = name,
            params = params
        )

        synchronized(bufferLock) {
            if (ringBuffer.size >= MAX_EVENTS) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(record)
        }
    }

    /**
     * 记录页面浏览事件的便捷方法。
     *
     * 等价于 `logEvent(AnalyticsEvent.SCREEN_VIEW.eventName, mapOf("screen_name" to screenName))`。
     *
     * @param screenName 页面名称（如 "SettingsScreen" / "ChatScreen"）
     */
    fun logScreenView(screenName: String) {
        logEvent(AnalyticsEvent.SCREEN_VIEW.eventName, mapOf("screen_name" to screenName))
    }

    /**
     * 返回 ring buffer 中所有事件的快照副本。
     *
     * 返回的是新 List，调用方修改不影响内部 ring buffer。
     * 顺序为时间正序（最旧到最新）。
     *
     * @return 事件列表的不可变快照
     */
    fun getEvents(): List<AnalyticsRecord> {
        return synchronized(bufferLock) {
            ringBuffer.toList()
        }
    }

    /**
     * 导出所有事件为 JSON 字符串。
     *
     * JSON 格式（数组，每个元素为一条事件）：
     * ```json
     * [
     *   {
     *     "timestamp": 1700000000000,
     *     "name": "screen_view",
     *     "params": { "screen_name": "SettingsScreen" }
     *   }
     * ]
     * ```
     *
     * 导出后不自动清空 ring buffer，如需清空请显式调用 [clearEvents]。
     *
     * @return JSON 格式的事件列表字符串
     */
    fun exportEvents(): String {
        val events = getEvents()
        val jsonArray = JSONArray()

        for (event in events) {
            val obj = JSONObject()
            obj.put("timestamp", event.timestamp)
            obj.put("name", event.name)

            val paramsObj = JSONObject()
            for ((key, value) in event.params) {
                // JSONObject.put 接受 String / Int / Long / Double / Boolean / JSONObject / JSONArray
                // 等类型，Any 类型会被自动适配或转为 toString。
                when (value) {
                    is String -> paramsObj.put(key, value)
                    is Int -> paramsObj.put(key, value)
                    is Long -> paramsObj.put(key, value)
                    is Double -> paramsObj.put(key, value)
                    is Float -> paramsObj.put(key, value.toDouble())
                    is Boolean -> paramsObj.put(key, value)
                    is Number -> paramsObj.put(key, value.toDouble())
                    else -> paramsObj.put(key, value.toString())
                }
            }
            obj.put("params", paramsObj)

            jsonArray.put(obj)
        }

        return jsonArray.toString()
    }

    /**
     * 清空 ring buffer 中的所有事件。
     *
     * 通常在用户导出事件后调用，或用于隐私清除。
     */
    fun clearEvents() {
        synchronized(bufferLock) {
            ringBuffer.clear()
        }
    }
}
