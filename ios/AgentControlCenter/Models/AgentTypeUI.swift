import Foundation

// MARK: - AgentTypeUI
// 对应 Android com.agentcontrolcenter.app.agent.model.AgentTypeUi
//
// [AgentType] 的 UI 辅助 — 集中提供图标、字段标签、默认配置等展示层信息。
//
// 字段标签按类型语义化复用 [AgentConfig] 字段：
// - 通用 LLM（Hermes/OpenClaw/OpenCode/OpenAI/XiaomiMiMo/LocalModel/OpenWebUI）使用 OpenAI 风格标签
// - ComfyUI 走「文生图工作流」语义：model→checkpoint、temperature→cfg、maxTokens→steps、
//   systemPrompt→negative prompt
//
// ComfyUI 本地部署通常无认证，apiKey 标记为可选。

/// [AgentType] 的 UI 辅助枚举 — 提供图标、动态字段标签与默认配置预填。
///
/// 作为无实例命名空间使用（与 `WorkflowTemplates` / `MarketplaceSamples` 风格一致），
/// 所有能力通过静态方法暴露。切换 AgentType 时调用 [withDefaults(for:)] 预填合理默认值，
/// 减少用户手动输入；表单字段标签通过 [modelLabel(for:)] 等方法按类型语义化展示。
enum AgentTypeUI {

    /// 按 [AgentType] 返回代表性 SF Symbol 图标名。
    ///
    /// - WebSocket 系（Hermes/OpenClaw/OpenCode）→ robot / sparkles / 代码符号
    /// - OpenAI 兼容（OpenAI/XiaomiMiMo/LocalModel/OpenWebUI）→ cpu / memorychip / flame / globe
    /// - ComfyUI → photo（图像生成）
    static func icon(for type: AgentType) -> String {
        switch type {
        case .hermes:       return "robot"
        case .openCode:     return "chevron.left.forwardslash.chevron.right"
        case .openClaw:     return "sparkles"
        case .openAI:       return "cpu"
        case .xiaomiMiMo:   return "memorychip"
        case .localModel:   return "flame"
        case .comfyUI:      return "photo"
        case .openWebUI:    return "globe"
        }
    }

    /// apiKey 是否对该类型可选（与 AgentConfigValidator 一致）。
    ///
    /// - LocalModel：本地推理，无远程认证
    /// - ComfyUI：本地部署通常无认证
    static func apiKeyOptional(for type: AgentType) -> Bool {
        switch type {
        case .localModel, .comfyUI:
            return true
        default:
            return false
        }
    }

    /// model 字段的展示标签。
    ///
    /// ComfyUI 用 checkpoint 语义；其他类型沿用通用「模型」标签。
    static func modelLabel(for type: AgentType) -> String {
        switch type {
        case .comfyUI: return "Checkpoint 文件名"
        default:       return "模型"
        }
    }

    /// model 字段的占位提示。
    static func modelPlaceholder(for type: AgentType) -> String {
        switch type {
        case .comfyUI:    return "v1-5-pruned-emaonly.safetensors"
        case .openAI:     return "gpt-4o"
        case .openWebUI:  return "llama3"
        case .localModel: return "llama3"
        default:          return ""
        }
    }

    /// serverUrl 字段的占位提示。
    static func serverUrlPlaceholder(for type: AgentType) -> String {
        switch type {
        case .comfyUI:    return "http://127.0.0.1:8188"
        case .openWebUI:  return "http://127.0.0.1:3000/api/v1"
        case .localModel: return "http://127.0.0.1:11434"
        case .openAI:     return "https://api.openai.com/v1"
        default:          return ""
        }
    }

    /// temperature 字段的展示标签。
    ///
    /// ComfyUI 用 cfg scale（CFG 引导强度）语义；其他类型沿用通用「Temperature」。
    static func temperatureLabel(for type: AgentType) -> String {
        switch type {
        case .comfyUI: return "CFG Scale"
        default:       return "Temperature"
        }
    }

    /// maxTokens 字段的展示标签。
    ///
    /// ComfyUI 用 steps（采样步数）语义；其他类型沿用通用「Max Tokens」。
    static func maxTokensLabel(for type: AgentType) -> String {
        switch type {
        case .comfyUI: return "Steps（采样步数）"
        default:       return "Max Tokens"
        }
    }

    /// systemPrompt 字段的展示标签。
    ///
    /// ComfyUI 用 negative prompt（负向提示词）语义；其他类型沿用通用「System Prompt」。
    static func systemPromptLabel(for type: AgentType) -> String {
        switch type {
        case .comfyUI: return "Negative Prompt（负向提示词）"
        default:       return "System Prompt"
        }
    }

    /// 为指定类型的配置预填合理默认值（仅在字段为空 / 默认值时填充，不覆盖用户已输入内容）。
    ///
    /// 切换 AgentType 时调用，减少用户手动输入：
    /// - ComfyUI：serverUrl=`http://127.0.0.1:8188`，checkpoint=`v1-5-pruned-emaonly.safetensors`，
    ///   cfg=7.0，steps=20，negative=`bad quality, blurry`
    /// - OpenWebUI：serverUrl=`http://127.0.0.1:3000/api/v1`，model=`llama3`
    /// - LocalModel：serverUrl=`http://127.0.0.1:11434`，model=`llama3`
    /// - OpenAI：serverUrl=`https://api.openai.com/v1`，model=`gpt-4o`
    ///
    /// - Parameter config: 当前配置（仅保留 name / id 等用户已输入的字段）
    /// - Returns: 预填默认值后的配置副本
    static func withDefaults(for config: AgentConfig) -> AgentConfig {
        var copy = config
        switch copy.type {
        case .comfyUI:
            if copy.serverUrl.isBlank { copy.serverUrl = "http://127.0.0.1:8188" }
            if copy.model.isBlank { copy.model = "v1-5-pruned-emaonly.safetensors" }
            // 仅在仍是通用默认值时替换为 ComfyUI 语义默认值，避免覆盖用户自定义
            if copy.temperature == 0.7 { copy.temperature = 7.0 }
            if copy.maxTokens == 4096 { copy.maxTokens = 20 }
            if copy.systemPrompt.isBlank { copy.systemPrompt = "bad quality, blurry" }
        case .openWebUI:
            if copy.serverUrl.isBlank { copy.serverUrl = "http://127.0.0.1:3000/api/v1" }
            if copy.model.isBlank { copy.model = "llama3" }
        case .localModel:
            if copy.serverUrl.isBlank { copy.serverUrl = "http://127.0.0.1:11434" }
            if copy.model.isBlank { copy.model = "llama3" }
        case .openAI:
            if copy.serverUrl.isBlank { copy.serverUrl = "https://api.openai.com/v1" }
            if copy.model.isBlank { copy.model = "gpt-4o" }
        default:
            break
        }
        return copy
    }
}

// MARK: - String 空白判断辅助

private extension String {
    /// 等价 Kotlin `String.ifBlank` 的判断：仅空白（或空）时返回 true。
    var isBlank: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
