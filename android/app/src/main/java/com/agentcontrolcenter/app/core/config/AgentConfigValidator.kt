package com.agentcontrolcenter.app.core.config

import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.core.security.UrlValidator

// MARK: - AgentConfigValidator
// 对应 iOS Security/AgentConfigValidator.swift
// 复用 [UrlValidator] 校验 serverUrl，并补齐字段级校验（必填 / 范围 / 长度上限）。
// 在 AgentsViewModel.saveAgent() 入口主动调用，避免无效配置进入 Room。

/**
 * [AgentConfig] 字段校验器。
 *
 * 校验项：
 * - name 非空、长度 ≤ 64
 * - serverUrl 非空、合法 scheme、防 SSRF（复用 [UrlValidator]）
 * - apiKey 非空（LocalModel 类型豁免）
 * - model 非空
 * - temperature ∈ [0.0, 2.0]
 * - maxTokens ∈ [256, 32768]
 * - systemPrompt 长度 ≤ 8000（OpenAI 风格 system 上限，留余量）
 */
object AgentConfigValidator {

    private const val NAME_MAX = 64
    private const val SYSTEM_PROMPT_MAX = 8000
    private const val TEMPERATURE_MIN = 0.0f
    private const val TEMPERATURE_MAX = 2.0f
    private const val MAX_TOKENS_MIN = 256
    private const val MAX_TOKENS_MAX = 32768

    /**
     * 校验 [AgentConfig]。
     *
     * @return [ConfigValidationResult]，errors 为空表示通过
     */
    fun validate(config: AgentConfig): ConfigValidationResult {
        val errors = mutableListOf<ConfigValidationError>()

        // ── name ──
        if (config.name.isBlank()) {
            errors += ConfigValidationError("name", "名称不能为空")
        } else if (config.name.length > NAME_MAX) {
            errors += ConfigValidationError("name", "名称不能超过 $NAME_MAX 字符")
        }

        // ── serverUrl ──
        // LocalModel 走本地进程，serverUrl 不必填；其他类型必须有 URL。
        val isLocal = config.type == AgentType.LocalModel
        if (!isLocal) {
            if (config.serverUrl.isBlank()) {
                errors += ConfigValidationError("serverUrl", "服务器地址不能为空")
            } else {
                val url = UrlValidator.validate(config.serverUrl, allowLocalhost = true)
                if (url == null) {
                    errors += ConfigValidationError(
                        "serverUrl",
                        "地址不合法或不安全（仅支持 http/https/ws/wss，禁止 SSRF 元数据 IP）"
                    )
                }
            }
        }

        // ── apiKey ──
        // LocalModel / OpenCode 等本地部署可豁免；远程协议必须有 apiKey。
        if (!isLocal && config.apiKey.isBlank()) {
            errors += ConfigValidationError("apiKey", "API Key 不能为空")
        }

        // ── model ──
        // LocalModel 也需要 model 名称（Ollama 模型名 / GGUF 文件名等）
        if (config.model.isBlank()) {
            errors += ConfigValidationError("model", "模型名称不能为空")
        }

        // ── temperature ──
        if (config.temperature < TEMPERATURE_MIN || config.temperature > TEMPERATURE_MAX) {
            errors += ConfigValidationError(
                "temperature",
                "temperature 必须在 $TEMPERATURE_MIN ~ $TEMPERATURE_MAX 之间（当前 ${config.temperature}）"
            )
        }

        // ── maxTokens ──
        // OpenAI 上限是 16384/32768 不等，统一用 32768 作上限；下限 256 避免 1-token 截断
        if (config.maxTokens < MAX_TOKENS_MIN || config.maxTokens > MAX_TOKENS_MAX) {
            errors += ConfigValidationError(
                "maxTokens",
                "maxTokens 必须在 $MAX_TOKENS_MIN ~ $MAX_TOKENS_MAX 之间（当前 ${config.maxTokens}）"
            )
        }

        // ── systemPrompt ──
        if (config.systemPrompt.length > SYSTEM_PROMPT_MAX) {
            errors += ConfigValidationError(
                "systemPrompt",
                "系统提示词不能超过 $SYSTEM_PROMPT_MAX 字符（当前 ${config.systemPrompt.length}）"
            )
        }

        return ConfigValidationResult(errors)
    }
}
