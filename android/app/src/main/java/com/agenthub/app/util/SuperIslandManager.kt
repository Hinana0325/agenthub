package com.agenthub.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.agenthub.app.MainActivity
import com.agenthub.app.R

/**
 * 小米超级岛 / 通知栏管理工具类
 * 提供胶囊通知、常驻通知等功能
 */
class SuperIslandManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ISLAND = "agent_island"
        private const val CHANNEL_ISLAND_NAME = "Agent Island"
        private const val NOTIFICATION_ID_ISLAND = 1001

        @Volatile
        private var instance: SuperIslandManager? = null

        fun getInstance(context: Context): SuperIslandManager {
            return instance ?: synchronized(this) {
                instance ?: SuperIslandManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ISLAND,
                CHANNEL_ISLAND_NAME,
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
     * 显示岛通知（胶囊/灵动岛样式）
     */
    fun showIslandNotification(
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

            val notification = NotificationCompat.Builder(context, CHANNEL_ISLAND)
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
            manager.notify(NOTIFICATION_ID_ISLAND, notification)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 移除岛通知
     */
    fun dismissIslandNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_ISLAND)
    }

    /**
     * 更新岛通知状态
     */
    fun updateIslandStatus(agentName: String, status: String) {
        val statusText = when (status) {
            "active" -> "Active"
            "thinking" -> "Thinking..."
            "idle" -> "Idle"
            "error" -> "Error"
            else -> status
        }
        showIslandNotification(agentName, statusText, status, agentName)
    }
}
