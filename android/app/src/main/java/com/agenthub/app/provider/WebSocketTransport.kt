package com.agenthub.app.provider

import com.agenthub.app.data.model.AgentConfig
import com.agenthub.app.data.model.MessageRole
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.agenthub.app.util.CryptoManager

/**
 * WebSocket 传输层，对应 Hermes / OpenClaw / OpenCode 等基于
 * `ws://host/ws` 的 Agent 服务。保留原有的鉴权帧与自动重连逻辑。
 */
class WebSocketTransport(
    private val gson: Gson = Gson()
) : AgentTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = Channel<AgentEvent>(Channel.BUFFERED)
    override val events: Flow<AgentEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(AgentConnectionState())
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    private val client = HttpClient {
        install(WebSockets)
    }
    private var session: WebSocketSession? = null
    private var connectJob: kotlinx.coroutines.Job? = null

    /** 当非空且非空字符串时，对消息内容做 E2E 加解密（仅对等模式生效）。 */
    private var e2eKey: String? = null

    override fun connect(config: AgentConfig, e2eKey: String?) {
        this.e2eKey = e2eKey?.takeIf { it.isNotBlank() }
        // 取消之前的连接尝试
        connectJob?.cancel()
        session?.let { scope.launch { try { it.close() } catch (_: Exception) {} } }
        session = null

        connectJob = scope.launch {
            _connectionState.value = _connectionState.value.copy(
                serverUrl = config.serverUrl,
                agentType = config.type
            )
            connectLoop(config.serverUrl, config.apiKey)
        }
    }

    private suspend fun connectLoop(serverUrl: String, apiKey: String) {
        val wsUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + "/ws"

        var retryCount = 0
        val maxRetries = 3

        while (currentCoroutineContext().isActive && retryCount < maxRetries) {
            try {
                _events.send(AgentEvent.Reconnecting)
                val startTime = System.currentTimeMillis()

                client.webSocket(wsUrl) {
                    session = this
                    if (apiKey.isNotBlank()) {
                        // Mask key in logs, send as-is over wire
                        send(Frame.Text("{\"type\":\"auth\",\"key\":\"$apiKey\"}"))
                    }
                    val latency = System.currentTimeMillis() - startTime
                    _connectionState.value = _connectionState.value.copy(
                        isConnected = true,
                        latency = latency
                    )
                    _events.send(AgentEvent.Connected(serverUrl, _connectionState.value.agentType))

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            handleMessage(frame.readText())
                        }
                    }
                }

                _events.send(AgentEvent.Disconnected())
                _connectionState.value = _connectionState.value.copy(isConnected = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                retryCount++
                _events.send(AgentEvent.Error(
                    if (retryCount >= maxRetries) "Connection failed after $maxRetries attempts: ${e.message}"
                    else "Connection failed (retry $retryCount/$maxRetries): ${e.message}"
                ))
                _connectionState.value = _connectionState.value.copy(isConnected = false)
                if (retryCount < maxRetries) {
                    // Use yield-based loop so cancellation is respected
                    repeat(50) { if (currentCoroutineContext().isActive) delay(100) }
                }
            } finally {
                session = null
            }
        }
    }

    private suspend fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString
            when (type) {
                "message", "response" -> {
                    val raw = json.get("content")?.asString ?: ""
                    val delta = json.get("delta")?.asBoolean ?: false
                    // E2E：尝试解密；失败则原样展示（兼容非 E2E 对端）
                    val content = e2eKey?.let { CryptoManager.decrypt(raw, it) } ?: raw
                    _events.send(AgentEvent.MessageReceived(content, delta))
                }
                "error" -> {
                    val msg = json.get("message")?.asString ?: "Unknown error"
                    _events.send(AgentEvent.Error(msg))
                }
                "ping" -> { }
            }
        } catch (_: Exception) {
            _events.send(AgentEvent.MessageReceived(text))
        }
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
        // E2E：发送前加密正文（对等模式；对端需持有相同密钥）
        val outContent = e2eKey?.let { CryptoManager.encrypt(content, it) } ?: content
        val message = JsonObject().apply {
            addProperty("type", "message")
            addProperty("sessionId", sessionId)
            addProperty("content", outContent)
            addProperty("role", MessageRole.User.name)
        }
        try {
            session?.send(Frame.Text(gson.toJson(message)))
        } catch (_: Exception) { }
    }

    override fun disconnect() {
        scope.launch {
            try { session?.close() } catch (_: Exception) { }
            session = null
            client.close()
            _connectionState.value = AgentConnectionState()
            _events.send(AgentEvent.Disconnected())
        }
    }
}
