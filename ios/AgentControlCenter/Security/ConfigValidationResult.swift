import Foundation

// MARK: - 配置校验结果
// 对应 Android com.agentcontrolcenter.app.core.config.ConfigValidationResult
//
// 设计为不可变值类型（struct + let），天然 Sendable，可在 Swift 6 strict
// concurrency complete 模式下跨 actor 安全传递。校验失败时由调用方读取
// `errors` 列表回填表单 UI，或通过 `errorFor(_:)` 按字段名检索错误消息。

/// 单个配置校验错误。
///
/// - Parameters:
///   - field: 出错字段名（与 `AgentConfigValidator.Field` / `McpServerValidator.Field` 常量对齐）
///   - message: 面向用户的错误描述（中文）
struct ConfigValidationError: Equatable, Sendable {
    let field: String
    let message: String
}

/// 配置校验结果。
///
/// 一次校验产生 0 条或多条 `ConfigValidationError`。`isValid` 为 `true` 时表示
/// 整份配置通过校验，可安全落库。
struct ConfigValidationResult: Equatable, Sendable {

    /// 全部错误（按校验顺序追加）。空数组表示校验通过。
    let errors: [ConfigValidationError]

    /// 是否校验通过（无任何错误）
    var isValid: Bool { errors.isEmpty }

    /// 获取指定字段的错误消息。
    /// - Parameter field: 字段名
    /// - Returns: 该字段第一条错误的描述；字段无错时返回 nil
    func errorFor(_ field: String) -> String? {
        errors.first(where: { $0.field == field })?.message
    }

    /// 无错误的合法结果（便于调用方书写 `guard result.isValid else { ... }` 后的通过路径）
    static let valid = ConfigValidationResult(errors: [])

    /// 从错误列表构造结果。
    /// - Parameter errors: 错误数组（可为空，等价于 `.valid`）
    static func of(_ errors: [ConfigValidationError]) -> ConfigValidationResult {
        ConfigValidationResult(errors: errors)
    }
}
