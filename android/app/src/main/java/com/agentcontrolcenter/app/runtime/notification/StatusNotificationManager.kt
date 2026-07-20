package com.agentcontrolcenter.app.runtime.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.agentcontrolcenter.app.MainActivity
import com.agentcontrolcenter.app.R

/**
 * Agent 状态通知管理器。
 *
 * 通过标准的 Android 通知 API 展示一条常驻 / 状态通知，用于向用户呈现
 * Agent 的当前状态（active / thinking / idle / error 等）。
 *
 * 重要说明：
 *  本类是一个标准的 Android 状态通知实现，**并非** MIUI "超级岛"（Super Island /
 *  灵动岛 / 胶囊通知）。MIUI Super Island 依赖小米闭源的私有 API，未公开文档且
 *  不在官方 SDK 中暴露，因此无法在第三方应用中真实复刻其胶囊形态。
 *  此前本类命名为 `SuperIslandManager` 容易产生误导，现重命名为
 *  [StatusNotificationManager] 以正本清源。
 *
 *  若未来需要接入真正的 MIUI Super Island，应通过反射调用小米私有 API 或等待
 *  小米公开相关 SDK，并在此处做机型 / 系统版本判断后降级到本状态通知。
 *
 * 通知 ID 固定为 [NOTIFICATION_ID_STATUS]（3001），与历史版本保持一致，
 *  避免在升级后出现残留的旧通知。
 */
class StatusNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_STATUS = "agent_status"
        private const val CHANNEL_STATUS_NAME = "Agent Status"
        private const val NOTIFICATION_ID_STATUS = 3001

        @Volatile
        private var instance: StatusNotificationManager? = null

        fun getInstance(context: Context): StatusNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: StatusNotificationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_STATUS,
                CHANNEL_STATUS_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent status and notifications"
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 显示状态通知。
     *
     * @param title 通知的副标题（subText），通常为会话或场景标题
     * @param content 通知正文
     * @param status Agent 状态标识：`active` / `thinking` / `idle` / `error` 等，
     *  `active` 状态下通知为常驻（ongoing），其余状态可被滑动清除
     * @param agentName 通知标题，默认 "Agent"
     * @return true 表示通知发送成功，false 表示发送过程中抛出异常
     */
    fun showStatusNotification(
        title: String,
        content: String,
        status: String = "active",
        agentName: String = "Agent"
    ): Boolean {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("island_action", "open")
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(agentName)
                .setContentText(content)
                .setSubText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(status == "active")
                .setAutoCancel(status != "active")
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_STATUS, notification)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 移除状态通知。
     */
    fun dismissStatusNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_STATUS)
    }

    /**
     * 更新状态通知的状态文本。
     *
     * @param agentName 通知标题
     * @param status 状态标识，将映射为用户可读的状态文案
     */
    fun updateStatus(agentName: String, status: String) {
        val statusText = when (status) {
            "active" -> "Active"
            "thinking" -> "Thinking..."
            "idle" -> "Idle"
            "error" -> "Error"
            else -> status
        }
        showStatusNotification(agentName, statusText, status, agentName)
    }
}
