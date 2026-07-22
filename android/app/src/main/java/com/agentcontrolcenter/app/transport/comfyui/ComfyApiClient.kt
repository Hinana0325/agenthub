package com.agentcontrolcenter.app.transport.comfyui

import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.core.security.UrlValidator
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * ComfyUI HTTP API 客户端 — 封装提交工作流、轮询结果两类操作。
 *
 * 错误以 sealed result 返回，由调用方决定如何转成 [com.agentcontrolcenter.app.transport.protocol.AgentEvent]，
 * 避免在传输层与 HTTP 细节之间产生耦合。
 */
internal class ComfyApiClient(
    private val client: HttpClient
) {

    /**
     * 提交工作流到 `/prompt`。
     *
     * 成功返回 [SubmitResult.Success]（含 `prompt_id`）；
     * HTTP 非 2xx 返回 [SubmitResult.HttpError]；
     * 响应缺 `prompt_id` 字段返回 [SubmitResult.MissingPromptId]；
     * 网络/超时异常返回 [SubmitResult.NetworkError]。
     */
    suspend fun submitWorkflow(
        base: String,
        workflowJson: JsonObject,
        config: AgentConfig
    ): SubmitResult {
        val promptUrl = "$base/prompt"
        if (UrlValidator.validate(promptUrl, allowLocalhost = true) == null) {
            return SubmitResult.InvalidUrl
        }

        val clientId = UUID.randomUUID().toString()
        val requestBody = JsonObject().apply {
            add("prompt", workflowJson)
            addProperty("client_id", clientId)
        }

        return try {
            val response = withTimeout(30_000) {
                client.post(promptUrl) {
                    header("Content-Type", ContentType.Application.Json.toString())
                    if (config.apiKey.isNotBlank()) {
                        header("Authorization", "Bearer ${config.apiKey}")
                    }
                    setBody(requestBody.toString())
                }
            }
            if (response.status.value !in 200..299) {
                SubmitResult.HttpError(response.status.value, response.bodyAsText().take(200))
            } else {
                val responseBody = JsonParser.parseString(response.bodyAsText()).asJsonObject
                val promptId = responseBody.get("prompt_id")?.asString
                if (promptId != null) {
                    SubmitResult.Success(promptId)
                } else {
                    SubmitResult.MissingPromptId
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SubmitResult.NetworkError(e.message)
        } catch (e: com.google.gson.JsonParseException) {
            SubmitResult.NetworkError("响应解析失败：${e.message}")
        }
    }

    /**
     * 轮询 `/history/{prompt_id}` 直到工作流执行完成或超时。
     *
     * - [PollResult.Success]：执行完成并提取到图片输出
     * - [PollResult.WorkflowError]：服务端返回 `status_str == "error"`
     * - [PollResult.Timeout]：达到最大轮询次数仍未生成图片
     * - [PollResult.NetworkError]：网络异常
     */
    suspend fun pollHistory(
        base: String,
        promptId: String,
        config: AgentConfig
    ): PollResult {
        val historyUrl = "$base/history/$promptId"
        if (UrlValidator.validate(historyUrl, allowLocalhost = true) == null) {
            return PollResult.NetworkError("Invalid history URL")
        }
        return try {
            pollUntilComplete(historyUrl, promptId, config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            PollResult.NetworkError(e.message)
        } catch (e: com.google.gson.JsonParseException) {
            PollResult.NetworkError("响应解析失败：${e.message}")
        }
    }

    /**
     * 轮询循环：每 3s 一次，最多 120 次（约 6 分钟）。
     *
     * 单一 `repeat` 块内只通过 `when` 分支的 `return` 早出，避免多次 `break/continue`
     * 触发 detekt `LoopWithTooManyJumpStatements`。
     */
    private suspend fun pollUntilComplete(
        historyUrl: String,
        promptId: String,
        config: AgentConfig
    ): PollResult {
        val maxAttempts = 120
        val intervalMs = 3000L
        repeat(maxAttempts) {
            delay(intervalMs)
            when (val state = checkHistoryOnce(historyUrl, promptId, config)) {
                is PollState.Done -> return PollResult.Success(state.images)
                is PollState.Error -> return PollResult.WorkflowError
                PollState.NotReady -> { /* 尚未完成，继续轮询 */ }
            }
        }
        return PollResult.Timeout
    }

    private sealed class PollState {
        object NotReady : PollState()
        data class Done(val images: List<ComfyImageOutput>) : PollState()
        object Error : PollState()
    }

    /**
     * 查询一次 history，返回当前状态。
     */
    private suspend fun checkHistoryOnce(
        historyUrl: String,
        promptId: String,
        config: AgentConfig
    ): PollState {
        val pollResponse = client.get(historyUrl) {
            if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
        }
        if (pollResponse.status.value !in 200..299) return PollState.NotReady

        val historyJson = JsonParser.parseString(pollResponse.bodyAsText()).asJsonObject
        val promptEntry = historyJson.getAsJsonObject(promptId) ?: return PollState.NotReady

        val statusObj = promptEntry.getAsJsonObject("status")
        if (statusObj?.get("status_str")?.asString == "error") return PollState.Error

        val isCompleted = statusObj?.get("completed")?.asBoolean ?: false
        if (isCompleted || promptEntry.has("outputs")) {
            val images = ComfyImageOutputExtractor.extract(promptEntry)
            return if (images.isNotEmpty()) PollState.Done(images) else PollState.NotReady
        }
        return PollState.NotReady
    }

    sealed class SubmitResult {
        data class Success(val promptId: String) : SubmitResult()
        data class HttpError(val code: Int, val body: String) : SubmitResult()
        object MissingPromptId : SubmitResult()
        object InvalidUrl : SubmitResult()
        data class NetworkError(val message: String?) : SubmitResult()
    }

    sealed class PollResult {
        data class Success(val images: List<ComfyImageOutput>) : PollResult()
        object WorkflowError : PollResult()
        object Timeout : PollResult()
        data class NetworkError(val message: String?) : PollResult()
    }

    /**
     * 健康检查：GET {base}/system_stats。
     *
     * 返回 2xx 视为可达；任何异常/非 2xx 视为不可达。
     */
    suspend fun checkHealth(config: AgentConfig): Boolean {
        val base = config.serverUrl.trimEnd('/')
        val probeUrl = "$base/system_stats"
        if (UrlValidator.validate(probeUrl, allowLocalhost = true) == null) {
            return false
        }
        return try {
            val response = withTimeout(5000) {
                client.get(probeUrl) {
                    if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            response.status.value in 200..299
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            false
        }
    }
}
