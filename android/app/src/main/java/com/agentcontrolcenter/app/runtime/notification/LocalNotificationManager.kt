package com.agentcontrolcenter.app.runtime.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agentcontrolcenter.app.MainActivity
import com.agentcontrolcenter.app.R
import androidx.core.app.Person

/**
 * 本地通知管理工具类
 * 替代旧的 Capacitor LocalNotifyPlugin
 *
 * 通知样式：
 * - Android 7+ (API 24) 使用 MessagingStyle，支持多消息气泡和 Person 头像
 * - 低于 API 24 回退到 BigTextStyle
 * - Android 7+ 支持通知分组（group summary），将多条 Agent 消息归为一组
 */
class LocalNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_DEFAULT = "agent_notifications"
        private const val CHANNEL_DEFAULT_NAME = "Agent Notifications"
        private const val GROUP_KEY_AGENT_MESSAGES = "group_agent_messages"
        private const val SUMMARY_NOTIFICATION_ID = -1

        @Volatile
        private var instance: LocalNotificationManager? = null

        fun getInstance(context: Context): LocalNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: LocalNotificationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_DEFAULT,
                CHANNEL_DEFAULT_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Agent notifications and alerts"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 发送本地通知 — 使用 MessagingStyle（Android 7+）展示聊天消息。
     *
     * MessagingStyle 优势：
     * - 显示对话气泡而非纯文本
     * - 支持 Person（发送者身份）
     * - 多条消息自动堆叠为对话视图
     *
     * @param title 通知标题（通常为 Agent 名称）
     * @param content 通知内容（消息文本）
     * @param senderName 消息发送者名称（用于 MessagingStyle 的 Person）
     * @param data 额外数据，通过 Intent extra 传递
     */
    fun notify(
        title: String?,
        content: String?,
        data: Map<String, String> = emptyMap()
    ): Boolean {
        return notifyWithStyle(title, content, null, data)
    }

    /**
     * 发送带 MessagingStyle 的聊天通知。
     *
     * @param agentName Agent 名称（显示为对话标题）
     * @param messageText 消息文本
     * @param data 额外数据
     */
    fun notifyChatMessage(
        agentName: String,
        messageText: String,
        data: Map<String, String> = emptyMap()
    ): Boolean {
        return notifyWithStyle(agentName, messageText, agentName, data)
    }

    /**
     * 内部方法：根据 API 级别选择 MessagingStyle 或 BigTextStyle。
     */
    private fun notifyWithStyle(
        title: String?,
        content: String?,
        senderName: String?,
        data: Map<String, String>
    ): Boolean {
        try {
            val safeTitle = title ?: "Agent Control Center"
            val safeContent = content ?: ""

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data?.forEach { (key, value) ->
                    putExtra(key, value)
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context, System.currentTimeMillis().toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_DEFAULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup(GROUP_KEY_AGENT_MESSAGES)

            // Android 7+ (API 24) 使用 MessagingStyle
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && senderName != null) {
                val person = Person.Builder()
                    .setName(senderName)
                    .setImportant(true)
                    .build()
                builder.setStyle(
                    NotificationCompat.MessagingStyle(person)
                        .setConversationTitle(safeTitle)
                        .addMessage(safeContent, System.currentTimeMillis(), person)
                )
            } else {
                // 回退到 BigTextStyle
                builder.setContentTitle(safeTitle)
                    .setContentText(safeContent)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(safeContent))
            }

            val notification = builder.build()
            val notificationId = System.currentTimeMillis().toInt()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
                // 发送分组摘要通知
                notifyGroupSummary()
            } else {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(context).notify(notificationId, notification)
                    notifyGroupSummary()
                }
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * 发送分组摘要通知（Android 7+）。
     * 将多条 Agent 消息归为一组，通知栏只显示一行摘要，展开后显示所有消息。
     */
    private fun notifyGroupSummary() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            val summaryIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val summaryPendingIntent = PendingIntent.getActivity(
                context, 0, summaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val summaryNotification = NotificationCompat.Builder(context, CHANNEL_DEFAULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Agent Control Center")
                .setContentText("New messages")
                .setStyle(NotificationCompat.InboxStyle()
                    .setBigContentTitle("Agent Control Center")
                    .setSummaryText("New messages"))
                .setGroup(GROUP_KEY_AGENT_MESSAGES)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(summaryPendingIntent)
                .build()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, summaryNotification)
            }
        } catch (_: Exception) {
            // 分组摘要失败不影响主通知
        }
    }

    /**
     * I3: Android 16 (API 36, BAKLAVA) Live Updates 通知。
     *
     * 使用 [Notification.ProgressStyle] 持续展示进行中的任务（如 Agent 流式响应、
     * 导航、外卖配送等）。通知标记为 ongoing，用户无法滑动清除，需在任务结束时
     * 调用 [cancel] 取消。
     *
     * 仅在 API 36+ 设备生效；低于 BAKLAVA 的设备直接 return，不影响现有通知逻辑。
     * 即使 compileSdk >= 36，运行时也会再次校验 SDK_INT，确保在低版本设备上安全。
     *
     * @param notificationId 通知 ID（同一 ID 的通知会被更新而非新建，适合反复刷新进度）
     * @param title 通知标题
     * @param content 通知正文
     * @param progress 进度 0-100；[indeterminate] 为 true 时忽略此值
     * @param indeterminate 是否为不确定进度（如流式响应开始时未知总长度）
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun notifyLiveUpdate(
        notificationId: Int,
        title: String,
        content: String,
        progress: Int,
        indeterminate: Boolean = false
    ) {
        // 低于 Android 16 的设备不支持 ProgressStyle，静默跳过
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return

        // Android 13+ 需要运行时授予 POST_NOTIFICATIONS 权限，未授权时静默跳过，
        // 与 [notifyWithStyle] 的权限检查逻辑保持一致，避免触发 SecurityException。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            // Notification.ProgressStyle 是 Android 16 (API 36) 引入的新样式，
            // 专为 Live Updates 场景设计。setStyledByProgress(false) 表示由系统
            // 根据 progress 自行渲染进度条样式而非完全自定义。
            val progressStyle = Notification.ProgressStyle()
                .setStyledByProgress(false)
                .setProgress(progress)
                .setProgressIndeterminate(indeterminate)

            // 注意：此处使用平台 Notification.Builder 而非 NotificationCompat.Builder，
            // 因为 ProgressStyle 目前仅存在于平台 API 中，NotificationCompat 尚未封装。
            val notification = Notification.Builder(context, CHANNEL_DEFAULT)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setStyle(progressStyle)
                .build()

            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: Exception) {
            // ProgressStyle 在部分 OEM ROM 上可能行为不一致，失败时静默跳过，
            // 不影响流式响应本身。
        }
    }

    /**
     * 取消所有通知
     */
    fun cancelAll() {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /**
     * 按 ID 取消通知
     */
    fun cancel(id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }
}
