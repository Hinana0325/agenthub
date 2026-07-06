package com.agenthub.app.provider

import com.agenthub.app.data.model.AgentType
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class AgentEvent {
    data class Connected(val serverUrl: String, val agentType: AgentType) : AgentEvent()
    data class Disconnected(val reason: String = "") : AgentEvent()
    data class MessageReceived(val content: String, val isDelta: Boolean = false) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data object Reconnecting : AgentEvent()
}

data class AgentConnectionState(
    val isConnected: Boolean = false,
    val serverUrl: String = "",
    val agentType: AgentType = AgentType.Hermes,
    val latency: Long = 0
)

class AgentProvider(
    private val gson: Gson = Gson()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = Channel<AgentEvent>(Channel.BUFFERED)
    val events: Flow<AgentEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(AgentConnectionState())
    val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    private val client = HttpClient {
        install(WebSockets)
    }
    private var session: WebSocketSession? = null
    private var connectJob: kotlinx.coroutines.Job? = null

    fun connect(serverUrl: String, apiKey: String, agentType: AgentType) {
        // 取消之前的连接尝试
        connectJob?.cancel()
        session?.let { scope.launch { try { it.close() } catch (_: Exception) {} } }
        session = null

        connectJob = scope.launch {
            _connectionState.value = _connectionState.value.copy(
                serverUrl = serverUrl,
                agentType = agentType
            )
            connectLoop(serverUrl, apiKey)
        }
    }

    private suspend fun connectLoop(serverUrl: String, apiKey: String) {
        val wsUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + "/ws"

        var retryCount = 0
        val maxRetries = 3

        while (kotlinx.coroutines.currentCoroutineContext().isActive && retryCount < maxRetries) {
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
                    repeat(50) { if (kotlinx.coroutines.currentCoroutineContext().isActive) kotlinx.coroutines.delay(100) }
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
                    val content = json.get("content")?.asString ?: ""
                    val delta = json.get("delta")?.asBoolean ?: false
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

    suspend fun sendMessage(sessionId: String, content: String) {
        val message = JsonObject().apply {
            addProperty("type", "message")
            addProperty("sessionId", sessionId)
            addProperty("content", content)
            addProperty("role", MessageRole.User.name)
        }
        try {
            session?.send(Frame.Text(gson.toJson(message)))
        } catch (_: Exception) { }
    }

    fun disconnect() {
        scope.launch {
            try { session?.close() } catch (_: Exception) { }
            session = null
            client.close()
            _connectionState.value = AgentConnectionState()
        }
    }
}
