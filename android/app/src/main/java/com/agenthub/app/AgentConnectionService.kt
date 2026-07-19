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
import com.agenthub.app.data.repository.ChatRepository
import com.agenthub.app.core.datastore.SettingsDataStore
import com.agenthub.app.transport.ConnectionRepository
import com.agenthub.app.widget.WidgetDataProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AgentConnectionService — Foreground Service
 * 保持 Agent WebSocket/SSE 连接在后台不被系统杀死。
 * Android 8+ 要求前台服务显示持久通知。
 *
 * 架构说明（已重构）：
 *  Transport 所有权由 [ConnectionRepository]（@Singleton）统一管理。
 *  本 Service 和 [com.agenthub.app.feature.chat.ChatViewModel] 共享同一个
 *  transport 实例，彻底消除了此前各自创建独立 transport 导致的双连接问题。
 *
 *  本 Service 通过注入的 [ConnectionRepository] 建立连接、监控连接状态并
 *  在断开时自动重连。连接状态同步到 [WidgetDataProvider] 供 Widget 显示。
 */
@AndroidEntryPoint
class AgentConnectionService : Service() {

    @Inject lateinit var repository: ChatRepository
    @Inject lateinit var settingsDataStore: SettingsDataStore
    @Inject lateinit var connectionRepository: ConnectionRepository

    private val smartNotificationManager = SmartNotificationManager()

    /** Service 级别的协程作用域，用于后台连接维持与重连 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 连接状态监控协程，便于在 onDestroy 中取消 */
    private var connectionMonitorJob: Job? = null

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
        val agentName = intent?.getStringExtra("agent_name") ?: "Agent Control Center"
        val notification = buildNotification(agentName)
        startForeground(NOTIFICATION_ID, notification)

        // Critical 4 修复：启动后台连接维持（若尚未连接则建立 keep-alive 连接）
        startConnectionMaintenance()

        // START_STICKY：被系统杀死后自动重建并重新维持连接
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 停止连接监控并取消协程作用域。
        // Transport 生命周期由 ConnectionRepository (@Singleton) 管理，
        // Service 销毁时不 disconnect/shutdown —— ChatViewModel 或其他消费者
        // 可能仍在使用连接。
        connectionMonitorJob?.cancel()
        serviceScope.cancel()
        try { unregisterReceiver(replyReceiver) } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * 启动后台连接维持。
     *
     * 1. 读取已保存的 AgentConfig（与 ChatViewModel.connectWith 一致的过滤条件）
     * 2. 若无可用配置，保持前台服务以便后续重试
     * 3. 通过 [ConnectionRepository] 建立连接（含 E2E 密钥）—— Repository 作为
     *    @Singleton 持有唯一 transport，与 ChatViewModel 共享同一连接
     * 4. 观察 [ConnectionRepository.connectionState]：同步到 [WidgetDataProvider]
     *    供 Widget 显示，断开时带退避自动重连
     */
    private fun startConnectionMaintenance() {
        // onStartCommand 可能多次触发，检查与监控协程赋值非原子会导致并发启动。
        // 若已有连接监控协程在运行则直接返回，避免重复启动。
        if (connectionMonitorJob?.isActive == true) return
        serviceScope.launch {
            try {
                val savedConfig = repository.getAllConfigs().let { flow ->
                    flow.first().firstOrNull {
                        it.serverUrl.isNotBlank() && !it.id.startsWith("seed_")
                    }
                }
                if (savedConfig == null || savedConfig.serverUrl.isBlank()) {
                    // 无可用配置，无法维持连接；保持前台服务以便后续重试
                    return@launch
                }

                // E2E 密钥逻辑与 ChatViewModel.connectWith 保持一致
                val e2eKey = if (savedConfig.type in setOf(
                        com.agenthub.app.agent.model.AgentType.Hermes,
                        com.agenthub.app.agent.model.AgentType.OpenClaw,
                        com.agenthub.app.agent.model.AgentType.OpenCode
                    )) {
                    val enabled = settingsDataStore.e2eEnabled.first()
                    if (enabled) settingsDataStore.e2eKey.first().takeIf { it.isNotBlank() } else null
                } else null

                // 通过 ConnectionRepository 建立连接（共享单例 transport）
                connectionRepository.connect(savedConfig, e2eKey = e2eKey)

                // 同步连接状态到 WidgetDataProvider，供 Widget 显示
                WidgetDataProvider.updateConnectionState(
                    this@AgentConnectionService,
                    isConnected = true,
                    agentName = savedConfig.name
                )

                // 监控连接状态：同步到 Widget 并在断开时带退避重连
                connectionMonitorJob?.cancel()
                connectionMonitorJob = serviceScope.launch {
                    connectionRepository.connectionState.collect { state ->
                        WidgetDataProvider.updateConnectionState(
                            this@AgentConnectionService,
                            isConnected = state.isConnected,
                            agentName = savedConfig.name
                        )
                        if (!state.isConnected) {
                            // 退避后尝试重连，避免频繁重连消耗资源
                            delay(RECONNECT_BACKOFF_MS)
                            try {
                                connectionRepository.connect(savedConfig, e2eKey = e2eKey)
                            } catch (_: Exception) {
                                // 重连失败，等待下一次状态变化触发重试
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // 静默失败；START_STICKY 会在系统重建后再次触发 onStartCommand
            }
        }
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

        /** Critical 4：断线重连退避间隔（毫秒） */
        private const val RECONNECT_BACKOFF_MS = 5000L

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
