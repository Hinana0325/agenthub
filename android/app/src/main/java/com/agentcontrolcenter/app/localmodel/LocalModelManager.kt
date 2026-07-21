package com.agentcontrolcenter.app.localmodel

import android.util.Log
import com.agentcontrolcenter.app.core.hardware.SoCOptimizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地模型管理器 — 支持离线推理
 *
 * 当前实现：通过 HTTP 请求本地推理服务（如 llama.cpp server, Ollama, LM Studio）
 * 骁龙优化：根据检测到的 SoC 能力自动调整推理参数（线程数、上下文长度、NPU 加速）
 */
class LocalModelManager {

    data class LocalModel(
        val id: String,
        val name: String,
        val size: String = "",
        val isRunning: Boolean = false,
        val endpoint: String = "http://localhost:11434",
        val provider: LocalProvider = LocalProvider.OLLAMA
    )

    enum class LocalProvider(val displayName: String, val defaultPort: Int) {
        OLLAMA("Ollama", 11434),
        LM_STUDIO("LM Studio", 1234),
        LLAMA_CPP("llama.cpp", 8080)
    }

    data class LocalModelState(
        val models: List<LocalModel> = emptyList(),
        val isDiscovering: Boolean = false,
        val discoveredProviders: List<LocalProvider> = emptyList(),
        val error: String? = null,
        val hardwareOptimized: Boolean = false,
        val npuAcceleration: Boolean = false
    )

    private val _state = MutableStateFlow(LocalModelState())
    val state: StateFlow<LocalModelState> = _state.asStateFlow()

    companion object {
        private const val TAG = "LocalModelManager"
        private const val CONNECT_TIMEOUT = 3000
        private const val READ_TIMEOUT = 30000
        private const val DEFAULT_OLLAMA = "http://localhost:11434"
        private const val DEFAULT_LM_STUDIO = "http://localhost:1234"
        private const val DEFAULT_LLAMA_CPP = "http://localhost:8080"
    }

    /**
     * 自动发现本地推理服务
     */
    suspend fun discoverModels(): List<LocalModel> = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(isDiscovering = true, error = null)
        val allModels = mutableListOf<LocalModel>()
        val discovered = mutableListOf<LocalProvider>()

        // 尝试连接 Ollama
        try {
            val ollamaModels = fetchOllamaModels(DEFAULT_OLLAMA)
            if (ollamaModels.isNotEmpty()) {
                allModels.addAll(ollamaModels)
                discovered.add(LocalProvider.OLLAMA)
            }
        } catch (e: Exception) {
            // F33：原 `catch (_: Exception) { }` 静默吞错，用户看不到为何 Ollama 未被发现。
            if (e is CancellationException) throw e
            Log.w(TAG, "Ollama discovery failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 尝试连接 LM Studio (OpenAI-compatible API)
        try {
            val lmStudioModels = fetchOpenAICompatibleModels(DEFAULT_LM_STUDIO, LocalProvider.LM_STUDIO)
            if (lmStudioModels.isNotEmpty()) {
                allModels.addAll(lmStudioModels)
                discovered.add(LocalProvider.LM_STUDIO)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "LM Studio discovery failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 尝试连接 llama.cpp
        try {
            val llamaCppModels = fetchLlamaCppModels(DEFAULT_LLAMA_CPP)
            if (llamaCppModels.isNotEmpty()) {
                allModels.addAll(llamaCppModels)
                discovered.add(LocalProvider.LLAMA_CPP)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "llama.cpp discovery failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        _state.value = _state.value.copy(
            models = allModels,
            isDiscovering = false,
            discoveredProviders = discovered,
            error = if (allModels.isEmpty()) "No local models found" else null
        )
        allModels
    }

    /**
     * 手动添加本地服务地址并发现模型
     */
    suspend fun discoverFromEndpoint(endpoint: String, provider: LocalProvider): List<LocalModel> =
        withContext(Dispatchers.IO) {
            _state.value = _state.value.copy(isDiscovering = true, error = null)
            try {
                val models = when (provider) {
                    LocalProvider.OLLAMA -> fetchOllamaModels(endpoint)
                    LocalProvider.LM_STUDIO -> fetchOpenAICompatibleModels(endpoint, provider)
                    LocalProvider.LLAMA_CPP -> fetchLlamaCppModels(endpoint)
                }
                val merged = (_state.value.models + models).distinctBy { it.id }
                _state.value = _state.value.copy(
                    models = merged,
                    isDiscovering = false,
                    discoveredProviders = (_state.value.discoveredProviders + provider).distinct()
                )
                models
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    isDiscovering = false,
                    error = "Failed to connect to $endpoint: ${e.message}"
                )
                emptyList()
            }
        }

    /**
     * 通过 OpenAI-compatible API 发送 prompt 到本地模型
     */
    suspend fun sendPrompt(model: LocalModel, prompt: String, systemPrompt: String = ""): String =
        withContext(Dispatchers.IO) {
            when (model.provider) {
                LocalProvider.OLLAMA -> sendOllamaPrompt(model, prompt, systemPrompt)
                LocalProvider.LM_STUDIO,
                LocalProvider.LLAMA_CPP -> sendOpenAICompatiblePrompt(model, prompt, systemPrompt)
            }
        }

    /**
     * 流式发送 prompt（返回 delta 文本的 Flow）
     */
    suspend fun sendPromptStream(
        model: LocalModel,
        prompt: String,
        systemPrompt: String = "",
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        when (model.provider) {
            LocalProvider.OLLAMA -> sendOllamaPromptStream(model, prompt, systemPrompt, onDelta)
            LocalProvider.LM_STUDIO,
            LocalProvider.LLAMA_CPP -> sendOpenAICompatiblePromptStream(model, prompt, systemPrompt, onDelta)
        }
    }

    // ── Ollama API ──

    private fun fetchOllamaModels(endpoint: String): List<LocalModel> {
        val conn = openConnection("$endpoint/api/tags")
        try {
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return emptyList()

            val body = readResponse(conn)
            val json = JSONObject(body)
            val modelsArray = json.getJSONArray("models")
            return (0 until modelsArray.length()).map { i ->
                val obj = modelsArray.getJSONObject(i)
                LocalModel(
                    id = obj.getString("name"),
                    name = obj.getString("name"),
                    size = formatSize(obj.optLong("size", 0)),
                    endpoint = endpoint,
                    provider = LocalProvider.OLLAMA
                )
            }
        } finally {
            // 显式断开连接，释放底层 socket（即便 BufferedReader 关闭了 reader，
            // keep-alive 连接仍可能驻留导致 socket 泄漏）。
            conn.disconnect()
        }
    }

    private fun sendOllamaPrompt(model: LocalModel, prompt: String, systemPrompt: String): String {
        val messages = buildMessagesArray(prompt, systemPrompt)
        val body = JSONObject().apply {
            put("model", model.id)
            put("messages", messages)
            put("stream", false)
            // SoC 优化：注入硬件调优参数
            put("options", JSONObject(SoCOptimizer.getOllamaOptions()))
        }

        val conn = openConnection("${model.endpoint}/api/chat")
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            writeBody(conn, body.toString())

            if (conn.responseCode != 200) {
                throw RuntimeException("Ollama API error: ${conn.responseCode}")
            }

            val resp = JSONObject(readResponse(conn))
            return resp.getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private fun sendOllamaPromptStream(
        model: LocalModel,
        prompt: String,
        systemPrompt: String,
        onDelta: (String) -> Unit
    ): String {
        val messages = buildMessagesArray(prompt, systemPrompt)
        val body = JSONObject().apply {
            put("model", model.id)
            put("messages", messages)
            put("stream", true)
            // SoC 优化：注入硬件调优参数
            put("options", JSONObject(SoCOptimizer.getOllamaOptions()))
        }

        val conn = openConnection("${model.endpoint}/api/chat")
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            writeBody(conn, body.toString())

            if (conn.responseCode != 200) {
                throw RuntimeException("Ollama API error: ${conn.responseCode}")
            }

            val fullResponse = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // readLine() 已保证非空，但 line 是 var 局部变量，smart-cast 无法传播。
                    // 这里用局部非空变量 current 捕获，避免 !! 在并发/异常路径下抛 NPE。
                    val current = line ?: continue
                    if (current.isBlank()) continue
                    try {
                        val chunk = JSONObject(current)
                        val content = chunk.getJSONObject("message").optString("content", "")
                        if (content.isNotEmpty()) {
                            fullResponse.append(content)
                            onDelta(content)
                        }
                    } catch (e: Exception) {
                        // F33：单 chunk 解析失败不应中断流式响应，但完全静默会让协议错误不可见。
                        // 用 Log.d（非 WARN）避免每 chunk 一条日志刷屏；排查时用
                        // `adb shell setprop log.tag.LocalModelManager DEBUG` 开启。
                        if (e is CancellationException) throw e
                        Log.d(TAG, "Ollama stream chunk parse failed (len=${current.length}): ${e.javaClass.simpleName}")
                    }
                }
            }
            return fullResponse.toString()
        } finally {
            conn.disconnect()
        }
    }

    // ── OpenAI-Compatible API (LM Studio, llama.cpp) ──

    private fun fetchOpenAICompatibleModels(endpoint: String, provider: LocalProvider): List<LocalModel> {
        val conn = openConnection("$endpoint/v1/models")
        try {
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return emptyList()

            val body = readResponse(conn)
            val json = JSONObject(body)
            val modelsArray = json.getJSONArray("data")
            return (0 until modelsArray.length()).map { i ->
                val obj = modelsArray.getJSONObject(i)
                LocalModel(
                    id = obj.getString("id"),
                    name = obj.getString("id"),
                    endpoint = endpoint,
                    provider = provider
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchLlamaCppModels(endpoint: String): List<LocalModel> {
        // llama.cpp server may not have /v1/models, try health endpoint first
        val conn = openConnection("$endpoint/health")
        try {
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return emptyList()

            // Return a placeholder model since llama.cpp typically serves one model
            return listOf(
                LocalModel(
                    id = "llama-cpp-model",
                    name = "llama.cpp Model",
                    endpoint = endpoint,
                    provider = LocalProvider.LLAMA_CPP
                )
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun sendOpenAICompatiblePrompt(model: LocalModel, prompt: String, systemPrompt: String): String {
        val messages = buildMessagesArray(prompt, systemPrompt)
        val body = JSONObject().apply {
            put("model", model.id)
            put("messages", messages)
            put("stream", false)
        }

        val conn = openConnection("${model.endpoint}/v1/chat/completions")
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            writeBody(conn, body.toString())

            if (conn.responseCode != 200) {
                throw RuntimeException("API error: ${conn.responseCode}")
            }

            val resp = JSONObject(readResponse(conn))
            return resp.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private fun sendOpenAICompatiblePromptStream(
        model: LocalModel,
        prompt: String,
        systemPrompt: String,
        onDelta: (String) -> Unit
    ): String {
        val messages = buildMessagesArray(prompt, systemPrompt)
        val body = JSONObject().apply {
            put("model", model.id)
            put("messages", messages)
            put("stream", true)
        }

        val conn = openConnection("${model.endpoint}/v1/chat/completions")
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            writeBody(conn, body.toString())

            if (conn.responseCode != 200) {
                throw RuntimeException("API error: ${conn.responseCode}")
            }

            val fullResponse = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // 同上：用局部非空变量 current 捕获，避免 !! 在 SSE 流读取中抛 NPE。
                    val current = line ?: continue
                    if (current.isBlank() || current == "data: [DONE]") continue
                    val data = current.removePrefix("data: ").trim()
                    if (data.isEmpty()) continue
                    try {
                        val chunk = JSONObject(data)
                        val delta = chunk.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("delta")
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            fullResponse.append(content)
                            onDelta(content)
                        }
                    } catch (e: Exception) {
                        // F33：同 Ollama 分支，单 chunk 解析失败用 Log.d，避免刷屏。
                        if (e is CancellationException) throw e
                        Log.d(TAG, "OpenAI-compat stream chunk parse failed (len=${current.length}): ${e.javaClass.simpleName}")
                    }
                }
            }
            return fullResponse.toString()
        } finally {
            conn.disconnect()
        }
    }

    // ── Helpers ──

    private fun buildMessagesArray(prompt: String, systemPrompt: String): JSONArray {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })
        return messages
    }

    private fun openConnection(urlStr: String): HttpURLConnection {
        val url = URL(urlStr)
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
    }

    private fun writeBody(conn: HttpURLConnection, body: String) {
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
            else -> "$bytes B"
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
