import SwiftUI

/// 应用根视图
///
/// 根据水平尺寸类自适应布局：
/// - compact（iPhone 竖屏等）：使用 `TabView`，底部仅展示 4 个主要 Tab
///   （会话 / Agent / 任务 / 设置），其余功能入口收敛到设置页。
/// - regular（iPad / iPhone 横屏等）：使用 `NavigationSplitView`，
///   侧边栏按分组（主功能 / 工具 / 数据 / 系统）组织全部页面。
///
/// 主题：通过 `@AppStorage("theme")` 读取用户主题偏好，在根视图统一应用
/// `.preferredColorScheme`，与 `SettingsView` 共享同一份存储。
struct ContentView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// 是否显示首次启动引导
    @AppStorage("onboarding_completed") private var onboardingCompleted = false

    /// 主题偏好（rawValue 与 `SettingsView.AppThemePreference` 一致）
    @AppStorage("theme") private var themeRaw = AppTheme.ThemePreference.system.rawValue

    /// regular 模式下选中的侧边栏项（nil 表示未选中）
    @State private var selectedTab: SidebarTab? = .sessions

    /// compact 模式下选中的底部 Tab
    @State private var selectedCompactTab: CompactTab = .sessions

    /// 是否显示命令面板（⌘K 触发）
    @State private var showingCommandPalette: Bool = false

    /// 命令面板管理器（共享实例，便于注册运行时命令）
    private var commandPaletteManager: CommandPaletteManager {
        CommandPaletteManager.shared
    }

    // MARK: - 侧边栏标签

    /// 侧边栏所有可导航页面
    enum SidebarTab: String, CaseIterable, Identifiable {
        case sessions, agents, tasks, marketplace, workflow, plugins, compare, mcp, voiceChat, activity, insights, deviceSync, settings

        var id: String { rawValue }

        /// 中文显示名
        var displayName: String {
            switch self {
            case .sessions:   "会话"
            case .agents:     "Agent"
            case .tasks:      "任务"
            case .marketplace:"市场"
            case .workflow:   "工作流"
            case .plugins:    "插件"
            case .compare:    "对比"
            case .mcp:        "MCP"
            case .voiceChat:  "语音消息"
            case .activity:   "活动"
            case .insights:   "洞察"
            case .deviceSync: "设备同步"
            case .settings:   "设置"
            }
        }

        /// SF Symbol 图标名
        var systemImage: String {
            switch self {
            case .sessions:   "bubble.left.and.bubble.right"
            case .agents:     "cpu"
            case .tasks:      "checklist"
            case .marketplace:"storefront"
            case .workflow:   "arrow.triangle.branch"
            case .plugins:    "puzzlepiece"
            case .compare:    "arrow.left.arrow.right"
            case .mcp:        "network"
            case .voiceChat:  "waveform"
            case .activity:   "clock"
            case .insights:   "chart.bar"
            case .deviceSync: "arrow.triangle.2.circlepath"
            case .settings:   "gear"
            }
        }

        /// 所属分组
        var section: SidebarSection {
            switch self {
            case .sessions, .agents, .tasks:                .main
            case .marketplace, .voiceChat, .workflow, .plugins, .compare, .mcp:   .tools
            case .activity, .insights:                      .data
            case .deviceSync, .settings:                    .system
            }
        }
    }

    // MARK: - 紧凑模式 Tab

    /// compact 模式下底部 Tab（仅 4 个主要入口，其余通过设置页进入）
    enum CompactTab: String, CaseIterable, Identifiable {
        case sessions, agents, tasks, settings

        var id: String { rawValue }
    }

    // MARK: - 侧边栏分组

    /// 侧边栏分组（用于 Section 标题）
    enum SidebarSection: String, CaseIterable {
        case main = "主功能"
        case tools = "工具"
        case data = "数据"
        case system = "系统"
    }

    // MARK: - 主题映射

    /// 将存储中的主题 rawValue 映射为 `ColorScheme`（nil 表示跟随系统）
    var preferredColorScheme: ColorScheme? {
        AppTheme.ThemePreference(rawValue: themeRaw)?.colorScheme
    }

    // MARK: - Body

    var body: some View {
        Group {
            if !onboardingCompleted {
                // 首次启动：显示引导页面
                OnboardingView {
                    withAnimation { onboardingCompleted = true }
                }
            } else if sizeClass == .regular {
                splitView
            } else {
                compactView
            }
        }
        // 全局连接状态条：仅在状态非 idle 时显示
        .safeAreaInset(edge: .top, spacing: 0) {
            if appState.statusNotificationManager.currentStatus != .idle {
                statusBar
            }
        }
        .animation(.easeInOut(duration: 0.25), value: appState.statusNotificationManager.currentStatus)
        .preferredColorScheme(preferredColorScheme)
        // 命令面板触发：隐藏的 ⌘K 快捷键按钮
        .background(
            Button("") { showingCommandPalette = true }
                .keyboardShortcut("k", modifiers: .command)
                .opacity(0)
                .frame(width: 0, height: 0)
                .accessibilityHidden(true)
        )
        // 命令面板 Sheet
        .sheet(isPresented: $showingCommandPalette) {
            CommandPaletteView()
                .presentationDetents([.medium, .large])
                .presentationBackgroundInteraction(.enabled)
                .presentationDragIndicator(.visible)
        }
        // 首次启动注册命令面板的运行时动作
        .task { registerCommandActions() }
        // P3-5: 观察 App Intent / 快捷方式触发的导航请求
        .onChange(of: appState.pendingShortcutDestination) { _, destination in
            handleShortcutDestination(destination)
        }
        // U1: Tab 切换时触发选择反馈
        .onChange(of: selectedTab) { _, _ in
            HapticFeedback.selection()
        }
        .onChange(of: selectedCompactTab) { _, _ in
            HapticFeedback.selection()
        }
        // P3-5: 应用启动时检查是否有冷启动期间暂存的快捷方式目标
        .task {
            if let destination = appState.pendingShortcutDestination {
                handleShortcutDestination(destination)
            }
        }
    }

    // MARK: - P3-5: 快捷方式导航

    /// 处理来自 App Intent / Siri 的快捷方式导航请求。
    ///
    /// 将 [AppShortcutDestination] 映射到 [SidebarTab] 并调用 [navigate(to:)]，
    /// 导航完成后清空 `appState.pendingShortcutDestination` 防止重复触发。
    ///
    /// 映射关系：
    /// - `.newChat`  → `.sessions`（会话页面，即聊天入口）
    /// - `.newAgent` → `.agents`（Agent 页面）
    /// - `.settings` → `.settings`（设置页面）
    ///
    /// - Parameter destination: 快捷方式导航目标，`nil` 时无操作
    private func handleShortcutDestination(_ destination: AppShortcutDestination?) {
        guard let destination else { return }
        let targetTab: SidebarTab
        switch destination {
        case .newChat:
            targetTab = .sessions
        case .newAgent:
            targetTab = .agents
        case .settings:
            targetTab = .settings
        }
        navigate(to: targetTab)
        // 消费完毕，清空待处理目标
        appState.pendingShortcutDestination = nil
    }

    // MARK: - 命令面板动作注册

    /// 将命令面板中静态注册的命令绑定到运行时导航动作
    ///
    /// 由于 `CommandPaletteManager.shared` 是单例，在 `init` 时无法访问 `selectedTab`
    /// 等运行时状态，因此命令的 `action` 闭包留空，由本方法在视图启动时统一绑定。
    /// 命令导航支持：
    /// - regular（iPad）：直接设置 `selectedTab`
    /// - compact（iPhone）：若目标存在对应 `CompactTab`，则同时切换 `selectedCompactTab`
    private func registerCommandActions() {
        let manager = commandPaletteManager
        let actions: [(String, () -> Void)] = [
            ("nav.sessions",        { navigate(to: .sessions) }),
            ("nav.agents",          { navigate(to: .agents) }),
            ("nav.tasks",           { navigate(to: .tasks) }),
            ("nav.marketplace",     { navigate(to: .marketplace) }),
            ("nav.voicechat",       { navigate(to: .voiceChat) }),
            ("nav.settings",       { navigate(to: .settings) }),
            ("action.new_session",  {
                let session = appState.sessionManager.createSession()
                appState.dataController.saveSession(session)
                navigate(to: .sessions)
            }),
            ("action.add_agent",    {
                navigate(to: .agents)
            }),
            ("action.toggle_theme", { toggleTheme() }),
            ("tools.command_palette", { showingCommandPalette = true }),
            ("tools.refresh_market", {
                Task { await appState.marketplaceClient.loadAgents() }
                navigate(to: .marketplace)
            })
        ]

        for (id, action) in actions {
            if let cmd = manager.command(byId: id) {
                let bound = CommandItem(
                    id: cmd.id,
                    systemImage: cmd.systemImage,
                    title: cmd.title,
                    subtitle: cmd.subtitle,
                    keywords: cmd.keywords,
                    shortcut: cmd.shortcut,
                    isQuickAction: cmd.isQuickAction,
                    category: cmd.category,
                    action: action
                )
                manager.register(bound)
            }
        }
    }

    /// 通用导航：同时设置 regular / compact 选中项
    /// - Parameter tab: 目标侧边栏项
    private func navigate(to tab: SidebarTab) {
        selectedTab = tab
        // 若 compact 模式有对应 Tab，则同步切换
        switch tab {
        case .sessions: selectedCompactTab = .sessions
        case .agents:   selectedCompactTab = .agents
        case .tasks:    selectedCompactTab = .tasks
        case .settings: selectedCompactTab = .settings
        default: break
        }
    }

    /// 切换主题（在浅色/深色/跟随系统间循环）
    private func toggleTheme() {
        let order: [AppTheme.ThemePreference] = [.light, .dark, .system]
        let current = AppTheme.ThemePreference(rawValue: themeRaw) ?? .system
        guard let idx = order.firstIndex(of: current) else { return }
        let next = order[(idx + 1) % order.count]
        themeRaw = next.rawValue
    }

    // MARK: - 全局状态条

    /// 顶部连接状态条，展示 StatusNotificationManager 的当前状态。
    ///
    /// - .connecting: 橙色背景 + 旋转图标
    /// - .connected:  绿色背景 + 勾选图标
    /// - .error:      红色背景 + 警告图标
    /// - .idle:       不显示
    private var statusBar: some View {
        let status = appState.statusNotificationManager.currentStatus
        let message = appState.statusNotificationManager.statusMessage

        return HStack(spacing: AppTheme.Spacing.sm) {
            Image(systemName: status.systemImage)
                .font(.caption)
                .foregroundStyle(.white)
                .rotationEffect(.degrees(status == .connecting ? 360 : 0))
                .animation(
                    status == .connecting
                        ? .linear(duration: 1).repeatForever(autoreverses: false)
                        : .default,
                    value: status
                )

            Text(status.displayName)
                .font(.caption)
                .fontWeight(.medium)
                .foregroundStyle(.white)

            Text(message)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.85))
                .lineLimit(1)

            Spacer()
        }
        .padding(.horizontal, AppTheme.Spacing.lg)
        .padding(.vertical, AppTheme.Spacing.sm)
        .background(statusBarColor(for: status))
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    /// 根据状态返回对应的背景色
    private func statusBarColor(for status: AppStatus) -> Color {
        switch status {
        case .idle:       return AppTheme.secondaryTextColor
        case .connecting: return AppTheme.warningColor
        case .connected:  return AppTheme.successColor
        case .error:      return AppTheme.errorColor
        }
    }

    // MARK: - iPad 双栏视图

    /// regular 尺寸：NavigationSplitView 侧边栏 + 详情
    private var splitView: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            detailView(for: selectedTab)
        }
    }

    // MARK: - iPhone 紧凑视图

    /// compact 尺寸：底部 TabView
    private var compactView: some View {
        TabView(selection: $selectedCompactTab) {
            SessionsView()
                .tabItem { Label("会话", systemImage: "bubble.left.and.bubble.right") }
                .tag(CompactTab.sessions)

            AgentsView()
                .tabItem { Label("Agent", systemImage: "cpu") }
                .tag(CompactTab.agents)

            TasksView()
                .tabItem { Label("任务", systemImage: "checklist") }
                .tag(CompactTab.tasks)

            SettingsView()
                .tabItem { Label("设置", systemImage: "gear") }
                .tag(CompactTab.settings)
        }
    }

    // MARK: - 侧边栏

    /// 按 Section 分组的侧边栏列表；选中项以品牌色 tint 高亮
    private var sidebar: some View {
        List(selection: $selectedTab) {
            ForEach(SidebarSection.allCases, id: \.self) { section in
                Section(section.rawValue) {
                    ForEach(SidebarTab.allCases.filter { $0.section == section }) { tab in
                        Label(tab.displayName, systemImage: tab.systemImage)
                            .tag(tab as SidebarTab?)
                            .tint(AppTheme.primaryColor)
                    }
                }
            }
        }
        .navigationTitle("Agent Control Center")
    }

    // MARK: - 详情路由

    /// 根据选中的侧边栏项返回对应详情视图；nil 时显示引导占位
    @ViewBuilder
    private func detailView(for tab: SidebarTab?) -> some View {
        switch tab {
        case .sessions:    SessionsView()
        case .agents:      AgentsView()
        case .tasks:       TasksView()
        case .marketplace: MarketplaceView()
        case .workflow:    WorkflowView()
        case .plugins:     PluginView()
        case .compare:     CompareView()
        case .mcp:         McpView()
        case .voiceChat:   VoiceChatView()
        case .activity:    ActivityView()
        case .insights:    InsightsView()
        case .deviceSync:  DeviceSyncView()
        case .settings:    SettingsView()
        case .none:
            ContentUnavailableView(
                "选择一个页面",
                systemImage: "sidebar.left",
                description: Text("从侧边栏选择要查看的内容")
            )
        }
    }
}
