import Foundation
import Observation

// MARK: - AppState
// 对应 Android com.agentcontrolcenter.app.di.* (Hilt 依赖注入容器)

/// 应用全局状态 — 中央依赖容器。
///
/// 聚合应用运行所需的全部核心服务，通过 `@Observable` 暴露给 SwiftUI 视图树。
/// 子视图通过 `@Environment(AppState.self)` 获取依赖，无需手动传递。
///
/// `@MainActor` 隔离保证 SwiftUI 视图树对 `AppState` 的读取均在主线程，
/// 避免视图更新时发生数据竞争。内部持有的 I/O 类型管理器
/// （McpClient / VoiceChatManager 等）仍可在自己的 actor / Task 中工作，
/// 仅为 AppState 自身属性访问加主线程约束。
@MainActor
@Observable
final class AppState {

    /// 持久化控制器（SwiftData）
    let dataController: DataController

    /// Agent 管理器
    let agentManager: AgentManager

    /// 会话管理器
    let sessionManager: SessionManager

    /// 任务管理器
    let taskManager: TaskManager

    /// 工作流引擎
    let workflowEngine: WorkflowEngine

    /// MCP 桥接层
    let mcpBridge: McpBridge

    /// 插件执行器
    let pluginExecutor: PluginExecutor

    /// 语音输入管理器
    let voiceInputManager: VoiceInputManager

    /// 语音聊天管理器（录音 / 回放 / 波形可视化）
    ///
    /// 与 `voiceInputManager` 区分：
    /// - `voiceInputManager` 基于 SFSpeechRecognizer，负责语音转文字
    /// - `voiceChatManager` 基于 AVAudioRecorder/AVAudioPlayer，负责录制语音消息与回放
    let voiceChatManager: VoiceChatManager

    /// 市场客户端（本地示例数据模拟 API）
    let marketplaceClient: MarketplaceClient

    /// 本地模型发现
    let localModelManager: LocalModelManager

    /// 性能监控
    let performanceMonitor: PerformanceMonitor

    /// 智能通知
    let notificationManager: SmartNotificationManager

    /// 更新检查
    let updateManager: UpdateManager

    /// 协作管理
    let collaborationManager: CollaborationManager

    /// 设备同步
    let deviceSyncManager: DeviceSyncManager

    /// 本地通知管理器（UserNotifications 执行层）
    let localNotificationManager: LocalNotificationManager

    /// 状态通知管理器（UI 内全局连接状态条）
    let statusNotificationManager: StatusNotificationManager

    /// 聊天数据仓库（文件持久化备份/导出/导入）
    let chatRepository: ChatRepository

    /// 数据分析管理器（聚合使用统计与洞察）
    let dataInsightsManager: DataInsightsManager

    /// P3-5: 待处理的快捷方式导航目标。
    ///
    /// 由 `IntentRouter` 在收到 App Intent 通知时设置，
    /// 由 `ContentView` 观察并消费（导航后清空）。
    ///
    /// - Note: 值为 `nil` 时表示无待处理请求；非 `nil` 时 ContentView 应立即导航并置回 `nil`
    var pendingShortcutDestination: AppShortcutDestination?

    /// F27: 待打开的会话 ID — 由外部 URL Scheme（`agentcontrolcenter://open-session?sessionId=xxx`）
    /// 触发，由 `ContentView` 观察并消费（打开会话后清空）。
    var pendingOpenSessionId: String?

    /// 命令面板显示绑定。
    ///
    /// 由 App 级 `.commands { CommandMenu }` 中的 ⌘K 快捷键触发，
    /// 由 `ContentView` 观察并通过 `.sheet` 弹出 `CommandPaletteView`。
    /// 这样比之前在 ContentView 内部用隐藏 Button + keyboardShortcut 更符合 HIG：
    /// - 系统菜单栏会显示快捷键
    /// - VoiceOver 可访问
    /// - 不依赖透明零尺寸按钮 hack
    var showCommandPalette: Bool = false

    /// 云备份/恢复管理器（P3-3）
    ///
    /// 与 `chatRepository` 的区别：
    /// - `chatRepository` 是单会话级别的 JSON 导出/导入（`exportSession` / `importSession`）
    /// - `backupManager` 是应用级完整快照（sessions + messages + agentConfigs + settings），
    ///   并支持基于 `KeychainManager` 的硬件级加密备份
    let backupManager: BackupManager

    /// 隐私优先的本地埋点管理器（P3-1，对应 Android AnalyticsManager）
    ///
    /// 纯本地埋点，不集成第三方 SDK。事件写入内存 ring buffer（最多 1000 条），
    /// 可通过设置页导出为 JSON。用户可在设置页关闭埋点。
    let analyticsManager: AnalyticsManager

    /// Feature Flag 管理器（P3-2，对应 Android FeatureFlagManager）
    ///
    /// 为每个功能标志提供默认值（开发中的功能默认关闭），
    /// 支持用户通过设置页覆盖默认值，覆盖持久化到 UserDefaults。
    let featureFlagManager: FeatureFlagManager

    /// 配置辅助：统一偏好仓库（对应 Android ConfigRepository）
    ///
    /// 包装 UserDefaults + Keychain（敏感字段 passphrase）。逐步收敛散落在各视图的
    /// `@AppStorage`，提供 `clearAllPreferences()` / `allPreferenceKeys()` 等统一入口。
    /// 详见 `Preferences/AppPreferences.swift`。
    let preferences: DefaultAppPreferences

    /// 最近一次配置校验错误（供 UI 读取并回填表单）。
    ///
    /// 由 `AgentConfigValidator` / `McpServerValidator` 在保存入口校验失败时写入；
    /// UI（如 `AgentFormSheet` / `AddMcpServerSheet` / `SetupWizardView`）读取后展示。
    /// `nil` 表示无错误或错误已被消费。
    var lastValidationError: ConfigValidationResult?

    // MARK: - 活动 Transport 跟踪与 E2E 密钥热更新
    // 对应 Android ConnectionRepository（@Singleton）：
    // - 持有当前活动 transport 的弱引用
    // - 订阅 SecurityConfig 变更（iOS 通过 @Observable + withObservationTracking）
    // - effectiveE2eKey 变更时调用 transport.updateE2eKey 热更新密钥
    // - 缓存 lastE2eKey 供网络恢复重连使用

    /// 当前活动 transport 的弱引用（由 ChatView / CompareView / WorkflowEngine 注册）。
    /// 用 weak 避免 AppState 强持有导致 transport 在视图退出后无法释放。
    /// `@ObservationIgnored`：不参与 @Observable 发布追踪（内部状态，不驱动 UI）。
    @ObservationIgnored private weak var activeTransport: AgentTransport?

    /// 缓存的最近一次有效 E2E 密钥（供网络恢复重连使用）。
    /// 与 Android ConnectionRepository.lastE2eKey 对齐。
    /// `@ObservationIgnored`：不参与 @Observable 发布追踪。
    @ObservationIgnored private var lastE2eKey: String?

    /// E2E 密钥变更观察任务（持续监听 preferences.configuration.security 变更）。
    /// `@ObservationIgnored`：不参与 @Observable 发布追踪。
    @ObservationIgnored private var e2eKeyObservationTask: Task<Void, Never>?

    /// 创建应用状态并初始化全部依赖
    init() {
        dataController = DataController()
        agentManager = AgentManager()
        sessionManager = SessionManager()
        taskManager = TaskManager()
        // P3-2 / 配置辅助：FeatureFlag 与统一偏好仓库需在 WorkflowEngine / McpBridge 之前
        // 初始化，以便注入（对齐 Android Hilt 中 FeatureFlagManager / ConfigRepository 单例）。
        featureFlagManager = FeatureFlagManager()
        // 配置辅助：统一偏好仓库（包装 UserDefaults + Keychain）
        preferences = DefaultAppPreferences()
        // C9 修复：注入 dataController，让 WorkflowEngine 能解析 AGENT 节点配置。
        // 项1：注入 featureFlagManager 用于 execute() 入口的 FeatureFlag gate。
        // 项2：注入 preferences 用于 AGENT 节点 config 的 model 兜底
        //      （对齐 AppState.resolveWithDefaults，避免循环引用故不直接持有 AppState）。
        workflowEngine = WorkflowEngine(
            dataController: dataController,
            featureFlagManager: featureFlagManager,
            preferences: preferences
        )
        // 项1：注入 featureFlagManager 用于 connectServer() 入口的 FeatureFlag gate。
        mcpBridge = McpBridge(featureFlagManager: featureFlagManager)
        pluginExecutor = PluginExecutor()
        voiceInputManager = VoiceInputManager()
        voiceChatManager = VoiceChatManager()
        marketplaceClient = MarketplaceClient()
        localModelManager = LocalModelManager()
        performanceMonitor = PerformanceMonitor()
        notificationManager = SmartNotificationManager()
        updateManager = UpdateManager()
        collaborationManager = CollaborationManager()
        deviceSyncManager = DeviceSyncManager()
        localNotificationManager = LocalNotificationManager()
        statusNotificationManager = StatusNotificationManager()
        chatRepository = ChatRepository()
        // DataInsightsManager 依赖 chatRepository / sessionManager / dataController
        dataInsightsManager = DataInsightsManager(
            chatRepository: chatRepository,
            sessionManager: sessionManager,
            dataController: dataController
        )
        // BackupManager 依赖 dataController
        backupManager = BackupManager(dataController: dataController)
        // P3-1 / P3-2: 埋点系统与 Feature Flag 系统
        analyticsManager = AnalyticsManager()
        // 启动 E2E 密钥变更观察（对齐 Android ConnectionRepository.init 中订阅 security Flow）
        // 注意：此时 preferences 已完成初始化，可安全读取 configuration.security。
        startE2eKeyObservation()
    }

    // MARK: - 活动 Transport 注册（供 ChatView / CompareView / WorkflowEngine 调用）

    /// 注册活动 transport，使 AppState 能在 e2eKey 变更时调用其 updateE2eKey。
    ///
    /// 与 Android ConnectionRepository 持有 activeTransport 对齐：
    /// - 由 ChatView.setupTransport() 等持有 transport 的视图在创建后调用
    /// - 视图退出时传 nil 注销（或依赖 weak 引用在 transport 释放后自动 nil）
    /// - 注册时立即同步当前 effectiveE2eKey，避免新 transport 使用旧密钥直到下次配置变更
    func registerActiveTransport(_ transport: AgentTransport?) {
        self.activeTransport = transport
        // 立即同步当前 effectiveE2eKey 到新 transport，并更新 lastE2eKey 缓存。
        // 注意：transport.connect() 也会设置 e2eKey，但两边的 e2eKey 来源一致
        // （均为 preferences.configuration.security.effectiveE2eKey），不会冲突。
        if let transport {
            let key = preferences.configuration.security.effectiveE2eKey
            transport.updateE2eKey(key)
            lastE2eKey = key
        }
    }

    // MARK: - AgentDefaults 兜底（项2，对齐 Android ConnectionRepository.connect）

    /// 用当前 AgentDefaults 兜底解析 config：仅当 model 为空（或仅空白）时回退 defaultModel。
    ///
    /// 策略（对齐 Android）：**不覆盖已有 Agent 的显式配置**，Defaults 仅作兜底。
    /// 对应 Android `ConnectionRepository.connect(config)` 中
    /// `if (config.model.isBlank()) config = config.copy(model = currentDefaults.defaultModel)`。
    ///
    /// 调用点：
    /// - `ChatView.setupTransport`：用户主聊天连接路径
    /// - `WorkflowEngine.executeAgent`：工作流 AGENT 节点（WorkflowEngine 不持有 AppState，
    ///   改为注入 `preferences` 并内联同样逻辑，见 `WorkflowEngine.resolveWithDefaults`）
    func resolveWithDefaults(_ config: AgentConfig) -> AgentConfig {
        let defaults = preferences.configuration.agentDefaults
        // Swift 无 isBlank，用 trimmingCharacters(in: .whitespaces).isEmpty 等价
        if config.model.trimmingCharacters(in: .whitespaces).isEmpty {
            // AgentConfig 为 struct 且 model 为 var，直接构造副本避免引入 copy(model:)
            var resolved = config
            resolved.model = defaults.defaultModel
            return resolved
        }
        return config
    }

    // MARK: - E2E 密钥变更观察

    /// 启动 E2E 密钥变更观察循环，对齐 Android ConnectionRepository 在 init 中订阅
    /// `security` Flow 并用 `distinctUntilChanged` 过滤 effectiveE2eKey 的行为。
    ///
    /// 实现机制（iOS @Observable 无内置 Combine publisher，改用 withObservationTracking）：
    /// 1. 立即应用一次当前 effectiveE2eKey（init 时 preferences 已加载）
    /// 2. 启动 Task 循环：await waitForE2eKeyChange() → applyLatestE2eKey()
    /// 3. waitForE2eKeyChange() 用 withObservationTracking 注册对
    ///    `preferences.configuration.security.effectiveE2eKey` 的观察，
    ///    onChange 触发时 resume continuation，循环继续
    ///
    /// 与 Android `distinctUntilChanged` 语义对齐：applyLatestE2eKey 内部比较
    /// newKey != lastE2eKey，仅变更时才调用 transport.updateE2eKey（去抖）。
    private func startE2eKeyObservation() {
        // 立即应用一次当前值（init 时 preferences.configuration 已就绪）
        applyLatestE2eKey()
        // 启动观察 Task（@MainActor 隔离，与 AppState 同线程）
        e2eKeyObservationTask = Task { @MainActor [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                await self.waitForE2eKeyChange()
                self.applyLatestE2eKey()
            }
        }
    }

    /// 桥接 withObservationTracking 的同步 onChange 到 async 等待。
    ///
    /// 在 onChange 触发时 resume continuation，使调用方 await 返回。
    /// 注意：onChange 是 `@autoclosure () -> @Sendable () -> Void`，调用方需提供一个
    /// `@Sendable () -> Void` 闭包表达式。CheckedContinuation.resume() 是线程安全的，
    /// 且 CheckedContinuation<Void, Never> 本身是 Sendable，可安全跨 actor 捕获。
    private func waitForE2eKeyChange() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            withObservationTracking {
                // 读取 effectiveE2eKey，触发 @Observable 追踪其依赖属性
                // （e2eEncryptionEnabled / e2eKey）。
                _ = preferences.configuration.security.effectiveE2eKey
            } onChange: {
                // onChange 的 autoclosure 求值得到此闭包（@Sendable () -> Void），
                // 框架在被观察属性下次变更时调用它一次，resume continuation 使 await 返回。
                continuation.resume()
            }
        }
    }

    /// 应用最新 effectiveE2eKey 到活动 transport。
    ///
    /// 与 Android `distinctUntilChanged` 对齐：仅当 newKey != lastE2eKey 时才调用
    /// transport.updateE2eKey，避免无变化的冗余调用。
    /// 同时更新 lastE2eKey 缓存（供网络恢复重连时复用）。
    private func applyLatestE2eKey() {
        let newKey = preferences.configuration.security.effectiveE2eKey
        guard newKey != lastE2eKey else { return }
        lastE2eKey = newKey
        activeTransport?.updateE2eKey(newKey)
    }
}
