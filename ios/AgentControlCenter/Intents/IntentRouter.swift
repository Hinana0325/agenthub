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
/// 因此将暂存属性提取到此无依赖的 actor 中：
/// - AppIntent `perform()` 通过 `await ShortcutRelay.shared.setPending(_:)` 写入
/// - `IntentRouter.bind(to:)` 通过 `await ShortcutRelay.shared.consumePendingDestination()` 读取并清空
///
/// 线程安全（Swift 6 严格并发）：`ShortcutRelay` 是 actor，
/// 跨线程访问由 actor 隔离保证，无需手动加锁。
/// AppIntent `perform()` 运行在后台执行器；IntentRouter 在 MainActor 上读取，
/// 两者通过 actor 串行化访问，无数据竞争。
actor ShortcutRelay {

    /// 全局共享实例 — AppIntent 与 IntentRouter 都通过此单例读写
    static let shared = ShortcutRelay()

    /// 冷启动时由 AppIntent 写入，由 IntentRouter.bind(to:) 读取并清空
    private(set) var pendingDestination: AppShortcutDestination?

    private init() {}

    /// 写入待处理的目标（覆盖已有值）。
    /// - Parameter destination: 导航目标；传 nil 等价于清空
    func setPending(_ destination: AppShortcutDestination?) {
        pendingDestination = destination
    }

    /// 原子地读取并清空待处理目标（一次性消费语义）。
    /// - Returns: 之前暂存的目标；若无返回 nil
    func consumePendingDestination() -> AppShortcutDestination? {
        let pending = pendingDestination
        pendingDestination = nil
        return pending
    }
}

// MARK: - IntentRouter

/// App Intent 通知路由器 — 监听 `NotificationCenter` 通知并转发到 `AppState`。
///
/// 职责：
/// 1. 监听 [ShortcutIntentNotification] 中的三种通知
/// 2. 将通知转换为 `AppShortcutDestination` 并写入 `AppState.pendingShortcutDestination`
/// 3. 处理冷启动场景：Intent 可能在 IntentRouter 初始化前触发，
///    通过 [ShortcutRelay.shared] actor 暂存，待 AppState 绑定后回放
///
/// 使用方式（在 `AgentControlCenterApp.init()` 中）：
/// ```swift
/// let router = IntentRouter.shared
/// router.bind(to: appState)
/// ```
///
/// 线程安全：本类标记 `@MainActor`，通知回调通过 `Task { @MainActor }` 跳转；
/// `ShortcutRelay` 是 actor，跨线程访问由 actor 隔离保证。
///
/// - Note: 本类不直接使用 AppIntents framework，而是通过 NotificationCenter
///   解耦。由于引用了 `AppState`（`@Observable`，iOS 17+），
///   本类隐式要求 iOS 17+（与项目部署目标一致）。
@MainActor
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
    /// 绑定时会检查 [ShortcutRelay.shared] 暂存的目标：
    /// 若存在冷启动时暂存的目标，回放到 AppState 并清空。
    ///
    /// - Note: 由于 [ShortcutRelay] 是 actor，回放通过 `Task` 异步完成。
    ///   `appState` 立即绑定，确保后续 `route(to:)` 同步路径可用。
    /// - Parameter appState: 应用全局状态
    func bind(to appState: AppState) {
        self.appState = appState

        // 异步消费冷启动时暂存的目标，回放到 AppState
        Task { [weak self] in
            guard let self else { return }
            if let pending = await ShortcutRelay.shared.consumePendingDestination() {
                self.appState?.pendingShortcutDestination = pending
            }
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
            // 通知回调闭包非 isolated；用 Task 跳转到 MainActor 调用 route(to:)
            Task { @MainActor [weak self] in
                await self?.route(to: .newChat)
            }
        }

        // 新建 Agent
        let newAgentObserver = center.addObserver(
            forName: ShortcutIntentNotification.newAgent,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                await self?.route(to: .newAgent)
            }
        }

        // 打开设置
        let openSettingsObserver = center.addObserver(
            forName: ShortcutIntentNotification.openSettings,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                await self?.route(to: .settings)
            }
        }

        observers = [newChatObserver, newAgentObserver, openSettingsObserver]
    }

    /// 路由到指定目标 — 设置 AppState 的 pendingShortcutDestination。
    ///
    /// 若 AppState 尚未绑定（冷启动中），暂存到 [ShortcutRelay.shared]。
    ///
    /// - Parameter destination: 快捷方式导航目标
    private func route(to destination: AppShortcutDestination) async {
        if let appState {
            appState.pendingShortcutDestination = destination
        } else {
            // AppState 尚未绑定 — 暂存到 actor，待 bind(to:) 时回放
            await ShortcutRelay.shared.setPending(destination)
        }
    }

    /// 移除所有观察者（保留用于未来 deinit 场景）
    deinit {
        let center = NotificationCenter.default
        observers.forEach { center.removeObserver($0) }
    }
}
