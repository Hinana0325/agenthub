import Foundation

// MARK: - AgentConfig 校验器
// 对应 Android com.agentcontrolcenter.app.core.config.AgentConfigValidator
//
// 纯静态 `enum` 命名空间，无实例状态，天然线程安全。调用方在保存 AgentConfig
// 前调用 `validate(_:)`，校验失败时根据返回的 `ConfigValidationResult` 回填
// 表单错误并阻止落库。

/// `AgentConfig` 校验器。
///
/// 校验项（与 Android `AgentConfigValidator` 一致）：
/// - `name` 非空，长度 ≤ 64
/// - `serverUrl` 非空 + `URLValidator` 校验（`LocalModel` 类型豁免）
/// - `apiKey` 非空（`LocalModel` 豁免）
/// - `model` 非空
/// - `temperature` ∈ [0.0, 2.0]
/// - `maxTokens` ∈ [256, 32768]
/// - `systemPrompt` 长度 ≤ 8000
enum AgentConfigValidator {

    /// 字段名常量，便于 UI 按 `errorFor(_:)` 检索对应字段的错误消息。
    enum Field {
        static let name = "name"
        static let serverUrl = "serverUrl"
        static let apiKey = "apiKey"
        static let model = "model"
        static let temperature = "temperature"
        static let maxTokens = "maxTokens"
        static let systemPrompt = "systemPrompt"
    }

    /// 校验 AgentConfig。
    /// - Parameter config: 待校验的 Agent 配置
    /// - Returns: 校验结果；`isValid` 为 true 时可安全保存
    static func validate(_ config: AgentConfig) -> ConfigValidationResult {
        var errors: [ConfigValidationError] = []

        // name 非空，长度 ≤ 64
        let trimmedName = config.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedName.isEmpty {
            errors.append(ConfigValidationError(field: Field.name, message: "名称不能为空"))
        } else if config.name.count > 64 {
            errors.append(ConfigValidationError(field: Field.name, message: "名称长度不能超过 64 个字符"))
        }

        // serverUrl：非空 + URLValidator（LocalModel 豁免，本地模型走 LocalModelManager）
        if config.type != .localModel {
            let trimmedUrl = config.serverUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmedUrl.isEmpty {
                errors.append(ConfigValidationError(field: Field.serverUrl, message: "服务器地址不能为空"))
            } else if URLValidator.validate(trimmedUrl) == nil {
                errors.append(ConfigValidationError(field: Field.serverUrl, message: "服务器地址不合法或存在安全风险"))
            }
        }

        // apiKey：非空（LocalModel / ComfyUI 豁免）
        // 与 Android AgentConfigValidator 对齐：ComfyUI 本地部署通常无认证，可豁免 apiKey。
        // OpenWebUI 仍需 apiKey（与 OpenAI 一致，远程 HTTP+SSE 协议）。
        let apiKeyOptional = config.type == .localModel || config.type == .comfyUI
        if !apiKeyOptional {
            if config.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                errors.append(ConfigValidationError(field: Field.apiKey, message: "API Key 不能为空"))
            }
        }

        // model 非空
        if config.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            errors.append(ConfigValidationError(field: Field.model, message: "模型不能为空"))
        }

        // temperature ∈ [0.0, 2.0]（AgentConfig.temperature 为 Float）
        if config.temperature < 0.0 || config.temperature > 2.0 {
            errors.append(ConfigValidationError(field: Field.temperature, message: "温度必须在 0.0 ~ 2.0 之间"))
        }

        // maxTokens ∈ [256, 32768]
        if config.maxTokens < 256 || config.maxTokens > 32768 {
            errors.append(ConfigValidationError(field: Field.maxTokens, message: "最大 Tokens 必须在 256 ~ 32768 之间"))
        }

        // systemPrompt 长度 ≤ 8000
        if config.systemPrompt.count > 8000 {
            errors.append(ConfigValidationError(field: Field.systemPrompt, message: "System Prompt 长度不能超过 8000 个字符"))
        }

        return .of(errors)
    }
}
