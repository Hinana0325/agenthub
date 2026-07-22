package com.agentcontrolcenter.app.transport.comfyui

import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.core.error.AppErrorCode
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
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
import com.agentcontrolcenter.app.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.app.transport.protocol.AgentEvent
import com.agentcontrolcenter.app.transport.protocol.AgentTransport

/**
 * ComfyUI 传输层 — 适配基于节点的图像生成工作流引擎。
 *
 * ComfyUI 无聊天接口，API 范式为「提交工作流 → 轮询结果 → 下载图片」。
 * 本传输层提供两种模式（由 [sendMessage] 的 content 自动判断）：
 *
 * 1. **文本→图像模式**（content 不以 `{` 开头）：
 *    用户输入作为正向提示词，由 [ComfyWorkflowBuilder] 构造默认文生图工作流。
 *
 * 2. **工作流 JSON 模式**（content 以 `{` 开头）：
 *    用户直接提供 ComfyUI API 格式的工作流 JSON，原样提交到 `/prompt`。
 *    适用于需要自定义节点/参数的高级用户。
 *
 * 结果图片通过 `/view` 端点下载，转 base64 后以 markdown 图片语法返回：
 * `![ComfyUI](data:image/png;base64,...)`
 *
 * HTTP 操作委托给 [ComfyApiClient]，工作流构造委托给 [ComfyWorkflowBuilder]，
 * 图片下载/提取委托给 [ComfyImageOutput] / [ComfyImageOutputExtractor]。
 */
class ComfyUITransport : AgentTransport {

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = Channel<AgentEvent>(Channel.BUFFERED)
    override val events: Flow<AgentEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(AgentConnectionState())
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 30_000
        }
    }

    private val apiClient = ComfyApiClient(client)

    @Volatile
    private var currentConfig: AgentConfig? = null

    private val sendMutex = Mutex()

    override fun connect(config: AgentConfig, e2eKey: String?) {
        currentConfig = config
        // 探活：GET /system_stats（ComfyUI 内置的健康检查端点），委托给 apiClient
        eventScope.launch {
            val reachable = apiClient.checkHealth(config)
            if (reachable) {
                _connectionState.value = AgentConnectionState(
                    isConnected = true,
                    serverUrl = config.serverUrl,
                    agentType = AgentType.ComfyUI
                )
                _events.send(AgentEvent.Connected(config.serverUrl, AgentType.ComfyUI))
            } else {
                _events.send(
                    AgentEvent.Error(
                        "无法连接到 ComfyUI 服务端",
                        code = AppErrorCode.TRANSPORT_CONNECT_FAILED
                    )
                )
            }
        }
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
        sendMutex.withLock {
            val config = currentConfig ?: run {
                _events.send(
                    AgentEvent.Error("Not connected", code = AppErrorCode.AGENT_CONFIG_MISSING)
                )
                return
            }
            val base = config.serverUrl.trimEnd('/')

            val workflowJson = buildWorkflow(content, config) ?: return
            _events.send(AgentEvent.MessageReceived("正在提交工作流到 ComfyUI...\n", isDelta = true))

            val promptId = submitWorkflow(base, workflowJson, config) ?: return
            _events.send(
                AgentEvent.MessageReceived(
                    "工作流已提交 (prompt_id: $promptId)，等待执行完成...\n",
                    isDelta = true
                )
            )

            val images = pollForImages(base, promptId, config) ?: return
            downloadAndEmitImages(images, base, config)
        }
    }

    /**
     * 根据输入内容构造工作流 JSON。
     *
     * - content 以 `{` 开头 → 解析为用户提供的 ComfyUI API 工作流 JSON
     * - 否则 → 调用 [ComfyWorkflowBuilder.buildTextToImageWorkflow] 构造默认文生图工作流
     *
     * 解析失败时发送错误事件并返回 null（调用方应直接 return）。
     */
    private suspend fun buildWorkflow(content: String, config: AgentConfig): JsonObject? {
        if (!content.trimStart().startsWith("{")) {
            return ComfyWorkflowBuilder.buildTextToImageWorkflow(content, config)
        }
        return try {
            JsonParser.parseString(content).asJsonObject
        } catch (e: com.google.gson.JsonParseException) {
            _events.send(
                AgentEvent.Error(
                    "工作流 JSON 解析失败：${e.message}",
                    code = AppErrorCode.AGENT_CONFIG_MISSING
                )
            )
            null
        }
    }

    /**
     * 提交工作流，发送错误事件后返回 null（调用方直接 return）。
     */
    private suspend fun submitWorkflow(
        base: String,
        workflowJson: JsonObject,
        config: AgentConfig
    ): String? {
        return when (val result = apiClient.submitWorkflow(base, workflowJson, config)) {
            is ComfyApiClient.SubmitResult.Success -> result.promptId
            is ComfyApiClient.SubmitResult.HttpError -> {
                val code = if (result.code == 401 || result.code == 403) {
                    AppErrorCode.TRANSPORT_AUTH_FAILED
                } else {
                    AppErrorCode.TRANSPORT_CONNECT_FAILED
                }
                _events.send(
                    AgentEvent.Error(
                        "ComfyUI 提交失败 (HTTP ${result.code}): ${result.body}",
                        code = code
                    )
                )
                null
            }
            ComfyApiClient.SubmitResult.MissingPromptId -> {
                _events.send(
                    AgentEvent.Error(
                        "ComfyUI 响应缺少 prompt_id",
                        code = AppErrorCode.TRANSPORT_CONNECT_FAILED
                    )
                )
                null
            }
            ComfyApiClient.SubmitResult.InvalidUrl -> {
                _events.send(
                    AgentEvent.Error(
                        "Invalid or blocked server URL",
                        code = AppErrorCode.TRANSPORT_CONNECT_FAILED
                    )
                )
                null
            }
            is ComfyApiClient.SubmitResult.NetworkError -> {
                _events.send(
                    AgentEvent.Error(
                        "提交工作流失败：${result.message}",
                        code = AppErrorCode.TRANSPORT_CONNECT_FAILED
                    )
                )
                null
            }
        }
    }

    /**
     * 轮询工作流结果，返回图片输出列表；出错时发送错误事件后返回 null。
     */
    private suspend fun pollForImages(
        base: String,
        promptId: String,
        config: AgentConfig
    ): List<ComfyImageOutput>? {
        return when (val result = apiClient.pollHistory(base, promptId, config)) {
            is ComfyApiClient.PollResult.Success -> result.images
            ComfyApiClient.PollResult.WorkflowError -> {
                _events.send(
                    AgentEvent.Error(
                        "ComfyUI 工作流执行出错",
                        code = AppErrorCode.TRANSPORT_CONNECT_FAILED
                    )
                )
                null
            }
            ComfyApiClient.PollResult.Timeout -> {
                _events.send(
                    AgentEvent.Error(
                        "ComfyUI 工作流执行超时或未生成图片",
                        code = AppErrorCode.TRANSPORT_TIMEOUT
                    )
                )
                null
            }
            is ComfyApiClient.PollResult.NetworkError -> {
                _events.send(
                    AgentEvent.Error(
                        "轮询结果失败：${result.message}",
                        code = AppErrorCode.TRANSPORT_CONNECT_FAILED
                    )
                )
                null
            }
        }
    }

    /**
     * 下载所有图片并以 markdown 形式输出；若全部失败则发送错误事件。
     */
    private suspend fun downloadAndEmitImages(
        images: List<ComfyImageOutput>,
        base: String,
        config: AgentConfig
    ) {
        val markdown = StringBuilder()
        for ((index, img) in images.withIndex()) {
            val md = img.toMarkdown(base, config.apiKey, client) ?: continue
            if (index > 0) markdown.append("\n\n")
            markdown.append(md)
        }
        if (markdown.isEmpty()) {
            _events.send(
                AgentEvent.Error(
                    "图片下载失败",
                    code = AppErrorCode.TRANSPORT_CONNECT_FAILED
                )
            )
            return
        }
        _events.send(AgentEvent.MessageReceived(markdown.toString(), isDelta = false))
        _events.send(AgentEvent.StreamComplete)
    }

    override fun disconnect() {
        currentConfig = null
        _connectionState.value = AgentConnectionState()
        eventScope.launch {
            _events.send(AgentEvent.Disconnected("User disconnected"))
        }
    }

    override fun shutdown() {
        eventScope.cancel()
        client.close()
        _connectionState.value = AgentConnectionState()
        currentConfig = null
    }
}
