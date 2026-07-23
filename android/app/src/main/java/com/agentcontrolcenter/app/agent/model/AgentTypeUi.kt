package com.agentcontrolcenter.app.agent.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Cpu
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * [AgentType] 的 UI 辅助 — 集中提供图标、字段标签、默认配置等展示层信息。
 *
 * 字段标签按类型语义化复用 [AgentConfig] 字段：
 * - 通用 LLM（Hermes/OpenClaw/OpenCode/OpenAI/XiaomiMiMo/LocalModel/OpenWebUI）使用 OpenAI 风格标签
 * - ComfyUI 走「文生图工作流」语义：model→checkpoint、temperature→cfg、maxTokens→steps、
 *   systemPrompt→negative prompt
 *
 * ComfyUI 本地部署通常无认证，apiKey 标记为可选。
 */
object AgentTypeUi {

    /**
     * 按 [AgentType] 返回代表性图标。
     *
     * - WebSocket 系（Hermes/OpenClaw/OpenCode）→ SmartToy / Code / AutoAwesome
     * - OpenAI 兼容（OpenAI/XiaomiMiMo/LocalModel/OpenWebUI）→ Cpu / Memory / LocalFireDepartment / Public
     * - ComfyUI → Image（图像生成）
     */
    fun icon(type: AgentType): ImageVector = when (type) {
        AgentType.Hermes -> Icons.Default.SmartToy
        AgentType.OpenCode -> Icons.Default.Code
        AgentType.OpenClaw -> Icons.Default.AutoAwesome
        AgentType.OpenAI -> Icons.Default.Cpu
        AgentType.XiaomiMiMo -> Icons.Default.Memory
        AgentType.LocalModel -> Icons.Default.LocalFireDepartment
        AgentType.ComfyUI -> Icons.Default.Image
        AgentType.OpenWebUI -> Icons.Default.Public
    }

    /**
     * apiKey 是否对该类型可选（与 [com.agentcontrolcenter.app.core.config.AgentConfigValidator] 一致）。
     *
     * - LocalModel：本地推理，无远程认证
     * - ComfyUI：本地部署通常无认证
     */
    fun apiKeyOptional(type: AgentType): Boolean = when (type) {
        AgentType.LocalModel, AgentType.ComfyUI -> true
        else -> false
    }

    /**
     * 该类型是否需要远程服务器地址（LocalModel 不需要）。
     */
    fun serverUrlRequired(type: AgentType): Boolean = type != AgentType.LocalModel

    /**
     * model 字段的展示标签。
     *
     * ComfyUI 用 checkpoint 语义；其他类型沿用通用「模型」标签。
     */
    fun modelLabel(type: AgentType): String = when (type) {
        AgentType.ComfyUI -> "Checkpoint 文件名"
        else -> "模型"
    }

    /**
     * model 字段的占位提示。
     */
    fun modelPlaceholder(type: AgentType): String = when (type) {
        AgentType.ComfyUI -> "v1-5-pruned-emaonly.safetensors"
        AgentType.OpenAI -> "gpt-4o / gpt-3.5-turbo"
        AgentType.XiaomiMiMo -> "mimo-7b"
        AgentType.LocalModel -> "llama3 / qwen2"
        AgentType.OpenWebUI -> "llama3 / gpt-4o"
        AgentType.Hermes, AgentType.OpenClaw, AgentType.OpenCode -> "模型 ID"
    }

    /**
     * serverUrl 字段的占位提示。
     */
    fun serverUrlPlaceholder(type: AgentType): String = when (type) {
        AgentType.ComfyUI -> "http://127.0.0.1:8188"
        AgentType.OpenWebUI -> "http://127.0.0.1:3000/api/v1"
        AgentType.LocalModel -> "http://127.0.0.1:11434"
        AgentType.OpenAI -> "https://api.openai.com/v1"
        AgentType.XiaomiMiMo -> "https://mimo.example.com/v1"
        AgentType.Hermes, AgentType.OpenClaw, AgentType.OpenCode -> "wss://hermes.example.com/ws"
    }

    /**
     * temperature 字段的展示标签。
     *
     * ComfyUI 用 cfg scale（CFG 引导强度）语义；其他类型沿用通用「Temperature」。
     */
    fun temperatureLabel(type: AgentType): String = when (type) {
        AgentType.ComfyUI -> "CFG Scale"
        else -> "Temperature"
    }

    /**
     * maxTokens 字段的展示标签。
     *
     * ComfyUI 用 steps（采样步数）语义；其他类型沿用通用「Max Tokens」。
     */
    fun maxTokensLabel(type: AgentType): String = when (type) {
        AgentType.ComfyUI -> "Steps（采样步数）"
        else -> "Max Tokens"
    }

    /**
     * systemPrompt 字段的展示标签。
     *
     * ComfyUI 用 negative prompt（负向提示词）语义；其他类型沿用通用「System Prompt」。
     */
    fun systemPromptLabel(type: AgentType): String = when (type) {
        AgentType.ComfyUI -> "Negative Prompt（负向提示词）"
        else -> "System Prompt"
    }

    /**
     * 为指定类型生成默认配置预填值。
     *
     * 切换 AgentType 时调用，用于在表单中预填合理默认值，减少用户手动输入。
     * - ComfyUI：checkpoint = `v1-5-pruned-emaonly.safetensors`，cfg = 7.0，steps = 20，
     *   negative = `bad quality, blurry`
     * - OpenWebUI：serverUrl 提示 `/api/v1` 前缀
     * - LocalModel：默认 Ollama 端口 + llama3
     * - 其他类型：沿用通用 LLM 默认值（temperature=0.7、maxTokens=4096）
     *
     * @param current 当前配置（仅保留 name / id 等用户已输入的字段）
     * @return 预填默认值后的配置
     */
    fun withDefaults(current: AgentConfig): AgentConfig {
        return when (current.type) {
            AgentType.ComfyUI -> current.copy(
                serverUrl = current.serverUrl.ifBlank { "http://127.0.0.1:8188" },
                model = current.model.ifBlank { "v1-5-pruned-emaonly.safetensors" },
                temperature = if (current.temperature == 0.7f) 7.0f else current.temperature,
                maxTokens = if (current.maxTokens == 4096) 20 else current.maxTokens,
                systemPrompt = current.systemPrompt.ifBlank { "bad quality, blurry" }
            )
            AgentType.OpenWebUI -> current.copy(
                serverUrl = current.serverUrl.ifBlank { "http://127.0.0.1:3000/api/v1" },
                model = current.model.ifBlank { "llama3" }
            )
            AgentType.LocalModel -> current.copy(
                serverUrl = current.serverUrl.ifBlank { "http://127.0.0.1:11434" },
                model = current.model.ifBlank { "llama3" }
            )
            AgentType.OpenAI -> current.copy(
                serverUrl = current.serverUrl.ifBlank { "https://api.openai.com/v1" },
                model = current.model.ifBlank { "gpt-4o" }
            )
            else -> current
        }
    }
}
