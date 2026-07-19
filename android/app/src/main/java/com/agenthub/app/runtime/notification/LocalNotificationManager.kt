package com.agenthub.app.runtime.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agenthub.app.MainActivity
import com.agenthub.app.R

/**
 * 本地通知管理工具类
 * 替代旧的 Capacitor LocalNotifyPlugin
 */
class LocalNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_DEFAULT = "agent_notifications"
        private const val CHANNEL_DEFAULT_NAME = "Agent Notifications"

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
     * 发送本地通知
     */
    fun notify(
        title: String?,
        content: String?,
        data: Map<String, String> = emptyMap()
    ): Boolean {
        try {
            val safeTitle = title ?: "AgentHub"
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

            val notification = NotificationCompat.Builder(context, CHANNEL_DEFAULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(safeTitle)
                .setContentText(safeContent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(safeContent))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                NotificationManagerCompat.from(context).notify(
                    System.currentTimeMillis().toInt(),
                    notification
                )
            } else {
                // Android 13+ requires POST_NOTIFICATIONS runtime permission
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(context).notify(
                        System.currentTimeMillis().toInt(),
                        notification
                    )
                }
            }
            return true
        } catch (e: Exception) {
            return false
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
