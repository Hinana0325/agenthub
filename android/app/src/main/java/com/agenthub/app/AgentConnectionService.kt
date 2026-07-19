package com.agenthub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.agenthub.app.data.notification.SmartNotificationManager

/**
 * AgentConnectionService — Foreground Service
 * 保持 Agent WebSocket/SSE 连接在后台不被系统杀死。
 * Android 8+ 要求前台服务显示持久通知。
 */
class AgentConnectionService : Service() {

    private val smartNotificationManager = SmartNotificationManager()

    /**
     * 检查消息是否应该触发通知
     * 使用 SmartNotificationManager 基于优先级过滤
     */
    fun shouldNotifyForMessage(messageContent: String): Boolean {
        val message = com.agenthub.app.data.model.Message(
            id = "temp",
            sessionId = "temp",
            role = com.agenthub.app.data.model.MessageRole.Assistant,
            content = messageContent
        )
        return smartNotificationManager.shouldShowNotification(message)
    }

    /**
     * 获取智能通知标题
     */
    fun getSmartNotificationTitle(messageContent: String): String {
        val message = com.agenthub.app.data.model.Message(
            id = "temp",
            sessionId = "temp",
            role = com.agenthub.app.data.model.MessageRole.Assistant,
            content = messageContent
        )
        return smartNotificationManager.getNotificationTitle(message)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReplyReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val agentName = intent?.getStringExtra("agent_name") ?: "AgentHub"
        val notification = buildNotification(agentName)
        startForeground(NOTIFICATION_ID, notification)

        // 如果被系统杀死则自动重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(replyReceiver) } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(agentName: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Reply action with RemoteInput
        val remoteInput = RemoteInput.Builder(REPLY_KEY)
            .setLabel(getString(R.string.notif_reply_hint))
            .build()

        val replyIntent = Intent(ACTION_REPLY).apply {
            setPackage(packageName)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            this, REPLY_REQUEST_CODE, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.notif_reply),
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_connected_title))
            .setContentText(getString(R.string.notif_connected_text, agentName))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .addAction(replyAction)
            .build()
    }

    private fun registerReplyReceiver() {
        val filter = IntentFilter(ACTION_REPLY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(replyReceiver, filter)
        }
    }

    private val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_REPLY) {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence(REPLY_KEY)?.toString()
                if (!replyText.isNullOrBlank()) {
                    val launchIntent = Intent(ctx, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(EXTRA_REPLY_TEXT, replyText)
                    }
                    ctx.startActivity(launchIntent)
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_connection"
        private const val NOTIFICATION_ID = 2001
        private const val REPLY_KEY = "reply_key"
        private const val REPLY_REQUEST_CODE = 1002
        private const val ACTION_REPLY = "com.agenthub.app.ACTION_REPLY"
        const val EXTRA_REPLY_TEXT = "reply_text"

        /** 启动服务 */
        fun start(ctx: Context, agentName: String) {
            val intent = Intent(ctx, AgentConnectionService::class.java).apply {
                putExtra("agent_name", agentName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** 停止服务 */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AgentConnectionService::class.java))
        }
    }
}
