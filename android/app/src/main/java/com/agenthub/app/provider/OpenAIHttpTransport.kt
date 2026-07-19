package com.agenthub.app.provider

import android.util.Log
import com.agenthub.app.data.model.AgentConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
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
    private val gson: Gson = Gson()
) : AgentTransport {

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = Channel<AgentEvent>(Channel.BUFFERED)
    override val events: Flow<AgentEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(AgentConnectionState())
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    private val client = HttpClient(OkHttp) {
        install(Logging)
    }

    private var currentConfig: AgentConfig? = null

    /**
     * 内存中的对话消息结构，仅含 role 与 content，用于拼装 OpenAI Chat
     * Completions 请求的 `messages` 数组。不复用 [com.agenthub.app.data.model.Message]，
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
     * 这是单实例字段，依赖「同一 transport 实例上 sendMessage 不会被并发调用」
     * 的约定（与 ChatViewModel 单协程发送模式一致）。历史字段的并发安全由
     * [historyMutex] 保证；累加器本身不跨请求共享状态（每次请求开头会清空）。
     */
    private val responseAccumulator = StringBuilder()

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
                _events.send(AgentEvent.Error("无法连接到 ${config.serverUrl}（请确认服务已启动、地址与端口正确）"))
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
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
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
        try {
            client.preparePost(url) {
                header("Authorization", "Bearer ${config.apiKey}")
                header("Content-Type", "application/json")
                setBody(gson.toJson(requestBody))
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    val errBody = response.bodyAsText().take(2000)
                    _events.send(AgentEvent.Error("HTTP ${response.status.value}: $errBody"))
                    _connectionState.value = _connectionState.value.copy(isConnected = false)
                    return@execute
                }
                // Stream the SSE response line-by-line as it arrives instead of
                // buffering the whole body with bodyAsText() — that would defeat
                // the point of streaming and delay first-token latency.
                parseStream(response.bodyAsChannel())
            }
            // 3. 流结束后，把累加的助手回复写入历史。此调用覆盖三种流结束情况：
            //    - SSE 流中收到 `data: [DONE]`（flushData 返回 false，循环退出）
            //    - 流自然结束（channel 关闭，while 退出）
            //    - 非 SSE 整包回退（emitFull 路径）
            //    若累加器为空（HTTP 非 200 / 请求失败），不会写入历史。
            saveAssistantResponse(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(AgentEvent.Error(e.message ?: "Request failed"))
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
            Log.w(TAG, "Failed to parse full JSON: ${e.message}; json=$jsonText")
        }
    }

    override fun disconnect() {
        try { client.close() } catch (_: Exception) { }
        _connectionState.value = AgentConnectionState()
        eventScope.launch {
            // 传输断开时清空全部对话历史，避免下次重连后把过期的上下文
            // 发给对端（尤其是切换到不同 server / apiKey 的场景）。
            clearAllHistory()
            _events.send(AgentEvent.Disconnected())
        }
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
