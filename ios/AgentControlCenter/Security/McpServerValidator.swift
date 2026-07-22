import Foundation

// MARK: - McpServer 校验器
// 对应 Android com.agentcontrolcenter.app.core.config.McpServerValidator
//
// 校验 MCP 服务器配置（`McpServer`，定义于 Models/MCPModels.swift）。
// 与 `AgentConfigValidator` 同样为静态 `enum` 命名空间。

/// `McpServer` 校验器。
///
/// 校验项（与 Android `McpServerValidator` 一致）：
/// - `name` 非空，长度 ≤ 64
/// - `transportType = STDIO` 时 `transportUrl` 为命令路径，禁止 shell 元字符（;|&`$()<>\n\r）
/// - `transportType = SSE/HTTP` 时 `transportUrl` 用 `URLValidator` 校验
/// - `apiKey` 可选，但若非空则长度 ≥ 8
enum McpServerValidator {

    /// 字段名常量。
    enum Field {
        static let name = "name"
        static let transportUrl = "transportUrl"
        static let apiKey = "apiKey"
    }

    /// STDIO 命令路径禁止的 shell 元字符集合。
    /// 防止用户在命令路径中注入管道 / 重定向 / 命令分隔符等危险字符。
    private static let shellMetacharacters: Set<Character> = [
        ";", "|", "&", "`", "$", "(", ")", "<", ">", "\n", "\r"
    ]

    /// 校验 McpServer。
    /// - Parameter server: 待校验的 MCP 服务器配置
    /// - Returns: 校验结果；`isValid` 为 true 时可安全保存
    static func validate(_ server: McpServer) -> ConfigValidationResult {
        var errors: [ConfigValidationError] = []

        // name 非空，长度 ≤ 64
        let trimmedName = server.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedName.isEmpty {
            errors.append(ConfigValidationError(field: Field.name, message: "名称不能为空"))
        } else if server.name.count > 64 {
            errors.append(ConfigValidationError(field: Field.name, message: "名称长度不能超过 64 个字符"))
        }

        // transportUrl：非空 + 按传输类型校验
        let trimmedUrl = server.transportUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedUrl.isEmpty {
            errors.append(ConfigValidationError(field: Field.transportUrl, message: "传输地址不能为空"))
        } else {
            switch server.transportType {
            case .stdio:
                // STDIO：命令路径，禁止 shell 元字符（防止命令注入）
                if containsShellMetacharacter(trimmedUrl) {
                    errors.append(ConfigValidationError(
                        field: Field.transportUrl,
                        message: "STDIO 命令路径包含非法 shell 元字符"
                    ))
                }
            case .sse, .http:
                // SSE / HTTP：用 URLValidator 校验（防 SSRF / 危险 scheme）
                if URLValidator.validate(trimmedUrl) == nil {
                    errors.append(ConfigValidationError(
                        field: Field.transportUrl,
                        message: "传输地址不合法或存在安全风险"
                    ))
                }
            }
        }

        // apiKey 可选，但若非空则长度 ≥ 8
        if let key = server.apiKey, !key.isEmpty, key.count < 8 {
            errors.append(ConfigValidationError(field: Field.apiKey, message: "API Key 至少 8 个字符"))
        }

        return .of(errors)
    }

    /// 检查字符串中是否包含 shell 元字符。
    private static func containsShellMetacharacter(_ value: String) -> Bool {
        value.contains { shellMetacharacters.contains($0) }
    }
}
