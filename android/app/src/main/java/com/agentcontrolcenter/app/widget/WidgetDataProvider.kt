package com.agentcontrolcenter.app.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * Widget 和 App 之间共享数据的提供者
 * 使用 SharedPreferences 作为轻量级数据桥接
 */
object WidgetDataProvider {

    private const val PREFS_NAME = "agent_control_center_widget_prefs"
    private const val KEY_PENDING_INPUT = "widget_pending_input"

    private const val APP_PREFS_NAME = "agent_control_center_prefs"
    private const val KEY_WS_CONNECTED = "ws_connected"
    private const val KEY_AGENT_NAME = "current_agent_name"
    private const val KEY_AVG_LATENCY = "avg_latency_ms"

    private fun getWidgetPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getAppPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Widget 输入框写入待发送文本
     */
    fun setPendingInput(context: Context, text: String) {
        getWidgetPrefs(context).edit().putString(KEY_PENDING_INPUT, text).apply()
    }

    /**
     * App 端读取并清除待发送文本
     */
    fun consumePendingInput(context: Context): String? {
        val prefs = getWidgetPrefs(context)
        val text = prefs.getString(KEY_PENDING_INPUT, null)
        if (!text.isNullOrBlank()) {
            prefs.edit().remove(KEY_PENDING_INPUT).apply()
        }
        return text
    }

    /**
     * 更新连接状态（由 App 的 ConnectionService 调用）
     */
    fun updateConnectionState(context: Context, isConnected: Boolean, agentName: String? = null) {
        getAppPrefs(context).edit().apply {
            putBoolean(KEY_WS_CONNECTED, isConnected)
            if (agentName != null) putString(KEY_AGENT_NAME, agentName)
            apply()
        }
    }

    /**
     * 更新平均延迟（由 PerformanceMonitor 调用）
     */
    fun updateLatency(context: Context, latencyMs: Long) {
        getAppPrefs(context).edit().putLong(KEY_AVG_LATENCY, latencyMs).apply()
    }

    /**
     * 获取当前连接状态
     */
    fun isConnected(context: Context): Boolean =
        getAppPrefs(context).getBoolean(KEY_WS_CONNECTED, false)

    /**
     * 获取当前 Agent 名称
     */
    fun getAgentName(context: Context): String =
        getAppPrefs(context).getString(KEY_AGENT_NAME, "") ?: ""
}
