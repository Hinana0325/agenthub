package com.agenthub.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.agenthub.app.core.database.AppDatabase
import com.agenthub.app.widget.WidgetInputActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AgentHub Home Screen Widget 2.0
 * 交互式 Widget：状态仪表盘 + 快捷输入 + 最后消息预览
 */
class AgentHubWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.agent_hub_widget)

            // 点击 Widget 主体打开聊天页面
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingOpen = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingOpen)

            // Sprint 8: 点击 Widget 输入区（TextView）拉起 WidgetInputActivity，
            // 在弹窗内完成真正的文本输入（RemoteViews 不支持 EditText 的解决方案）
            val inputIntent = Intent(context, WidgetInputActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val inputPending = PendingIntent.getActivity(
                context, REQUEST_CODE_INPUT, inputIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_input, inputPending)

            // Sprint 8: 发送按钮同样拉起 WidgetInputActivity。
            // 此前指向 WidgetSendReceiver 的广播并未在 Manifest 注册（死代码），
            // 改为统一打开输入弹窗，保证按钮始终可用。
            val sendIntent = Intent(context, WidgetInputActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val sendPending = PendingIntent.getActivity(
                context, REQUEST_CODE_SEND, sendIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_send, sendPending)

            // 设置语音按钮
            val voiceIntent = Intent(context, WidgetVoiceReceiver::class.java).apply {
                action = ACTION_WIDGET_VOICE
            }
            val voicePending = PendingIntent.getBroadcast(
                context, 101, voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_voice, voicePending)

            // 异步更新状态、最后消息、延迟
            updateWidgetData(context, views, appWidgetManager, widgetId)
        }
    }

    private fun updateWidgetData(
        context: Context,
        views: RemoteViews,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        // Critical 5 修复：使用 goAsync() 延长广播处理时间，并在 finally 中 finish()。
        // 不再使用 SupervisorJob 创建常驻 scope（原实现每次 onUpdate 都创建不取消的 scope，
        // 导致协程泄漏）。此处 CoroutineScope 无 SupervisorJob，协程完成后 scope 自然结束可被 GC。
        // updateAll 通过手动 new provider 调用 onUpdate 时不在广播上下文，goAsync() 会抛
        // IllegalStateException，捕获后退化为普通异步（finish 跳过），scope 仍会自然结束。
        val pendingResult: BroadcastReceiver.PendingResult? = try {
            goAsync()
        } catch (_: IllegalStateException) {
            null
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val prefs = context.getSharedPreferences("agent_hub_prefs", Context.MODE_PRIVATE)

                // 获取连接状态
                val isConnected = prefs.getBoolean("ws_connected", false)
                val agentName = prefs.getString("current_agent_name", "") ?: ""
                val latency = prefs.getLong("avg_latency_ms", 0L)

                // 更新连接状态指示
                views.setInt(
                    R.id.widget_status_dot,
                    "setBackgroundResource",
                    if (isConnected) R.drawable.dot_connected else R.drawable.dot_disconnected
                )

                // 更新 Agent 名称
                val displayName = if (agentName.isNotEmpty()) agentName else "AgentHub"
                views.setTextViewText(R.id.widget_agent_name, "\uD83E\uDD16 $displayName")

                // 更新延迟
                views.setTextViewText(
                    R.id.widget_latency,
                    if (latency > 0) "${latency} ms" else "— ms"
                )

                // 获取最后一条消息
                try {
                    val lastMessage = db.messageDao().getLastMessage()
                    val preview = if (lastMessage != null) {
                        val content = lastMessage.content
                        if (content.length > 120) content.substring(0, 120) + "…" else content
                    } else {
                        context.getString(R.string.widget_last_message_empty)
                    }
                    views.setTextViewText(R.id.widget_last_message, preview)
                } catch (_: Exception) {
                    views.setTextViewText(R.id.widget_last_message, context.getString(R.string.widget_last_message_empty))
                }

                appWidgetManager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {
                // 静默失败，保持默认显示
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_SEND = "com.agenthub.app.WIDGET_SEND"
        const val ACTION_WIDGET_VOICE = "com.agenthub.app.WIDGET_VOICE"

        // Sprint 8: Widget PendingIntent 请求码
        // 容器复用 0（保持原有行为）；输入区与发送按钮各自独立以避免被覆盖。
        private const val REQUEST_CODE_INPUT = 200
        private const val REQUEST_CODE_SEND = 100

        /** 更新所有 Widget 实例 */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, AgentHubWidget::class.java)
            val ids = manager.getAppWidgetIds(provider)
            if (ids.isNotEmpty()) {
                AgentHubWidget().onUpdate(context, manager, ids)
            }
        }
    }
}

/**
 * Widget 快捷发送按钮的 BroadcastReceiver
 */
class WidgetSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("agent_hub_widget_prefs", Context.MODE_PRIVATE)
        val pendingText = prefs.getString("widget_pending_input", "") ?: ""

        if (pendingText.isNotBlank()) {
            // 将待发送文本保存，由 App 读取并发送
            prefs.edit().remove("widget_pending_input").apply()

            // 打开 App 并传递发送指令
            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("widget_action", "send_message")
                putExtra("widget_message", pendingText)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(openIntent)
        } else {
            // 没有输入文本，打开 App 聊天界面
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(openIntent)
        }
    }
}

/**
 * Widget 语音按钮的 BroadcastReceiver
 */
class WidgetVoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 打开 App 并启动语音输入
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("widget_action", "voice_input")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(openIntent)
    }
}
