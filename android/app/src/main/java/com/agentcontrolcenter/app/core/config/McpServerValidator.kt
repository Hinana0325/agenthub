package com.agentcontrolcenter.app.core.config

import com.agentcontrolcenter.app.mcp.model.McpServer
import com.agentcontrolcenter.app.mcp.model.McpTransportType
import com.agentcontrolcenter.app.core.security.UrlValidator

// MARK: - McpServerValidator
// 对应 iOS Security/McpServerValidator.swift
// 与 [AgentConfigValidator] 同级，统一在表单保存入口主动校验。

/**
 * [McpServer] 字段校验器。
 *
 * 校验项：
 * - name 非空、长度 ≤ 64
 * - transportType=STDIO：transportUrl 为命令路径，非空、不含 shell 元字符（防止注入）
 * - transportType=SSE/HTTP：transportUrl 合法 http(s) URL（复用 [UrlValidator]）
 * - apiKey 可选，但若非空则长度 ≥ 8（避免误填单字符）
 */
object McpServerValidator {

    private const val NAME_MAX = 64
    private const val API_KEY_MIN = 8

    /**
     * STDIO 模式下禁止的 shell 元字符，防止用户输入 `"rm -rf /"` 之类。
     * 这不是命令注入的完整防护（应用层应使用 ProcessBuilder 而非 shell），
     * 但可作为第一道防线。
     */
    private val STDIO_DANGEROUS_CHARS = setOf(';', '|', '&', '`', '$', '(', ')', '<', '>', '\n', '\r')

    /**
     * 校验 [McpServer]。
     *
     * @return [ConfigValidationResult]，errors 为空表示通过
     */
    fun validate(server: McpServer): ConfigValidationResult {
        val errors = mutableListOf<ConfigValidationError>()

        // ── name ──
        if (server.name.isBlank()) {
            errors += ConfigValidationError("name", "名称不能为空")
        } else if (server.name.length > NAME_MAX) {
            errors += ConfigValidationError("name", "名称不能超过 $NAME_MAX 字符")
        }

        // ── transportUrl（按类型分别校验）──
        if (server.transportUrl.isBlank()) {
            errors += ConfigValidationError(
                "transportUrl",
                if (server.transportType == McpTransportType.STDIO) "命令路径不能为空"
                else "服务器地址不能为空"
            )
        } else {
            when (server.transportType) {
                McpTransportType.STDIO -> {
                    val dangerous = server.transportUrl.firstOrNull { it in STDIO_DANGEROUS_CHARS }
                    if (dangerous != null) {
                        errors += ConfigValidationError(
                            "transportUrl",
                            "STDIO 命令路径含禁止字符 '$dangerous'（避免 shell 注入）"
                        )
                    }
                }
                McpTransportType.SSE, McpTransportType.HTTP -> {
                    val url = UrlValidator.validate(server.transportUrl, allowLocalhost = true)
                    if (url == null) {
                        errors += ConfigValidationError(
                            "transportUrl",
                            "地址不合法或不安全（仅支持 http/https，禁止 SSRF 元数据 IP）"
                        )
                    }
                }
            }
        }

        // ── apiKey（可选，但非空时长度校验）──
        val key = server.apiKey
        if (!key.isNullOrBlank() && key.length < API_KEY_MIN) {
            errors += ConfigValidationError(
                "apiKey",
                "API Key 若填写则长度需 ≥ $API_KEY_MIN（避免误填）"
            )
        }

        return ConfigValidationResult(errors)
    }
}
