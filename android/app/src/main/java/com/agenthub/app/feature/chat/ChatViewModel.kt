package com.agenthub.app.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agenthub.app.agent.model.AgentConfig
import com.agenthub.app.agent.model.AgentType
import com.agenthub.app.agent.model.ConnectionState
import com.agenthub.app.data.model.Message
import com.agenthub.app.data.model.MessageRole
import com.agenthub.app.data.model.MessageStatus
import com.agenthub.app.data.model.Session
import com.agenthub.app.transport.protocol.AgentConnectionState
import com.agenthub.app.transport.protocol.AgentEvent
import com.agenthub.app.transport.protocol.AgentTransport
import com.agenthub.app.transport.TransportFactory
import com.agenthub.app.core.datastore.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import com.agenthub.app.AgentHubWidget
import com.agenthub.app.core.common.PerformanceMonitor
import com.agenthub.app.core.ui.VoiceInputManager
import com.agenthub.app.widget.WidgetInputActivity
import javax.inject.Inject
import com.agenthub.app.core.ui.VoiceChatManager
import com.agenthub.app.localmodel.LocalModelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val currentSessionId: String? = null,
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val connectionState: ConnectionState = ConnectionState(),
    val agentConfig: AgentConfig = AgentConfig(),
    val agentConfigs: List<AgentConfig> = emptyList(),
    val showWizard: Boolean = true,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    // Search state
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    // Voice state
    val isVoiceListening: Boolean = false,
    // Voice chat mode
    val isVoiceChatMode: Boolean = false,
    val voiceChatLastUserText: String = "",
    val voiceChatLastAgentText: String = "",
    // Local model state
    val localModels: List<LocalModelManager.LocalModel> = emptyList(),
    val isDiscoveringLocalModels: Boolean = false,
    val localModelError: String? = null,
    // Attachment state
    val pendingAttachmentType: String? = null,
    val pendingAttachmentData: String? = null,
    val pendingAttachmentName: String? = null,
    // Edit state
    val editingMessageId: String? = null,
    // Compare mode navigation trigger
    val comparePending: Boolean = false,
    // Reply state
    val replyingToMessageId: String? = null,
    val replyingToMessageContent: String? = null
)


@HiltViewModel
class ChatViewModel @Inject constructor(
    application: Application,
    private val repository: com.agenthub.app.data.repository.ChatRepository,
    private val settingsDataStore: SettingsDataStore
) : AndroidViewModel(application) {
    private val _transport = MutableStateFlow<AgentTransport?>(null)
    private var voiceInputManager: VoiceInputManager? = null
    private var voiceChatManager: VoiceChatManager? = null
    private val localModelManager = LocalModelManager()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            repository.getAllSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            repository.getAllConfigs().collect { configs ->
                _uiState.update { state ->
                    val firstConfig = configs.firstOrNull() ?: state.agentConfig
                    state.copy(agentConfig = firstConfig, agentConfigs = configs)
                }
            }
        }
        viewModelScope.launch {
            val savedConfig = repository.getAllConfigs().let { flow ->
                flow.first().firstOrNull { it.serverUrl.isNotBlank() && !it.id.startsWith("seed_") }
            }
            if (savedConfig != null) {
                _uiState.update { it.copy(showWizard = false) }
                connectWith(savedConfig)
            }
        }
        // Preload most recent session messages
        viewModelScope.launch {
            val sessions = repository.getAllSessionsList()
            val mostRecent = sessions.maxByOrNull { it.updatedAt }
            if (mostRecent != null) {
                _currentSessionId.value = mostRecent.id
                _uiState.update { it.copy(currentSessionId = mostRecent.id, showWizard = false) }
            }
        }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _transport.filterNotNull().flatMapLatest { it.events }.collect { event ->
                handleAgentEvent(event)
            }
        }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _transport.filterNotNull().flatMapLatest { it.connectionState }.collect { state ->
                handleConnectionState(state)
            }
        }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _currentSessionId.flatMapLatest { id ->
                if (id != null) repository.getMessagesBySession(id)
                else kotlinx.coroutines.flow.flowOf(emptyList())
            }.collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }

        // Sprint 8: 注册 Widget 快捷输入广播，接收 WidgetInputActivity 发来的消息
        registerWidgetMessageReceiver()
    }

    // Track when user message was sent for latency measurement
    private var lastUserMessageTime: Long = 0L

    /**
     * Sprint 8: 接收来自 [WidgetInputActivity] 的快捷输入消息。
     *
     * 生命周期：在 [init] 中通过 [registerWidgetMessageReceiver] 注册，
     * 在 [onCleared] 中注销。
     *
     * 架构约束：该接收器仅在 ChatViewModel 存活时生效（即 App 处于前台
     * 或进程未被回收）。若 App 已被系统冷启动回收，广播将丢失。后续可在
     * ChatViewModel 初始化时通过 WidgetDataProvider.consumePendingInput
     * 消费遗留消息作为补偿通道。
     */
    private val widgetMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(WidgetInputActivity.EXTRA_MESSAGE)
            if (!text.isNullOrBlank()) {
                handleWidgetMessage(text)
            }
        }
    }

    private fun registerWidgetMessageReceiver() {
        val filter = IntentFilter(WidgetInputActivity.ACTION_WIDGET_SEND_MESSAGE)
        // Android 13+ 要求显式声明 RECEIVER_NOT_EXPORTED 以接收同应用广播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplication<Application>().registerReceiver(widgetMessageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            getApplication<Application>().registerReceiver(widgetMessageReceiver, filter)
        }
    }

    private suspend fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageReceived -> {
                val sessionId = _uiState.value.currentSessionId
                if (sessionId == null) return

                // Record latency from user message to agent response
                if (lastUserMessageTime > 0) {
                    val latency = System.currentTimeMillis() - lastUserMessageTime
                    PerformanceMonitor.recordMessageLatency(latency)
                    lastUserMessageTime = 0L
                }

                if (event.isDelta) {
                    val lastIdx = _uiState.value.messages.lastIndex
                    if (lastIdx >= 0) {
                        val last = _uiState.value.messages[lastIdx]
                        if (last.role == MessageRole.Assistant) {
                            val newContent = last.content + event.content
                            _uiState.update { state ->
                                val updated = state.messages.toMutableList()
                                updated[lastIdx] = last.copy(
                                    content = newContent,
                                    status = MessageStatus.Received
                                )
                                state.copy(messages = updated)
                            }
                            // Persist accumulated delta to Room so it survives app restarts
                            repository.updateMessageStatus(last.id, newContent, MessageStatus.Received)
                            return
                        }
                    }
                }

                // Non-delta: persist via repository and use returned message (same ID) to avoid duplicates
                val persistedMsg = repository.sendMessage(sessionId, event.content, MessageRole.Assistant)
                repository.logActivity("message", "Agent response received", event.content.take(80))
                _uiState.update { state ->
                    val filtered = state.messages.filter { it.id != persistedMsg.id }
                    state.copy(
                        messages = filtered + Message(
                            id = persistedMsg.id, sessionId = sessionId, role = MessageRole.Assistant,
                            content = event.content, status = MessageStatus.Received
                        ),
                        isStreaming = false
                    )
                }
            }
            is AgentEvent.Error -> {
                repository.logActivity("error", "Agent error", event.message)
                _uiState.update { it.copy(isStreaming = false, isConnecting = false, errorMessage = event.message) }
            }
            is AgentEvent.Disconnected -> {
                repository.logActivity("connection", "Agent disconnected")
                _uiState.update { it.copy(isStreaming = false) }
            }
            is AgentEvent.Connected -> {
                repository.logActivity("connection", "Agent connected")
                _uiState.update { it.copy(isConnecting = false, showWizard = false) }
            }
            is AgentEvent.Reconnecting -> {
                repository.logActivity("connection", "Agent reconnecting...")
            }
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * 通过 [TransportFactory] 按 AgentType 获取传输实例并连接。
     * 若已存在同类型的传输则复用，否则断开旧传输并创建新实例。
     */
    private suspend fun connectWith(config: AgentConfig) {
        val current = _transport.value
        val needsNew = current == null || current.connectionState.value.agentType != config.type
        val transport = if (needsNew) {
            current?.disconnect()
            TransportFactory.create(config.type).also { _transport.value = it }
        } else {
            current
        }
        // E2E 仅对 WebSocket 对等传输（Hermes/OpenClaw/OpenCode）生效；
        // 从全局设置读取开关与密钥，未启用则为 null。
        val e2eKey = if (config.type in setOf(AgentType.Hermes, AgentType.OpenClaw, AgentType.OpenCode)) {
            val enabled = settingsDataStore.e2eEnabled.first()
            if (enabled) settingsDataStore.e2eKey.first().takeIf { it.isNotBlank() } else null
        } else null
        transport?.connect(config, e2eKey = e2eKey)
    }

    private fun handleConnectionState(state: AgentConnectionState) {
        _uiState.update {
            it.copy(connectionState = ConnectionState(
                isConnected = state.isConnected,
                serverUrl = state.serverUrl,
                agentType = state.agentType,
                latency = state.latency
            ))
        }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val state = _uiState.value
        if ((text.isEmpty() && state.pendingAttachmentType == null) || state.isStreaming) return

        // If we're editing an existing message, update it instead of sending a new one
        val editingId = state.editingMessageId
        if (editingId != null) {
            editMessage(editingId, text)
            return
        }

        lastUserMessageTime = System.currentTimeMillis()

        viewModelScope.launch {
            val sessionId = state.currentSessionId
                ?: repository.createSession("New Chat").let { session ->
                    _currentSessionId.value = session.id
                    _uiState.update { it.copy(currentSessionId = session.id) }
                    session.id
                }

            val userMsg = repository.sendMessage(
                sessionId = sessionId,
                content = text,
                role = MessageRole.User,
                attachmentType = state.pendingAttachmentType,
                attachmentData = state.pendingAttachmentData,
                attachmentName = state.pendingAttachmentName,
                replyToId = state.replyingToMessageId
            )
            repository.logActivity("message", "Message sent", text.take(80))

            _uiState.update { s ->
                val stateMsg = Message(
                    id = userMsg.id,
                    sessionId = userMsg.sessionId,
                    role = MessageRole.User,
                    content = text,
                    status = MessageStatus.Sent,
                    attachmentType = state.pendingAttachmentType,
                    attachmentData = state.pendingAttachmentData,
                    attachmentName = state.pendingAttachmentName,
                    replyToId = state.replyingToMessageId
                )
                s.copy(
                    messages = s.messages + stateMsg,
                    inputText = "",
                    isStreaming = true,
                    pendingAttachmentType = null,
                    pendingAttachmentData = null,
                    pendingAttachmentName = null,
                    replyingToMessageId = null,
                    replyingToMessageContent = null
                )
            }

            if (_uiState.value.connectionState.isConnected) {
                _transport.value?.sendMessage(sessionId, text)
            } else {
                simulateResponse()
            }
        }
    }

    private suspend fun simulateResponse() {
        kotlinx.coroutines.delay(500)
        val sessionId = _uiState.value.currentSessionId ?: return
        val reply = "Hello! You said: \"${_uiState.value.messages.lastOrNull()?.content}\"\n\n" +
                "> Offline mode — connect to an agent server for real AI responses."

        repository.sendMessage(sessionId, reply, MessageRole.Assistant).let { msg ->
            _uiState.update { state ->
                // Deduplicate: use returned message ID to avoid Room flow creating a second copy
                val filtered = state.messages.filter { it.id != msg.id }
                state.copy(
                    messages = filtered + Message(
                        id = msg.id, sessionId = sessionId, role = MessageRole.Assistant,
                        content = reply, status = MessageStatus.Received
                    ),
                    isStreaming = false
                )
            }
        }
    }

    // ── Message Edit ──

    fun startEditMessage(messageId: String, content: String) {
        _uiState.update { it.copy(inputText = content, editingMessageId = messageId) }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(editingMessageId = null) }
    }

    // ── Message Reply ──

    fun startReply(messageId: String, content: String) {
        _uiState.update { it.copy(
            replyingToMessageId = messageId,
            replyingToMessageContent = content.trim().take(100)
        ) }
    }

    fun cancelReply() {
        _uiState.update { it.copy(
            replyingToMessageId = null,
            replyingToMessageContent = null
        ) }
    }

    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            repository.updateMessageStatus(messageId, newContent, MessageStatus.Sent)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map {
                        if (it.id == messageId) it.copy(content = newContent) else it
                    },
                    inputText = "",
                    editingMessageId = null
                )
            }
        }
    }

    fun clearMessages() {
        viewModelScope.launch {
            _uiState.value.currentSessionId?.let { id ->
                // 清空传输层中当前 session 的对话历史，确保下次发送的消息
                // 不会携带清除前的上下文（/clear 命令的语义是「重新开始」）。
                _transport.value?.clearHistory(id)
                repository.deleteMessagesBySession(id)
            }
        }
        _uiState.update { it.copy(messages = emptyList(), errorMessage = null) }
    }

    fun dismissWizard() {
        _uiState.update { it.copy(showWizard = false, isConnecting = false) }
    }

    /** 打开连接向导（供离线横幅的「去连接」按钮调用）。 */
    fun openConnectWizard() {
        _uiState.update { it.copy(showWizard = true) }
    }

    fun connectToServer(url: String, apiKey: String, agentType: AgentType) {
        viewModelScope.launch {
            val existing = _uiState.value.agentConfigs.firstOrNull { it.serverUrl == url }
            val config = if (existing != null) {
                existing.copy(apiKey = apiKey, type = agentType)
            } else {
                AgentConfig(
                    id = java.util.UUID.randomUUID().toString(),
                    name = agentType.displayName,
                    type = agentType,
                    serverUrl = url,
                    apiKey = apiKey
                )
            }
            repository.saveConfig(config)

            connectWith(config)
            _uiState.update { state ->
                state.copy(
                    isConnecting = true,
                    showWizard = true,
                    errorMessage = null,
                    agentConfig = config
                )
            }
            repository.logActivity("connection", "Connecting to ${config.serverUrl}", config.type.displayName)
        }
    }

    // ── Session management ──

    fun createNewSession() {
        viewModelScope.launch {
            val session = repository.createSession("New Chat")
            _currentSessionId.value = session.id
            _uiState.update { it.copy(currentSessionId = session.id) }
        }
    }

    fun switchToSession(sessionId: String) {
        _currentSessionId.value = sessionId
        _uiState.update { it.copy(currentSessionId = sessionId) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            // 清空传输层中该 session 的对话历史，避免历史残留导致下次
            // 同 sessionId（若被复用）的请求携带已删除会话的上下文。
            // transport 可能为 null（尚未连接），安全调用会跳过；
            // OpenAIHttpTransport 清空客户端历史，WebSocketTransport 清空本地缓存。
            _transport.value?.clearHistory(sessionId)
            repository.deleteSession(sessionId)
            if (_uiState.value.currentSessionId == sessionId) {
                val sessions = _uiState.value.sessions
                val next = sessions.firstOrNull { it.id != sessionId }
                if (next != null) {
                    switchToSession(next.id)
                } else {
                    _currentSessionId.value = null
                    _uiState.update { it.copy(currentSessionId = null) }
                }
            }
        }
    }

    fun toggleSessionPin(sessionId: String, isPinned: Boolean) {
        viewModelScope.launch {
            repository.togglePin(sessionId, isPinned)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Copy text to the system clipboard.
     */
    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AgentHub Message", text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Delete a single message by id.
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
            _uiState.update { state ->
                state.copy(messages = state.messages.filter { it.id != messageId })
            }
        }
    }

    /**
     * Refresh sessions list from repository.
     */
    fun refreshSessions() {
        viewModelScope.launch {
            // Force re-read from Room to trigger the Flow emission
            val sessions = repository.getAllSessionsList()
            _uiState.update { it.copy(sessions = sessions) }
            delay(300) // Brief delay for pull-to-refresh indicator visibility
        }
    }

    // ── Session swipe navigation ──

    fun switchToNextSession() {
        val sessions = _uiState.value.sessions
        val currentId = _uiState.value.currentSessionId ?: return
        val currentIndex = sessions.indexOfFirst { it.id == currentId }
        if (currentIndex >= 0 && currentIndex < sessions.size - 1) {
            switchToSession(sessions[currentIndex + 1].id)
        }
    }

    fun switchToPreviousSession() {
        val sessions = _uiState.value.sessions
        val currentId = _uiState.value.currentSessionId ?: return
        val currentIndex = sessions.indexOfFirst { it.id == currentId }
        if (currentIndex > 0) {
            switchToSession(sessions[currentIndex - 1].id)
        }
    }

    // ── Message reactions ──

    private val reactionEmojis = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDD25", "\uD83C\uDF89", "\uD83D\uDC40")

    fun toggleMessageReaction(messageId: String) {
        viewModelScope.launch {
            val currentMessage = _uiState.value.messages.find { it.id == messageId } ?: return@launch
            val currentReaction = currentMessage.reaction
            val nextReaction = if (currentReaction.isEmpty()) {
                reactionEmojis[0]
            } else {
                val idx = reactionEmojis.indexOf(currentReaction)
                if (idx >= 0 && idx < reactionEmojis.size - 1) {
                    reactionEmojis[idx + 1]
                } else {
                    "" // cycle back to no reaction
                }
            }
            repository.updateReaction(messageId, nextReaction)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map {
                        if (it.id == messageId) it.copy(reaction = nextReaction) else it
                    }
                )
            }
        }
    }

    // ── Command execution ──

    fun executeCommand(command: String): Boolean {
        when (command) {
            "/clear" -> {
                clearMessages()
                return true
            }
            "/new" -> {
                createNewSession()
                return true
            }
            "/search" -> {
                openSearch()
                return true
            }
            "/reconnect" -> {
                val config = _uiState.value.agentConfig
                if (config.serverUrl.isNotBlank()) {
                    viewModelScope.launch { connectWith(config) }
                    _uiState.update { it.copy(isConnecting = true) }
                }
                return true
            }
            "/model" -> {
                // Cycle to next agent config
                val configs = _uiState.value.agentConfigs
                if (configs.size > 1) {
                    val currentId = _uiState.value.agentConfig.id
                    val currentIdx = configs.indexOfFirst { it.id == currentId }
                    val nextIdx = (currentIdx + 1) % configs.size
                    val nextConfig = configs[nextIdx]
                    viewModelScope.launch {
                        connectWith(nextConfig)
                        _uiState.update { it.copy(agentConfig = nextConfig, isConnecting = true) }
                    }
                }
                return true
            }
            "/export" -> {
                // Export handled by SettingsViewModel; signal via activity log
                viewModelScope.launch {
                    repository.logActivity("command", "Export requested via /export command")
                }
                return true
            }
            "/help" -> {
                val helpText = "Available commands:\n" +
                    "/clear — Clear current chat\n" +
                    "/new — Create new session\n" +
                    "/search — Search messages\n" +
                    "/reconnect — Reconnect to agent\n" +
                    "/model — Switch to next agent\n" +
                    "/export — Export chat history\n" +
                    "/help — Show this help"
                viewModelScope.launch {
                    val sessionId = _uiState.value.currentSessionId
                        ?: repository.createSession("Help").let { session ->
                            _currentSessionId.value = session.id
                            _uiState.update { it.copy(currentSessionId = session.id) }
                            session.id
                        }
                    repository.sendMessage(sessionId, helpText, MessageRole.Assistant)
                }
                return true
            }
            "/compare" -> {
                _uiState.update { it.copy(comparePending = true) }
                return true
            }
            else -> return false
        }
    }

    // ── Search ──

    fun openSearch() {
        _uiState.update { it.copy(isSearchActive = true, searchQuery = "", searchResults = emptyList()) }
    }

    fun closeSearch() {
        _uiState.update { it.copy(isSearchActive = false, searchQuery = "", searchResults = emptyList()) }
    }

    fun searchMessages(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            val results = repository.searchMessages(query)
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    // ── Share Intent ──

    fun handleSharedText(text: String) {
        viewModelScope.launch {
            val sessionId = _uiState.value.currentSessionId
                ?: repository.createSession("Shared Content").let { session ->
                    _currentSessionId.value = session.id
                    _uiState.update { it.copy(currentSessionId = session.id) }
                    session.id
                }
            val userMsg = repository.sendMessage(sessionId, text, MessageRole.User)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + Message(
                        id = userMsg.id,
                        sessionId = userMsg.sessionId,
                        role = MessageRole.User,
                        content = text,
                        status = MessageStatus.Sent
                    ),
                    isStreaming = true
                )
            }
            if (_uiState.value.connectionState.isConnected) {
                _transport.value?.sendMessage(sessionId, text)
            } else {
                simulateResponse()
            }
        }
    }

    /**
     * Sprint 8: 处理来自 [WidgetInputActivity] 的快捷输入消息。
     *
     * 行为与 [handleSharedText] 一致：创建/复用 session、持久化 user 消息、
     * 通过 transport 发送或离线模拟回复。完成后刷新 Widget 以显示最新消息
     * （对应需求"Widget 更新为 sent 状态"）。
     */
    fun handleWidgetMessage(text: String) {
        viewModelScope.launch {
            val sessionId = _uiState.value.currentSessionId
                ?: repository.createSession("Widget Message").let { session ->
                    _currentSessionId.value = session.id
                    _uiState.update { it.copy(currentSessionId = session.id) }
                    session.id
                }
            val userMsg = repository.sendMessage(sessionId, text, MessageRole.User)
            repository.logActivity("widget", "Message sent from Widget", text.take(80))
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + Message(
                        id = userMsg.id,
                        sessionId = userMsg.sessionId,
                        role = MessageRole.User,
                        content = text,
                        status = MessageStatus.Sent
                    ),
                    isStreaming = true
                )
            }
            if (_uiState.value.connectionState.isConnected) {
                _transport.value?.sendMessage(sessionId, text)
            } else {
                simulateResponse()
            }
            // 刷新所有 Widget 实例，使其显示最新发送的消息
            AgentHubWidget.updateAll(getApplication())
        }
    }

    // ── Voice Input ──

    fun initVoiceInput(context: Context) {
        if (voiceInputManager == null) {
            voiceInputManager = VoiceInputManager(context)
        }
    }

    fun toggleVoiceInput() {
        val manager = voiceInputManager ?: return
        if (_uiState.value.isVoiceListening) {
            manager.stopListening()
            _uiState.update { it.copy(isVoiceListening = false) }
        } else {
            manager.startListening()
            _uiState.update { it.copy(isVoiceListening = true) }
        }
    }

    fun stopVoiceInput() {
        voiceInputManager?.stopListening()
        _uiState.update { it.copy(isVoiceListening = false) }
    }

    fun getVoiceInputManager(): VoiceInputManager? = voiceInputManager

    // ── Attachment ──

    fun setPendingAttachment(context: Context, uri: android.net.Uri, isImage: Boolean) {
        viewModelScope.launch {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    // Warn if attachment is very large
                    if (bytes.size > 10 * 1024 * 1024) { // 10MB
                        _uiState.update { it.copy(errorMessage = "Attachment too large (>10MB)") }
                        return@launch
                    }
                    val finalBytes = if (isImage && bytes.size > 1_000_000) {
                        try {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val maxDim = 720
                                val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
                                if (scale < 1f) {
                                    val scaled = Bitmap.createScaledBitmap(
                                        bitmap,
                                        (bitmap.width * scale).toInt(),
                                        (bitmap.height * scale).toInt(),
                                        true
                                    )
                                    val out = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                    if (scaled !== bitmap) scaled.recycle()
                                    bitmap.recycle()
                                    out.toByteArray()
                                } else {
                                    val out = ByteArrayOutputStream()
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                    bitmap.recycle()
                                    out.toByteArray()
                                }
                            } else bytes
                        } catch (_: Exception) { bytes }
                    } else bytes
                    val base64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
                    val name = uri.lastPathSegment ?: if (isImage) "image" else "file"
                    _uiState.update {
                        it.copy(
                            pendingAttachmentType = if (isImage) "image" else "file",
                            pendingAttachmentData = base64,
                            pendingAttachmentName = name
                        )
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to load attachment") }
            }
        }
    }

    fun clearPendingAttachment() {
        _uiState.update {
            it.copy(
                pendingAttachmentType = null,
                pendingAttachmentData = null,
                pendingAttachmentName = null
            )
        }
    }

    // ── Marketplace ──

    fun installMarketplaceAgent(config: AgentConfig) {
        viewModelScope.launch {
            repository.saveConfig(config)
            repository.logActivity("marketplace", "Agent installed", config.name)
        }
    }

    // ── Voice Chat Mode (full conversation) ──

    fun initVoiceChatMode(context: Context) {
        if (voiceChatManager == null) {
            voiceChatManager = VoiceChatManager(context).apply {
                onSpeechResult = { text ->
                    _uiState.update { it.copy(voiceChatLastUserText = text) }
                    // Send the recognized text as a message
                    _uiState.update { it.copy(inputText = text) }
                    sendMessage()
                }
            }
        }
    }

    fun enterVoiceChatMode() {
        val manager = voiceChatManager ?: return
        manager.startVoiceMode()
        _uiState.update { it.copy(isVoiceChatMode = true) }
    }

    fun exitVoiceChatMode() {
        val manager = voiceChatManager ?: return
        manager.stopVoiceMode()
        _uiState.update { it.copy(isVoiceChatMode = false, voiceChatLastUserText = "", voiceChatLastAgentText = "") }
    }

    fun getVoiceChatManager(): VoiceChatManager? = voiceChatManager

    fun speakAgentResponse(text: String) {
        voiceChatManager?.speak(text)
        _uiState.update { it.copy(voiceChatLastAgentText = text) }
    }

    // ── Local Model ──

    fun discoverLocalModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringLocalModels = true) }
            val models = localModelManager.discoverModels()
            _uiState.update {
                it.copy(
                    localModels = models,
                    isDiscoveringLocalModels = false,
                    localModelError = if (models.isEmpty()) "No local models found" else null
                )
            }
        }
    }

    fun discoverLocalEndpoint(endpoint: String, provider: LocalModelManager.LocalProvider) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscoveringLocalModels = true) }
            val models = localModelManager.discoverFromEndpoint(endpoint, provider)
            _uiState.update {
                it.copy(
                    localModels = localModelManager.state.value.models,
                    isDiscoveringLocalModels = false
                )
            }
        }
    }

    fun connectLocalModel(model: LocalModelManager.LocalModel) {
        viewModelScope.launch {
            val config = AgentConfig(
                id = java.util.UUID.randomUUID().toString(),
                name = model.name,
                type = AgentType.LocalModel,
                serverUrl = model.endpoint,
                model = model.id
            )
            repository.saveConfig(config)
            repository.logActivity("local_model", "Connected to local model", model.name)
            _uiState.update { state ->
                state.copy(
                    showWizard = false,
                    agentConfig = config
                )
            }
        }
    }

    fun clearLocalModelError() {
        _uiState.update { it.copy(localModelError = null) }
        localModelManager.clearError()
    }

    fun resetComparePending() {
        _uiState.update { it.copy(comparePending = false) }
    }

    override fun onCleared() {
        super.onCleared()
        // Sprint 8: 注销 Widget 快捷输入广播接收器，避免内存泄漏
        try {
            getApplication<Application>().unregisterReceiver(widgetMessageReceiver)
        } catch (_: Exception) {
            // 接收器可能因异常路径未注册成功，忽略注销失败
        }
        _transport.value?.disconnect()
        voiceInputManager?.destroy()
        voiceChatManager?.destroy()
    }
}
