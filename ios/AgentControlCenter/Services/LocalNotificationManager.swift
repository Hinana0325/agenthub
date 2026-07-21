import Foundation
import Observation
import UserNotifications

// MARK: - LocalNotificationManager
// 对应 Android LocalNotificationManager（基于 NotificationManager / NotificationChannel）

/// 本地通知管理器 — 封装 UserNotifications 框架，提供权限请求、消息/任务通知调度与取消能力。
///
/// 职责：
/// - 请求通知授权并维护 `hasPermission` 状态
/// - 收到新消息时通过 `scheduleMessageNotification` 发送即时通知
/// - 任务完成时通过 `scheduleTaskNotification` 发送通知（携带 taskId 用于跳转）
/// - 查询/取消待处理通知
///
/// 与 `SmartNotificationManager` 的区别：
/// - `SmartNotificationManager` 负责「是否应该通知」的过滤逻辑（优先级、免打扰）
/// - `LocalNotificationManager` 负责「如何通知」的执行层（UNUserNotificationCenter 调度）
/// 二者可配合使用：先由 SmartNotificationManager 判断，再由本类调度。
//
// CI-fix: 标记 `@unchecked Sendable`。原 `@Observable final class` 既非
// @MainActor 也非 Sendable，但 init / 各方法在 Task / UNUserNotificationCenter
// 回调中通过 `[weak self]` 捕获 self，触发 Swift 6 "sending 'self' risks
// causing data races"。
//
// 之所以用 `@unchecked Sendable` 而非 `@MainActor`：本类调用
// `UNUserNotificationCenter.current().notificationSettings()` 和
// `.pendingNotificationRequests()`，两者均 nonisolated async 且返回
// 非 Sendable 类型 (UNNotificationSettings / [UNNotificationRequest])。
// 若类为 @MainActor，结果需跨 actor 边界返回 MainActor，但非 Sendable 类型
// 无法跨边界。`@unchecked Sendable` 让 self 可被 Task 捕获，同时不改变
// 类的隔离，await 结果留在 nonisolated 上下文使用，无需跨边界。
//
// 线程安全：所有可变属性 (hasPermission / pendingNotifications) 的写入均
// 通过 `await MainActor.run { ... }` 串行化到 MainActor，运行时安全。
@Observable
final class LocalNotificationManager: @unchecked Sendable {

    // MARK: - 通知标识符前缀

    /// 消息通知标识符前缀
    private static let messageNotificationPrefix = "acc-message-"

    /// 任务通知标识符前缀
    private static let taskNotificationPrefix = "acc-task-"

    // MARK: - 属性

    /// 是否拥有通知权限
    var hasPermission: Bool = false

    /// 当前待处理的通知列表（从 UNUserNotificationCenter 查询）
    var pendingNotifications: [UNNotificationRequest] = []

    // MARK: - 初始化

    /// 创建本地通知管理器并异步检查当前权限状态
    init() {
        Task { [weak self] in
            await self?.refreshPermissionStatus()
            await self?.refreshPendingNotifications()
        }
    }

    // MARK: - 权限管理

    /// 请求通知授权（alert / badge / sound）。
    ///
    /// 在首次调用时弹出系统授权弹窗；后续调用仅检查状态。
    /// - Returns: 是否已授权
    @discardableResult
    func requestPermission() async -> Bool {
        let center = UNUserNotificationCenter.current()
        do {
            let granted = try await center.requestAuthorization(options: [.alert, .badge, .sound])
            await MainActor.run {
                hasPermission = granted
            }
            return granted
        } catch {
            await MainActor.run {
                hasPermission = false
            }
            return false
        }
    }

    /// 从系统查询当前授权状态并更新 `hasPermission`
    func refreshPermissionStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let granted = settings.authorizationStatus == .authorized
            || settings.authorizationStatus == .provisional
            || settings.authorizationStatus == .ephemeral
        await MainActor.run {
            hasPermission = granted
        }
    }

    // MARK: - 消息通知

    /// 收到新消息时发送即时通知。
    ///
    /// 若未获授权则静默跳过；通知标识符使用前缀 + UUID 保证唯一性，
    /// 避免同一条消息被重复通知。
    /// - Parameters:
    ///   - title: 通知标题（如 "Agent 消息"）
    ///   - body: 通知正文（消息内容摘要）
    func scheduleMessageNotification(title: String, body: String) {
        guard hasPermission else { return }

        let identifier = Self.messageNotificationPrefix + UUID().uuidString
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = "MESSAGE"

        // 立即触发
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)

        // CI-fix: 避免 `UNUserNotificationCenter.add(_:completionHandler:)` 的
        // completion 闭包被作为 `sending` 参数传递触发 Swift 6.2 数据竞争检查。
        // 改用 async 版 `add(_:)`，整体包裹在 Task 中。
        Task { [weak self] in
            do {
                try await UNUserNotificationCenter.current().add(request)
            } catch {
                // 通知添加失败，忽略错误（与原 completion 回调忽略 error 行为一致）
            }
            await self?.refreshPendingNotifications()
        }
    }

    // MARK: - 任务通知

    /// 任务完成时发送通知，携带 taskId 供点击跳转使用。
    ///
    /// 将 taskId 存入 userInfo，应用在收到通知响应时可读取该字段
    /// 导航到对应任务详情页。
    /// - Parameters:
    ///   - title: 通知标题（如 "任务完成"）
    ///   - body: 通知正文（任务结果摘要）
    ///   - taskId: 关联的任务 ID
    func scheduleTaskNotification(title: String, body: String, taskId: String) {
        guard hasPermission else { return }

        let identifier = Self.taskNotificationPrefix + taskId
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = "TASK"
        content.userInfo = ["taskId": taskId]

        // 立即触发
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)

        // CI-fix: 同 scheduleMessageNotification，改用 async 版 add
        Task { [weak self] in
            do {
                try await UNUserNotificationCenter.current().add(request)
            } catch {
                // 通知添加失败，忽略错误
            }
            await self?.refreshPendingNotifications()
        }
    }

    // MARK: - 取消通知

    /// 取消所有待处理通知
    func cancelAll() {
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        Task { [weak self] in
            await self?.refreshPendingNotifications()
        }
    }

    /// 取消指定标识符的待处理通知。
    ///
    /// 支持 cancel(identifier:) 调用形式，内部委托给
    /// `removePendingNotificationRequests(withIdentifiers:)`。
    /// - Parameter identifier: 通知标识符
    func cancel(identifier: String) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [identifier])
        Task { [weak self] in
            await self?.refreshPendingNotifications()
        }
    }

    // MARK: - 查询

    /// 从 UNUserNotificationCenter 拉取当前待处理通知列表
    func refreshPendingNotifications() async {
        let requests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        await MainActor.run {
            pendingNotifications = requests
        }
    }
}
