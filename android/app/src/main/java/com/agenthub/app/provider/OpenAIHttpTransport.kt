package com.agenthub.app.provider

import com.agenthub.app.data.model.AgentConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.preparePost
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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

    override fun connect(config: AgentConfig, e2eKey: String?) {
        // HTTP 传输：对端是 LLM 服务，需要明文请求体，E2E 不适用（忽略 e2eKey）。
        currentConfig = config
        _connectionState.value = _connectionState.value.copy(
            isConnected = true,
            serverUrl = config.serverUrl,
            agentType = config.type
        )
        eventScope.launch {
            _events.send(AgentEvent.Connected(config.serverUrl, config.type))
        }
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
        val config = currentConfig ?: run {
            _events.send(AgentEvent.Error("Not connected"))
            return
        }
        val url = config.serverUrl.trimEnd('/') + "/v1/chat/completions"
        val requestBody = mapOf(
            "model" to config.model,
            "messages" to listOf(mapOf("role" to "user", "content" to content)),
            "stream" to true,
            "temperature" to config.temperature,
            "max_tokens" to config.maxTokens
        )
        try {
            client.preparePost(url) {
                header("Authorization", "Bearer ${config.apiKey}")
                setBody(gson.toJson(requestBody)) {
                    contentType(ContentType.Application.Json)
                }
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    val errBody = response.bodyAsText().take(2000)
                    _events.send(AgentEvent.Error("HTTP ${response.status.value}: $errBody"))
                    _connectionState.value = _connectionState.value.copy(isConnected = false)
                    return@execute
                }
                val body = response.bodyAsText()
                parseBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(AgentEvent.Error(e.message ?: "Request failed"))
        }
    }

    /**
     * 解析响应体：若含 SSE `data:` 行则按增量发射；否则当作单个 JSON 完成包处理。
     */
    private suspend fun parseBody(body: String) {
        val lines = body.lineSequence()
        var sawData = false
        val singleJson = StringBuilder()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("data:")) {
                sawData = true
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                emitDelta(data)
            } else {
                singleJson.appendLine(rawLine)
            }
        }
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
                _events.send(AgentEvent.MessageReceived(text, isDelta = true))
            }
        } catch (_: Exception) { }
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
                _events.send(AgentEvent.MessageReceived(text, isDelta = false))
            }
        } catch (_: Exception) { }
    }

    override fun disconnect() {
        try { client.close() } catch (_: Exception) { }
        _connectionState.value = AgentConnectionState()
        eventScope.launch {
            _events.send(AgentEvent.Disconnected())
        }
    }
}
