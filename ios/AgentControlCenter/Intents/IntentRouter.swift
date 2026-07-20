import Foundation

// MARK: - AppShortcutDestination

/// 快捷方式导航目标 — 表示用户通过 App Intent / Siri 触发的目标页面。
///
/// 由 [IntentRouter] 写入 `AppState.pendingShortcutDestination`，
/// 由 `ContentView` 观察并消费（导航后清空）。
///
/// 映射关系：
/// - `newChat`  → 会话页面（SessionsView，即聊天入口）
/// - `newAgent` → Agent 页面（AgentsView）
/// - `settings` → 设置页面（SettingsView）
enum AppShortcutDestination: String {
    /// 新建聊天 — 导航到会话页面
    case newChat
    /// 新建 Agent — 导航到 Agent 页面
    case newAgent
    /// 设置 — 导航到设置页面
    case settings
}

// MARK: - ShortcutRelay

/// 冷启动暂存容器 — 独立于 [IntentRouter] 存在，无 iOS 17 依赖。
///
/// 设计目的：AppIntent（`@available(iOS 16, *)`）在 `perform()` 中需要写入
/// 待处理的导航目标，但 [IntentRouter] 引用了 `AppState`（`@Observable`，iOS 17+），
/// 若 AppIntent 直接引用 IntentRouter 会触发可用性冲突。
///
/// 因此将暂存属性提取到此无依赖的命名空间枚举中：
/// - AppIntent `perform()` 写入 `ShortcutRelay.pendingDestination`
/// - `IntentRouter.bind(to:)` 读取并清空该属性，回放到 AppState
///
/// 线程安全：`pendingDestination` 在 Intent `perform()`（后台线程）中写入，
/// 在 `bind(to:)`（主线程，应用启动时）中读取。由于两者不会并发执行
/// （冷启动时 perform 先于 bind），不存在数据竞争。
enum ShortcutRelay {
    /// 冷启动时由 AppIntent 写入，由 IntentRouter.bind(to:) 读取并清空
    static var pendingDestination: AppShortcutDestination?
}

// MARK: - IntentRouter

/// App Intent 通知路由器 — 监听 `NotificationCenter` 通知并转发到 `AppState`。
///
/// 职责：
/// 1. 监听 [ShortcutIntentNotification] 中的三种通知
/// 2. 将通知转换为 `AppShortcutDestination` 并写入 `AppState.pendingShortcutDestination`
/// 3. 处理冷启动场景：Intent 可能在 IntentRouter 初始化前触发，
///    通过 [ShortcutRelay.pendingDestination] 静态属性暂存，待 AppState 绑定后回放
///
/// 使用方式（在 `AgentControlCenterApp.init()` 中）：
/// ```swift
/// let router = IntentRouter.shared
/// router.bind(to: appState)
/// ```
///
/// 线程安全：通知回调在主线程执行（通过 `queue: .main` 保证）；
/// [ShortcutRelay.pendingDestination] 静态属性在 Intent `perform()` 中写入，
/// 在主线程 `bind(to:)` 中读取，不存在竞争。
///
/// - Note: 本类不直接使用 AppIntents framework，而是通过 NotificationCenter
///   解耦。由于引用了 `AppState`（`@Observable`，iOS 17+），
///   本类隐式要求 iOS 17+（与项目部署目标一致）。
final class IntentRouter {

    /// 共享单例 — 全局唯一路由器
    static let shared = IntentRouter()

    /// 绑定的应用状态 — 非空时通知会直接转发到此实例
    private weak var appState: AppState?

    /// NotificationCenter 观察者 token（用于 deinit 时移除）
    private var observers: [NSObjectProtocol] = []

    // MARK: - 初始化

    private init() {
        startObserving()
    }

    // MARK: - 绑定 AppState

    /// 绑定应用状态，使后续通知能直接转发到 AppState。
    ///
    /// 绑定时会检查 [ShortcutRelay.pendingDestination] 静态属性：
    /// 若存在冷启动时暂存的目标，立即回放到 AppState 并清空。
    ///
    /// - Parameter appState: 应用全局状态
    func bind(to appState: AppState) {
        self.appState = appState

        // 回放冷启动时暂存的目标
        if let pending = ShortcutRelay.pendingDestination {
            appState.pendingShortcutDestination = pending
            ShortcutRelay.pendingDestination = nil
        }
    }

    // MARK: - 通知监听

    /// 注册 NotificationCenter 观察者，监听三种快捷方式通知。
    ///
    /// 所有回调在主线程执行（`queue: .main`），确保对 AppState 的修改线程安全。
    private func startObserving() {
        let center = NotificationCenter.default

        // 新建聊天
        let newChatObserver = center.addObserver(
            forName: ShortcutIntentNotification.newChat,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.route(to: .newChat)
        }

        // 新建 Agent
        let newAgentObserver = center.addObserver(
            forName: ShortcutIntentNotification.newAgent,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.route(to: .newAgent)
        }

        // 打开设置
        let openSettingsObserver = center.addObserver(
            forName: ShortcutIntentNotification.openSettings,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.route(to: .settings)
        }

        observers = [newChatObserver, newAgentObserver, openSettingsObserver]
    }

    /// 路由到指定目标 — 设置 AppState 的 pendingShortcutDestination。
    ///
    /// 若 AppState 尚未绑定（冷启动中），暂存到 [ShortcutRelay.pendingDestination]。
    ///
    /// - Parameter destination: 快捷方式导航目标
    private func route(to destination: AppShortcutDestination) {
        if let appState {
            appState.pendingShortcutDestination = destination
        } else {
            // AppState 尚未绑定 — 暂存到静态属性，待 bind(to:) 时回放
            ShortcutRelay.pendingDestination = destination
        }
    }

    /// 移除所有观察者（保留用于未来 deinit 场景）
    deinit {
        let center = NotificationCenter.default
        observers.forEach { center.removeObserver($0) }
    }
}
