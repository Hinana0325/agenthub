import Foundation
import Observation

// MARK: - FeatureFlagManager
// 对应 Android com.agentcontrolcenter.app.core.featureflag.FeatureFlagManager
//
// 职责：功能开关的统一入口
// - 为每个功能标志提供默认值（开发中的功能默认关闭）
// - 支持用户通过设置页覆盖默认值，覆盖持久化到 UserDefaults
// - 提供 isEnabled(_:) 同步查询接口
// - @Observable 支持 SwiftUI 视图自动响应标志变化

/// Feature Flag 管理器 — 功能开关的统一入口。
///
/// 职责：
/// - 为每个功能标志提供默认值（开发中的功能默认关闭）
/// - 支持用户通过设置页覆盖默认值，覆盖持久化到 `UserDefaults`
/// - 提供 `isEnabled(_:)` 同步查询接口
/// - `@Observable` 支持 SwiftUI 视图自动响应标志变化
///
/// 设计理念：
/// - 默认值在 `FeatureFlag` 枚举中集中定义，避免散落在各业务模块
/// - 用户覆盖存储为 JSON `Data`（`[String: Bool]` 字典序列化），
///   支持区分「未设置」（使用默认值）与「显式设为 false」
/// - `@Observable` 追踪 `overrides` 属性，`setOverride` 赋值新字典触发 SwiftUI 重绘
///
/// 持久化说明：
/// - 覆盖表通过 `UserDefaults` 持久化（与 `@AppStorage` 等效）
/// - `@Observable` 类无法直接使用 `@AppStorage`（后者依赖 SwiftUI View 生命周期），
///   因此使用 `JSONEncoder` / `JSONDecoder` + `UserDefaults` 实现等效持久化
@Observable
final class FeatureFlagManager {

    // MARK: - FeatureFlag 枚举

    /// Feature Flag 枚举 — 应用中所有可通过开关控制的功能。
    ///
    /// 与 Android 端 `FeatureFlagManager.FeatureFlag` 对齐，
    /// `rawValue` 使用大写枚举名（如 `"WORKFLOW_ENGINE"`）以保证跨平台存储兼容。
    enum FeatureFlag: String, CaseIterable {
        /// 工作流引擎 — Agent 任务编排
        case workflowEngine = "WORKFLOW_ENGINE"
        /// Agent 市场 — 浏览和安装第三方 Agent
        case marketplace = "MARKETPLACE"
        /// 设备同步 — P2P 跨设备数据同步（开发中，默认关闭）
        case deviceSync = "DEVICE_SYNC"
        /// 数据洞察 — 使用统计与分析
        case insights = "INSIGHTS"
        /// 对比模式 — 多 Agent 输出对比
        case compareMode = "COMPARE_MODE"
        /// MCP 服务器 — Model Context Protocol 集成
        case mcpServers = "MCP_SERVERS"
        /// 自定义主题 — 用户自定义配色
        case customTheme = "CUSTOM_THEME"
        /// 语音输入 — Speech-to-Text
        case voiceInput = "VOICE_INPUT"
        /// 端到端加密 — 消息加密传输
        case e2eEncryption = "E2E_ENCRYPTION"

        /// 默认是否启用。
        ///
        /// - 已发布且稳定的功能默认为 `true`
        /// - 开发中或实验性功能默认为 `false`（如 `deviceSync`）
        var defaultEnabled: Bool {
            switch self {
            case .workflowEngine, .marketplace, .insights,
                 .compareMode, .mcpServers, .customTheme,
                 .voiceInput, .e2eEncryption:
                return true
            case .deviceSync:
                // 开发中，默认关闭
                return false
            }
        }
    }

    // MARK: - 配置常量

    /// 覆盖表的 UserDefaults 存储键。
    private let overridesKey = "featureFlagOverrides"

    // MARK: - 可观察属性

    /// 用户覆盖表。
    ///
    /// key 为 `FeatureFlag.rawValue`，value 为用户设置的覆盖值。
    /// 未出现在此字典中的标志使用 `FeatureFlag.defaultEnabled`。
    ///
    /// - Note: `setOverride` 通过赋值新字典（而非原地修改）来触发
    ///   `@Observable` 的变更通知，确保 SwiftUI 视图自动更新。
    ///   外部只读，写入通过 `setOverride` / `clearOverride` 进行。
    private(set) var overrides: [String: Bool] = [:]

    // MARK: - 初始化

    /// 初始化 Feature Flag 管理器，从 UserDefaults 恢复覆盖表。
    init() {
        loadOverrides()
    }

    // MARK: - 公开接口

    /// 同步查询某个功能标志是否启用。
    ///
    /// 查询顺序：
    /// 1. 用户覆盖（`overrides` 中存在则使用覆盖值）
    /// 2. 默认值（`FeatureFlag.defaultEnabled`）
    ///
    /// - Important: 在 SwiftUI View body 中调用此方法时，`@Observable` 会自动
    ///   注册对 `overrides` 属性的依赖。当 `overrides` 变更时，View 自动重绘。
    ///
    /// - Parameter flag: 要查询的功能标志
    /// - Returns: `true` 表示功能已启用
    func isEnabled(_ flag: FeatureFlag) -> Bool {
        return overrides[flag.rawValue] ?? flag.defaultEnabled
    }

    /// 设置用户覆盖值并持久化。
    ///
    /// 通过赋值新字典（而非原地修改 `overrides`）来触发 `@Observable` 变更通知，
    /// 确保 SwiftUI 视图自动更新。
    ///
    /// - Parameters:
    ///   - flag: 要覆盖的功能标志
    ///   - enabled: 覆盖值（`true` 启用 / `false` 禁用）
    func setOverride(_ flag: FeatureFlag, enabled: Bool) {
        var updated = overrides
        updated[flag.rawValue] = enabled
        overrides = updated
        saveOverrides()
    }

    /// 清除某个功能标志的用户覆盖，恢复为默认值。
    ///
    /// - Parameter flag: 要清除覆盖的功能标志
    func clearOverride(_ flag: FeatureFlag) {
        var updated = overrides
        updated.removeValue(forKey: flag.rawValue)
        overrides = updated
        saveOverrides()
    }

    // MARK: - 持久化

    /// 从 UserDefaults 加载覆盖表。
    ///
    /// 存储格式为 `[String: Bool]` 的 JSON `Data`。
    /// 解码失败时使用空字典（容错，避免损坏的存储数据阻塞应用启动）。
    private func loadOverrides() {
        guard let data = UserDefaults.standard.data(forKey: overridesKey) else {
            overrides = [:]
            return
        }
        do {
            overrides = try JSONDecoder().decode([String: Bool].self, from: data)
        } catch {
            // 存储数据损坏，回退到空覆盖表
            overrides = [:]
        }
    }

    /// 将覆盖表持久化到 UserDefaults。
    private func saveOverrides() {
        do {
            let data = try JSONEncoder().encode(overrides)
            UserDefaults.standard.set(data, forKey: overridesKey)
        } catch {
            // 编码失败时忽略（best-effort 持久化）
        }
    }
}
