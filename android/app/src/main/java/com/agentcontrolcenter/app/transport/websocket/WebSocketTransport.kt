package com.agentcontrolcenter.app.transport.websocket

import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.data.model.MessageRole
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
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.agentcontrolcenter.app.core.security.CryptoManager
import com.agentcontrolcenter.app.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.app.transport.protocol.AgentEvent
import com.agentcontrolcenter.app.transport.protocol.AgentTransport

/**
 * WebSocket 传输层，对应 Hermes / OpenClaw / OpenCode 等基于
 * `ws://host/ws` 的 Agent 服务。保留原有的鉴权帧与自动重连逻辑。
 *
 * 多轮对话说明：
 *  与 HTTP 传输不同，WebSocket 服务端（Hermes / OpenClaw / OpenCode 等）通常
 *  通过 `sessionId` 在服务端维护完整的会话状态与历史。客户端在每条消息帧中
 *  携带正确的 `sessionId`（见 [sendMessage]），服务端即可据此关联同一会话的
 *  上下文，因此多轮对话在协议层面无需客户端额外转发历史。
 *
 *  本类额外维护一份 [localMessageCache]（按 `sessionId` 分组），仅用于客户端
 *  侧展示与调试，不影响服务端会话状态。[clearHistory] / [clearAllHistory]
 *  只会清空这份本地缓存；如需真正重置服务端会话，应使用一个新的 `sessionId`。
 */
class WebSocketTransport(
    private val gson: Gson = Gson()
) : AgentTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = Channel<AgentEvent>(Channel.BUFFERED)
    override val events: Flow<AgentEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(AgentConnectionState())
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    /**
     * Long-lived HttpClient. Intentionally NOT closed in [disconnect] so the
     * transport can be reconnected repeatedly — only the active WebSocket
     * session is torn down on disconnect. Closing the client would render the
     * transport permanently unusable because [connect] does not recreate it.
     */
    private val client = HttpClient {
        install(WebSockets)
    }

    /**
     * The active WebSocket session, guarded by [sessionMutex] to avoid races
     * between concurrent [connect] / [sendMessage] / [disconnect] calls
     * (previously a plain `var` accessed from multiple coroutines).
     */
    private var session: WebSocketSession? = null
    private val sessionMutex = Mutex()

    private var connectJob: kotlinx.coroutines.Job? = null

    /** 当非空且非空字符串时，对消息内容做 E2E 加解密（仅对等模式生效）。 */
    private var e2eKey: String? = null

    /**
     * 本地消息缓存：按 `sessionId` 分组保存已收发的消息，仅用于客户端展示与
     * 调试。WebSocket 服务端会独立维护会话状态，此缓存不是多轮对话的来源。
     *
     * 访问受 [cacheLock] 保护，避免 [sendMessage] / [handleMessage] /
     * [clearHistory] / [clearAllHistory] 并发修改导致的竞态。
     */
    private val localMessageCache: MutableMap<String, MutableList<CachedMessage>> = mutableMapOf()
    private val cacheLock = Any()

    /** 缓存条目：仅记录角色与（解密后的）明文内容，用于本地展示。 */
    private data class CachedMessage(val role: MessageRole, val content: String)

    override fun connect(config: AgentConfig, e2eKey: String?) {
        this.e2eKey = e2eKey?.takeIf { it.isNotBlank() }
        // 取消之前的连接尝试
        connectJob?.cancel()
        connectJob = scope.launch {
            _connectionState.value = _connectionState.value.copy(
                serverUrl = config.serverUrl,
                agentType = config.type
            )
            // Swap-and-close any existing session under the mutex BEFORE starting
            // the new connection. Doing the clear in the same coroutine as
            // connectLoop (rather than a separate launch) guarantees the clear
            // runs before the new session is installed, so we never clobber the
            // new session. The close happens after releasing the lock to avoid
            // holding the mutex during IO.
            val old = sessionMutex.withLock {
                val cur = session
                session = null
                cur
            }
            old?.let { try { it.close() } catch (_: Exception) {} }
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
        // Exponential backoff for reconnect: start at 1s, double each retry,
        // cap at 30s. Previously a fixed 5s delay (repeat(50) { delay(100) }).
        var retryDelay = 1000L

        while (currentCoroutineContext().isActive && retryCount < maxRetries) {
            // Track the session we install this iteration so the `finally`
            // block only clears our own session and doesn't clobber one
            // installed by a newer [connect] call.
            var currentSession: WebSocketSession? = null
            try {
                _events.send(AgentEvent.Reconnecting)
                val startTime = System.currentTimeMillis()

                client.webSocket(wsUrl) {
                    currentSession = this
                    sessionMutex.withLock { session = this }
                    if (apiKey.isNotBlank()) {
                        // Build the auth frame via Gson to avoid JSON injection
                        // through a malformed apiKey (e.g. one containing `"` or
                        // `\`), which would break the frame or smuggle extra keys.
                        val authFrame = JsonObject().apply {
                            addProperty("type", "auth")
                            addProperty("key", apiKey)
                        }
                        send(Frame.Text(gson.toJson(authFrame)))
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
                    // Exponential backoff (1s, 2s, 4s, ... capped at 30s).
                    // Sleep in 100ms slices so coroutine cancellation stays
                    // responsive (matches the old repeat()/delay() pattern).
                    var remaining = retryDelay
                    while (remaining > 0 && currentCoroutineContext().isActive) {
                        delay(minOf(remaining, 100L))
                        remaining -= 100L
                    }
                    retryDelay = (retryDelay * 2).coerceAtMost(30000L)
                }
            } finally {
                // Only clear our own session; don't clobber one set by a
                // newer connect() that has already installed its own session.
                val mine = currentSession
                sessionMutex.withLock {
                    if (session === mine) session = null
                }
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
                    // 仅缓存完整的助手回复（非增量帧）；增量帧由上层 UI 自行拼接，
                    // 此处不逐帧入库，避免缓存中出现大量碎片条目。仅当帧携带
                    // sessionId 时才能归入对应会话，否则跳过缓存。
                    if (!delta && content.isNotEmpty()) {
                        val msgSessionId = json.get("sessionId")?.asString
                        if (!msgSessionId.isNullOrBlank()) {
                            cacheMessage(msgSessionId, MessageRole.Assistant, content)
                        }
                    }
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
        // 多轮对话：将 sessionId 写入消息帧。WebSocket 服务端据此关联同一会话的
        // 上下文历史，客户端无需（也不应）在帧中重放完整对话历史。
        val message = JsonObject().apply {
            addProperty("type", "message")
            addProperty("sessionId", sessionId)
            addProperty("content", outContent)
            addProperty("role", MessageRole.User.name)
        }
        // 先把用户消息写入本地缓存（保存解密前的明文，便于展示），
        // 再发送到服务端。即使发送失败，本地缓存也反映了用户的输入意图。
        if (sessionId.isNotBlank() && content.isNotEmpty()) {
            cacheMessage(sessionId, MessageRole.User, content)
        }
        try {
            // Hold the mutex for the duration of the send so a concurrent
            // disconnect()/connect() can't close the session out from under us.
            sessionMutex.withLock {
                session?.send(Frame.Text(gson.toJson(message)))
            }
        } catch (e: Exception) {
            // Surface the failure as an error event instead of silently
            // swallowing it (the previous `catch (_: Exception) {}` hid
            // real send failures from the UI layer).
            if (e is CancellationException) throw e
            _events.send(
                AgentEvent.Error("Failed to send message: ${e.message ?: e.javaClass.simpleName}")
            )
        }
    }

    /**
     * 将一条消息追加到 [localMessageCache] 中对应 [sessionId] 的列表。
     * 线程安全：通过 [cacheLock] 串行化，避免与 [clearHistory] / [clearAllHistory]
     * 并发执行时的竞态。
     */
    private fun cacheMessage(sessionId: String, role: MessageRole, content: String) {
        synchronized(cacheLock) {
            localMessageCache.getOrPut(sessionId) { mutableListOf() }
                .add(CachedMessage(role, content))
        }
    }

    /**
     * 清空指定 [sessionId] 的本地消息缓存。
     *
     * 注意：WebSocket 服务端会独立维护会话状态，此方法只影响客户端侧的展示缓存，
     * 不会重置服务端的会话历史。如需开启一段全新对话，应使用一个新的 `sessionId`。
     */
    override suspend fun clearHistory(sessionId: String) {
        synchronized(cacheLock) {
            localMessageCache.remove(sessionId)
        }
    }

    /**
     * 清空所有会话的本地消息缓存。
     *
     * 同 [clearHistory]，仅作用于客户端侧缓存，不影响服务端会话状态。
     */
    override suspend fun clearAllHistory() {
        synchronized(cacheLock) {
            localMessageCache.clear()
        }
    }

    override fun disconnect() {
        scope.launch {
            val old = sessionMutex.withLock {
                val cur = session
                session = null
                cur
            }
            try { old?.close() } catch (_: Exception) { }
            // Note: client is intentionally NOT closed here — it is a long-lived
            // instance shared across connect/disconnect cycles. Closing it would
            // render the transport permanently unusable because connect() does
            // not recreate it.
            _connectionState.value = AgentConnectionState()
            _events.send(AgentEvent.Disconnected())
        }
    }

    override fun shutdown() {
        // 1. 取消协程作用域：终止 connectJob、connectLoop 中的 webSocket 会话
        //    以及任何活跃的 incoming frame 收集协程。取消会传播到子协程，
        //    WebSocketSession 也会被相应取消。
        scope.cancel()
        // 2. 关闭事件 Channel：使所有 events 收集者收到关闭信号并正常退出。
        _events.close()
        // 3. 关闭 HttpClient：释放底层连接池与线程资源。
        client.close()
        // 4. 重置连接状态。
        _connectionState.value = AgentConnectionState()
    }
}
