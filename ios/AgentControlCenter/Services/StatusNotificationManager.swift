import Foundation
import Observation

// MARK: - AppStatus

/// 应用全局连接状态枚举。
///
/// 用于在 UI 顶部状态条中展示当前 Agent / 传输层 / 同步通道的连接状态。
/// 状态值按生命周期顺序排列：idle → connecting → connected，
/// 任何阶段均可能转入 error。
enum AppStatus: String, Codable, Equatable {
    /// 空闲，未发起连接
    case idle
    /// 正在连接中
    case connecting
    /// 已连接
    case connected
    /// 发生错误
    case error

    /// 中文显示名称
    var displayName: String {
        switch self {
        case .idle:       return "空闲"
        case .connecting: return "连接中"
        case .connected:  return "已连接"
        case .error:      return "错误"
        }
    }

    /// SF Symbol 图标名称
    var systemImage: String {
        switch self {
        case .idle:       return "circle.dotted"
        case .connecting: return "arrow.triangle.2.circlepath"
        case .connected:  return "checkmark.circle.fill"
        case .error:      return "exclamationmark.circle.fill"
        }
    }
}

// MARK: - StatusRecord

/// 状态历史记录条目，记录某次状态变更的时间、状态与描述信息。
struct StatusRecord: Identifiable, Codable, Equatable {
    /// 唯一标识
    let id: UUID
    /// 记录时间
    let timestamp: Date
    /// 状态值
    let status: AppStatus
    /// 状态描述信息
    let message: String

    /// 创建状态记录
    /// - Parameters:
    ///   - status: 状态值
    ///   - message: 描述信息
    ///   - timestamp: 记录时间，默认当前时间
    init(status: AppStatus, message: String, timestamp: Date = Date()) {
        self.id = UUID()
        self.timestamp = timestamp
        self.status = status
        self.message = message
    }

    private enum CodingKeys: String, CodingKey {
        case id, timestamp, status, message
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try container.decode(UUID.self, forKey: .id)
        self.timestamp = try container.decode(Date.self, forKey: .timestamp)
        self.status = try container.decode(AppStatus.self, forKey: .status)
        self.message = try container.decode(String.self, forKey: .message)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(timestamp, forKey: .timestamp)
        try container.encode(status, forKey: .status)
        try container.encode(message, forKey: .message)
    }
}

// MARK: - StatusNotificationManager

/// 状态通知管理器 — 维护全局连接状态及历史记录，供 UI 状态条展示。
///
/// 职责：
/// - 持有当前应用连接状态（`currentStatus`）和可读描述（`statusMessage`）
/// - 记录状态变更历史（`statusHistory`），最多保留 100 条
/// - 提供 `updateStatus(_:message:)` 统一入口供各模块上报状态
///
/// 与 `LocalNotificationManager` 的区别：
/// - 本类管理 UI 内的状态条展示（in-app banner）
/// - `LocalNotificationManager` 管理系统级推送通知
@MainActor
@Observable
final class StatusNotificationManager {

    // MARK: - 属性

    /// 当前应用状态
    var currentStatus: AppStatus = .idle

    /// 当前状态的描述信息
    var statusMessage: String = "尚未连接"

    /// 状态变更历史（按时间降序，最新的在前）
    private(set) var statusHistory: [StatusRecord] = []

    /// 历史记录最大保留条数
    private let maxHistoryCount = 100

    // MARK: - 状态更新

    /// 更新当前状态并写入历史记录。
    ///
    /// 若新状态与当前状态相同且消息也相同，则跳过以避免冗余记录。
    /// - Parameters:
    ///   - status: 新状态
    ///   - message: 状态描述信息
    func updateStatus(_ status: AppStatus, message: String) {
        // 避免重复记录完全相同的状态
        if status == currentStatus && message == statusMessage {
            return
        }

        currentStatus = status
        statusMessage = message

        let record = StatusRecord(status: status, message: message)
        statusHistory.insert(record, at: 0)

        // 超出上限时移除最旧的记录
        if statusHistory.count > maxHistoryCount {
            statusHistory.removeLast()
        }
    }

    // MARK: - 快捷方法

    /// 设置为「连接中」状态
    /// - Parameter message: 可选的自定义描述，默认 "正在连接服务器…"
    func setConnecting(_ message: String = "正在连接服务器…") {
        updateStatus(.connecting, message: message)
    }

    /// 设置为「已连接」状态
    /// - Parameter message: 可选的自定义描述，默认 "连接正常"
    func setConnected(_ message: String = "连接正常") {
        updateStatus(.connected, message: message)
    }

    /// 设置为「错误」状态
    /// - Parameter message: 错误描述信息
    func setError(_ message: String) {
        updateStatus(.error, message: message)
    }

    /// 设置为「空闲」状态
    /// - Parameter message: 可选的自定义描述，默认 "尚未连接"
    func setIdle(_ message: String = "尚未连接") {
        updateStatus(.idle, message: message)
    }

    // MARK: - 历史管理

    /// 清空状态历史记录
    func clearHistory() {
        statusHistory.removeAll()
    }
}
