package com.agenthub.app.localmodel

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
 * 未来可集成 MLC-LLM 进行 on-device 推理
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
        val error: String? = null
    )

    private val _state = MutableStateFlow(LocalModelState())
    val state: StateFlow<LocalModelState> = _state.asStateFlow()

    companion object {
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
        } catch (_: Exception) { }

        // 尝试连接 LM Studio (OpenAI-compatible API)
        try {
            val lmStudioModels = fetchOpenAICompatibleModels(DEFAULT_LM_STUDIO, LocalProvider.LM_STUDIO)
            if (lmStudioModels.isNotEmpty()) {
                allModels.addAll(lmStudioModels)
                discovered.add(LocalProvider.LM_STUDIO)
            }
        } catch (_: Exception) { }

        // 尝试连接 llama.cpp
        try {
            val llamaCppModels = fetchLlamaCppModels(DEFAULT_LLAMA_CPP)
            if (llamaCppModels.isNotEmpty()) {
                allModels.addAll(llamaCppModels)
                discovered.add(LocalProvider.LLAMA_CPP)
            }
        } catch (_: Exception) { }

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
    }

    private fun sendOllamaPrompt(model: LocalModel, prompt: String, systemPrompt: String): String {
        val messages = buildMessagesArray(prompt, systemPrompt)
        val body = JSONObject().apply {
            put("model", model.id)
            put("messages", messages)
            put("stream", false)
        }

        val conn = openConnection("${model.endpoint}/api/chat")
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        writeBody(conn, body.toString())

        if (conn.responseCode != 200) {
            throw RuntimeException("Ollama API error: ${conn.responseCode}")
        }

        val resp = JSONObject(readResponse(conn))
        return resp.getJSONObject("message").getString("content")
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
        }

        val conn = openConnection("${model.endpoint}/api/chat")
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
                if (line.isNullOrBlank()) continue
                try {
                    val chunk = JSONObject(line!!)
                    val content = chunk.getJSONObject("message").optString("content", "")
                    if (content.isNotEmpty()) {
                        fullResponse.append(content)
                        onDelta(content)
                    }
                } catch (_: Exception) { }
            }
        }
        return fullResponse.toString()
    }

    // ── OpenAI-Compatible API (LM Studio, llama.cpp) ──

    private fun fetchOpenAICompatibleModels(endpoint: String, provider: LocalProvider): List<LocalModel> {
        val conn = openConnection("$endpoint/v1/models")
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
    }

    private fun fetchLlamaCppModels(endpoint: String): List<LocalModel> {
        // llama.cpp server may not have /v1/models, try health endpoint first
        val conn = openConnection("$endpoint/health")
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
    }

    private fun sendOpenAICompatiblePrompt(model: LocalModel, prompt: String, systemPrompt: String): String {
        val messages = buildMessagesArray(prompt, systemPrompt)
        val body = JSONObject().apply {
            put("model", model.id)
            put("messages", messages)
            put("stream", false)
        }

        val conn = openConnection("${model.endpoint}/v1/chat/completions")
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
                if (line.isNullOrBlank() || line == "data: [DONE]") continue
                val data = line!!.removePrefix("data: ").trim()
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
                } catch (_: Exception) { }
            }
        }
        return fullResponse.toString()
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
