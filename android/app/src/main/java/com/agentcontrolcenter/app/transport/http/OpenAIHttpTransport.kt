package com.agentcontrolcenter.app.transport.http

import android.content.Context
import android.util.Log
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.preparePost
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import com.agentcontrolcenter.app.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.app.transport.protocol.AgentEvent
import com.agentcontrolcenter.app.transport.protocol.AgentTransport

/**
 * OpenAI 兼容 HTTP + SSE 传输层。
 *
 * 覆盖 OpenAI / OpenRouter / Ollama / LM Studio / vLLM / Xiaomi MiMo —— 它们都暴露
 * OpenAI 格式的 `/v1/chat/completions` 端点。
 *
 * - 优先以 `stream:true` 发送，解析 SSE `data:` 行得到增量（delta）。
 * - 对于不支持 SSE 的端点（返回单个 JSON 完成包），自动回退为整段解析。
 * - HTTP 传输无持久连接：`connect()` 仅记录配置并标记就绪；`disconnect()` 才置为离线。
 */
class OpenAIHttpTransport(
    private val context: Context,
    private val gson: Gson = Gson()
) : AgentTransport {

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = Channel<AgentEvent>(Channel.BUFFERED)
    override val events: Flow<AgentEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(AgentConnectionState())
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    private val client = HttpClient(OkHttp) {
        install(Logging)
        // Phase 1.1: 为 HTTP 请求配置超时，防止网络卡顿时 sendMessage 无限挂起
        // 导致 UI isStreaming 永远为 true。
        //   - connectTimeoutMillis: TCP 连接建立超时，10s 足够覆盖慢网络。
        //   - requestTimeoutMillis: 整个请求超时（含 SSE 流），设为 120s 给
        //     LLM 长回复留足空间。流式场景下首个 token 可能延迟数十秒。
        //   - socketTimeoutMillis: 两次数据包之间的最大间隔，30s。SSE 流中
        //     token 之间通常间隔很短，30s 足够检测到死连接。
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 30_000
        }
    }

    private var currentConfig: AgentConfig? = null

    /**
     * 内存中的对话消息结构，仅含 role 与 content，用于拼装 OpenAI Chat
     * Completions 请求的 `messages` 数组。不复用 [com.agentcontrolcenter.app.data.model.Message]，
     * 因为后者带数据库 id、附件、状态等与本请求体无关的字段。
     */
    private data class ConversationMessage(val role: String, val content: String)

    /**
     * 按 sessionId 隔离的多轮对话历史。每条记录是一个 [ConversationMessage]。
     *
     * 通过 [historyMutex] 保护所有读写，避免并发 [sendMessage] / [clearHistory]
     * / [clearAllHistory] 之间的数据竞争。同一 sessionId 的历史在多次请求间累积，
     * 从而让模型获得上下文；跨 sessionId 自然隔离。
     */
    private val conversationHistory = mutableMapOf<String, MutableList<ConversationMessage>>()
    private val historyMutex = Mutex()

    /**
     * 当前请求的助手回复累加器。在 [sendMessage] 起始处清空，由 [emitDelta] /
     * [emitFull] 追加增量，[saveAssistantResponse] 在流结束时将其写入历史。
     *
     * Phase 1.5: 累加器访问由 [sendMutex] 串行化保护，不再依赖
     * 「sendMessage 不会被并发调用」的隐含约定。
     */
    private val responseAccumulator = StringBuilder()

    /**
     * Phase 1.5: 串行化 sendMessage 调用，防止并发请求撕裂 [responseAccumulator]。
     *
     * 此前依赖「同一 transport 实例上 sendMessage 不会被并发调用」的隐含约定，
     * 一旦上层引入并发发送（如 CompareViewModel 或未来的 TaskExecutor）会撕裂
     * 累加器。加锁后即使并发调用也能安全串行执行，代价是同一时刻只能有一个
     * 流式请求在途（对单 Agent 场景完全够用）。
     */
    private val sendMutex = Mutex()

    override fun connect(config: AgentConfig, e2eKey: String?) {
        // HTTP 传输：对端是 LLM 服务，需要明文请求体，E2E 不适用（忽略 e2eKey）。
        currentConfig = config
        eventScope.launch {
            // 连接自检：先探测 /v1/models 探活。仅 2xx 或常见的 "服务存在但
            // 需鉴权/路径不对" 状态码才视为可达；5xx 视为不可用。
            val reachable = probeEndpoint(config)
            if (reachable) {
                _connectionState.value = _connectionState.value.copy(
                    isConnected = true,
                    serverUrl = config.serverUrl,
                    agentType = config.type
                )
                _events.send(AgentEvent.Connected(config.serverUrl, config.type))
            } else {
                _connectionState.value = _connectionState.value.copy(isConnected = false)
                _events.send(AgentEvent.Error(context.getString(R.string.error_cannot_connect, config.serverUrl)))
            }
        }
    }

    /**
     * 探测端点是否可达：GET {base}/v1/models（base 已含 /v1 时则为 {base}/models）。
     *
     * Reachability 判定（修复：原实现把任何 HTTP 响应都视为可达，5xx 也算）：
     *  - 2xx：服务健康，端点存在。
     *  - 401/403：端点存在但需要鉴权（凭据正确后仍可用）。
     *  - 404：主机在线但探活路径不存在 —— chat-completions 端点仍可能可用
     *    （Ollama / LM Studio 等对 /v1/models 的实现各异）。
     *  - 5xx：服务端故障，当前不可用，返回 false。
     *  - 其他 4xx（如 400/405）：保守起见视为不可达。
     *  - 任何抛出的异常（UnknownHost / 连接拒绝 / 超时） -> false。
     */
    private suspend fun probeEndpoint(config: AgentConfig): Boolean {
        val base = config.serverUrl.trimEnd('/')
        val probeUrl = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        return try {
            val response = withTimeout(5000) {
                client.get(probeUrl) {
                    if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            val status = response.status.value
            status in 200..299 || status == 401 || status == 403 || status == 404
        } catch (e: CancellationException) {
            // Phase 1.6: 重抛 CancellationException，避免 shutdown() 取消 eventScope 后
            // probeEndpoint 吞掉取消信号，导致 connect() 继续执行并覆盖已重置的状态。
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
        // Phase 1.5: 串行化整个 sendMessage，防止并发请求撕裂 responseAccumulator。
        // 锁内包含历史读取、请求发送、流解析、历史写入的完整流程。
        sendMutex.withLock {
            val config = currentConfig ?: run {
                _events.send(AgentEvent.Error("Not connected"))
                return
            }

            // 1. 把本轮用户消息追加到历史，按滑动窗口裁剪，并原子地取一份快照用于
            //    构建请求体。整个「追加 + 裁剪 + 快照」在同一把锁内完成，避免
            //    并发 [clearHistory] 在两步之间插入导致 messages 数组为空。
            //    历史快照为空时（理论上的并发清空场景）退化为单条用户消息，
            //    与原有单轮行为完全一致（向后兼容）。
            val messagesPayload: List<Map<String, String>> = historyMutex.withLock {
                val history = conversationHistory.getOrPut(sessionId) { mutableListOf() }
                history.add(ConversationMessage("user", content))
                trimHistory(history)
                history.map { msg ->
                    mapOf("role" to msg.role, "content" to msg.content)
                }
            }

            // 2. 重置助手回复累加器，准备接收本轮 SSE 增量。
            responseAccumulator.setLength(0)

            val url = config.serverUrl.trimEnd('/') + "/v1/chat/completions"
            val requestBody = mapOf(
                "model" to config.model,
                // 多轮对话：messages 数组由本 session 的历史快照构成，包含此前
                // 的 user / assistant 轮次，让模型获得上下文。滑动窗口由
                // [trimHistory] 限制在 [MAX_HISTORY_MESSAGES] 条以内。
                "messages" to messagesPayload,
                "stream" to true,
                "temperature" to config.temperature,
                "max_tokens" to config.maxTokens
            )
            var streamSucceeded = false
            // HTTP 重试：对可重试错误（5xx / 网络异常 / 超时）进行指数退避重试。
            // 不可重试：4xx 客户端错误（含 401/403/400 等）、CancellationException。
            var attempt = 0
            val maxAttempts = 3
            var lastError: String? = null
            while (attempt < maxAttempts && !streamSucceeded) {
                attempt++
                try {
                    client.preparePost(url) {
                        header("Authorization", "Bearer ${config.apiKey}")
                        header("Content-Type", "application/json")
                        setBody(gson.toJson(requestBody))
                    }.execute { response ->
                        if (response.status != HttpStatusCode.OK) {
                            val errBody = response.bodyAsText().take(2000)
                            val code = response.status.value
                            // 4xx 客户端错误不重试（凭据/请求格式问题，重试无用）
                            if (code in 400..499) {
                                _events.send(AgentEvent.Error("HTTP $code: $errBody"))
                                _connectionState.value = _connectionState.value.copy(isConnected = false)
                                return@execute
                            }
                            // 5xx 服务端错误：可重试
                            if (attempt < maxAttempts) {
                                lastError = "HTTP $code (retry $attempt/$maxAttempts)"
                            } else {
                                _events.send(AgentEvent.Error("HTTP $code: $errBody"))
                                _connectionState.value = _connectionState.value.copy(isConnected = false)
                            }
                            return@execute
                        }
                        // Stream the SSE response line-by-line as it arrives instead of
                        // buffering the whole body with bodyAsText() — that would defeat
                        // the point of streaming and delay first-token latency.
                        parseStream(response.bodyAsChannel())
                        streamSucceeded = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 网络异常 / 超时：可重试
                    if (attempt < maxAttempts) {
                        lastError = "${e.message ?: e.javaClass.simpleName} (retry $attempt/$maxAttempts)"
                    } else {
                        _events.send(AgentEvent.Error(e.message ?: "Request failed"))
                    }
                }
                // 指数退避：1s, 2s, 4s...（仅在还有下一次尝试时等待）
                if (!streamSucceeded && attempt < maxAttempts) {
                    val backoff = (1000L * (1 shl (attempt - 1))).coerceAtMost(8000L)
                    try {
                        kotlinx.coroutines.delay(backoff)
                    } catch (_: CancellationException) {
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }
            // 3. 流结束后，把累加的助手回复写入历史。此调用覆盖三种流结束情况：
            //    - SSE 流中收到 `data: [DONE]`（flushData 返回 false，循环退出）
            //    - 流自然结束（channel 关闭，while 退出）
            //    - 非 SSE 整包回退（emitFull 路径）
            //    若累加器为空（HTTP 非 200 / 请求失败），不会写入历史。
            saveAssistantResponse(sessionId)
            // 4. 通知上层流式响应已结束，使其重置 isStreaming 状态。
            //    仅在流成功完成时发送（HTTP 非 200 已通过 Error 事件通知上层）。
            if (streamSucceeded) {
                _events.send(AgentEvent.StreamComplete)
            } else if (lastError != null) {
                // 所有重试均失败，发送最终错误
                _events.send(AgentEvent.Error("Request failed after $maxAttempts attempts: $lastError"))
            }
        }
    }

    /**
     * 把 [responseAccumulator] 中累加的助手回复写入指定 session 的历史，
     * 并清空累加器。空回复（如 HTTP 非 200、请求失败、无 content delta）
     * 不会被写入，避免污染历史。
     */
    private suspend fun saveAssistantResponse(sessionId: String) {
        val text = responseAccumulator.toString()
        responseAccumulator.setLength(0)
        if (text.isEmpty()) return
        historyMutex.withLock {
            val history = conversationHistory.getOrPut(sessionId) { mutableListOf() }
            history.add(ConversationMessage("assistant", text))
            trimHistory(history)
        }
    }

    /**
     * 滑动窗口裁剪：保留最近的 [MAX_HISTORY_MESSAGES] 条消息，从头部移除
     * 较旧的条目。必须在 [historyMutex] 内调用。
     */
    private fun trimHistory(messages: MutableList<ConversationMessage>) {
        val overflow = messages.size - MAX_HISTORY_MESSAGES
        if (overflow > 0) {
            repeat(overflow) { messages.removeAt(0) }
        }
    }

    /**
     * 清空指定 [sessionId] 的客户端侧对话历史。
     *
     * HTTP 传输是无状态的，多轮上下文完全由客户端维护；调用此方法后，
     * 后续对该 sessionId 的请求将不再携带此前的 user / assistant 轮次，
     * 相当于开启一段全新对话（但复用同一 sessionId）。
     */
    override suspend fun clearHistory(sessionId: String) {
        historyMutex.withLock {
            conversationHistory.remove(sessionId)
        }
    }

    /**
     * 清空所有 session 的客户端侧对话历史。
     *
     * 在 [disconnect] 时调用，避免切换到不同 server / apiKey 后复用过期上下文。
     * 同时清空可能残留的 [responseAccumulator]，避免下一次请求误把上一轮的
     * 片段写入新历史。
     */
    override suspend fun clearAllHistory() {
        historyMutex.withLock {
            conversationHistory.clear()
        }
        // 同时清空可能残留的累加器，避免下一次请求误把上一轮的片段写入新历史。
        responseAccumulator.setLength(0)
    }

    /**
     * Parse an SSE stream from [channel] line-by-line as it arrives.
     *
     * SSE rules implemented here (replaces the old `parseBody` that buffered the
     * whole response via `bodyAsText()`):
     *  - Lines are terminated by `\n` (with optional trailing `\r`, which we
     *    strip explicitly — do NOT use `trim()`, which would discard significant
     *    leading whitespace in the `data:` payload).
     *  - Consecutive `data:` lines accumulate into a single event, joined by
     *    `\n` (per the SSE spec). The event is dispatched when a blank line is
     *    seen (or at end of stream).
     *  - Lines starting with `:` are SSE comments and are ignored.
     *  - `data: [DONE]` terminates the stream.
     *  - Non-`data:` lines fall back to a single-JSON buffer for servers that
     *    ignore `stream:true` and return one JSON object.
     */
    private suspend fun parseStream(channel: ByteReadChannel) {
        val dataBuffer = StringBuilder()
        val singleJson = StringBuilder()
        var sawData = false

        /**
         * Dispatch the accumulated `data:` buffer.
         * Returns false if the buffer contained the `[DONE]` sentinel
         * (signal to stop reading the stream).
         */
        suspend fun flushData(): Boolean {
            if (dataBuffer.isEmpty()) return true
            val data = dataBuffer.toString()
            dataBuffer.setLength(0)
            if (data == "[DONE]") return false
            emitDelta(data)
            return true
        }

        var keepGoing = true
        while (keepGoing && !channel.isClosedForRead) {
            @Suppress("DEPRECATION") // readUTF8Line handles CR/LF/CRLF per SSE spec.
            val rawLine = channel.readUTF8Line() ?: break
            // SSE uses `\r\n` or `\n` line endings; strip a trailing `\r` only.
            // Do NOT trim() — the data field may legitimately start with spaces.
            val line = rawLine.removeSuffix("\r")

            if (line.isEmpty()) {
                // Blank line: dispatch the accumulated event and reset.
                keepGoing = flushData()
                continue
            }
            if (line.startsWith(":")) {
                // SSE comment / heartbeat; ignore.
                continue
            }
            if (line.startsWith("data:")) {
                sawData = true
                // Per SSE spec: strip exactly one leading space after "data:".
                var data = line.removePrefix("data:")
                if (data.startsWith(" ")) data = data.removePrefix(" ")
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(data)
            } else {
                // Non-SSE line (e.g. a plain JSON body from a non-streaming
                // server). Accumulate for fallback full-message parsing.
                singleJson.append(rawLine).append('\n')
            }
        }
        // Flush any trailing buffered event (unless we already hit [DONE]).
        if (keepGoing) flushData()
        if (!sawData && singleJson.isNotBlank()) {
            emitFull(singleJson.toString())
        }
    }

    private suspend fun emitDelta(data: String) {
        try {
            val json = gson.fromJson(data, JsonObject::class.java) ?: return
            val choices = json.getAsJsonArray("choices") ?: return
            if (choices.size() == 0) return
            val choice = choices[0].asJsonObject
            val delta = choice.getAsJsonObject("delta")
            val text = delta?.get("content")?.asString ?: ""
            if (text.isNotEmpty()) {
                // 累加增量文本，流结束后由 saveAssistantResponse 统一写入历史。
                responseAccumulator.append(text)
                _events.send(AgentEvent.MessageReceived(text, isDelta = true))
            }
        } catch (e: Exception) {
            // Don't break the stream; log and continue so subsequent deltas still emit.
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to parse SSE delta: ${e.message}; data=$data")
        }
    }

    private suspend fun emitFull(jsonText: String) {
        try {
            val json = gson.fromJson(jsonText, JsonObject::class.java) ?: return
            val choices = json.getAsJsonArray("choices") ?: return
            if (choices.size() == 0) return
            val choice = choices[0].asJsonObject
            val message = choice.getAsJsonObject("message")
            val text = message?.get("content")?.asString ?: ""
            if (text.isNotEmpty()) {
                // 非 SSE 整包回复：同样累加，统一由 saveAssistantResponse 写入历史。
                responseAccumulator.append(text)
                _events.send(AgentEvent.MessageReceived(text, isDelta = false))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to parse full JSON: ${e.message}; json=$jsonText")
        }
    }

    override fun disconnect() {
        // 注意：此处不调用 client.close()。client 是字段单例，connect() 不会重建它，
        // 一旦 close 之后再 connect()，probeEndpoint 会抛 IllegalStateException: HttpClient is closed。
        // 与 WebSocketTransport 的做法一致，disconnect 只清理状态与历史，client 的
        // 关闭留给 Transport 被销毁时（如进程退出 / shutdown hook）。
        _connectionState.value = AgentConnectionState()
        eventScope.launch {
            // 传输断开时清空全部对话历史，避免下次重连后把过期的上下文
            // 发给对端（尤其是切换到不同 server / apiKey 的场景）。
            clearAllHistory()
            _events.send(AgentEvent.Disconnected())
        }
    }

    override fun shutdown() {
        // 1. 取消协程作用域：终止所有正在进行的请求（connect 探活、sendMessage SSE 流）。
        //    取消会触发 CancellationException，sendMessage 的 catch 块会正确处理。
        eventScope.cancel()
        // 2. 关闭事件 Channel：使所有 events 收集者收到 Channel 关闭信号并正常退出。
        _events.close()
        // 3. 关闭 HttpClient：释放 OkHttp 连接池、线程池等底层资源。
        //    HttpClient 实现了 Closeable，close() 是同步调用。
        client.close()
        // 4. 重置连接状态。
        _connectionState.value = AgentConnectionState()
    }

    companion object {
        private const val TAG = "OpenAIHttpTransport"

        /**
         * 每个 session 最多保留的历史消息条数（含 user 与 assistant）。
         * 超出时按滑动窗口从头部裁剪，避免请求体无限增长导致 token 超限。
         */
        private const val MAX_HISTORY_MESSAGES = 20
    }
}
