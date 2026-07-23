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

    /// 主题偏好（rawValue 与 `AppTheme.ThemePreference` 一致）
    @AppStorage("theme") private var themeRaw = AppTheme.ThemePreference.system.rawValue

    /// regular 模式下选中的侧边栏项（nil 表示未选中）
    @State private var selectedTab: SidebarTab? = .sessions

    /// compact 模式下选中的底部 Tab
    @State private var selectedCompactTab: CompactTab = .sessions

    /// compact 模式「更多」Tab 内 NavigationStack 的路径。
    /// 路径栈顶即为当前展示的「更多」页面，切到 regular 时据此同步 selectedTab。
    @State private var moreNavigationPath: [SidebarTab] = []

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
            case .sessions:   String(localized: "tab.sessions")
            case .agents:     String(localized: "tab.agents")
            case .tasks:      String(localized: "tab.tasks")
            case .marketplace:String(localized: "tab.marketplace")
            case .workflow:   String(localized: "tab.workflow")
            case .plugins:    String(localized: "tab.plugins")
            case .compare:    String(localized: "tab.compare")
            case .mcp:        String(localized: "tab.mcp")
            case .voiceChat:  String(localized: "tab.voiceChat")
            case .activity:   String(localized: "tab.activity")
            case .insights:   String(localized: "tab.insights")
            case .deviceSync: String(localized: "tab.deviceSync")
            case .settings:   String(localized: "tab.settings")
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

    /// compact 模式下底部 Tab。
    /// v5.0 P0：新增 `.more` Tab，承载 Marketplace / Workflow / Plugins / MCP /
    /// Compare / VoiceChat / Activity / Insights / DeviceSync 共 9 个页面，
    /// 解决 iPhone 既无 Tab 也无 Settings 入口的可达性问题。
    enum CompactTab: String, CaseIterable, Identifiable {
        case sessions, agents, tasks, settings, more

        var id: String { rawValue }
    }

    // MARK: - 侧边栏分组

    /// 侧边栏分组（用于 Section 标题）
    enum SidebarSection: String, CaseIterable {
        case main = "主功能"
        case tools = "工具"
        case data = "数据"
        case system = "系统"

        /// 本地化分组标题（rawValue 保留为内部标识，UI 展示统一走本地化）
        var displayName: String {
            switch self {
            case .main:   String(localized: "sidebar.section.main")
            case .tools:  String(localized: "sidebar.section.tools")
            case .data:   String(localized: "sidebar.section.data")
            case .system: String(localized: "sidebar.section.system")
            }
        }
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
                // 首次启动：显示配置向导（替代纯介绍式 OnboardingView）
                // SetupWizardView.finish() 内部已通过 appState.preferences.update
                // 标记 onboarding.completed = true（写入 UserDefaults "onboarding_completed"），
                // ContentView 的 @AppStorage("onboarding_completed") 会自动感知并切换视图，
                // 因此 onComplete 仅做动画过渡（双保险也设置一次本地 @AppStorage）。
                SetupWizardView {
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
        // 命令面板 Sheet
        // HIG (iOS 26 Liquid Glass)：sheet 内容使用玻璃采样下层视图，
        // 必须将 sheet 背景设为 .clear，否则系统默认 material 会遮挡玻璃 lensing
        // HIG：⌘K 快捷键已移到 App 级 .commands { CommandMenu }，
        // 此处通过 appState.showCommandPalette 双向绑定，避免隐藏按钮 hack
        .sheet(isPresented: Binding(
            get: { appState.showCommandPalette },
            set: { appState.showCommandPalette = $0 }
        )) {
            CommandPaletteView()
                .presentationDetents([.medium, .large])
                .presentationBackgroundInteraction(.enabled)
                .presentationDragIndicator(.visible)
                .presentationBackground(.clear)
        }
        // 首次启动注册命令面板的运行时动作
        .task { registerCommandActions() }
        // P3-5: 观察 App Intent / 快捷方式触发的导航请求
        .onChange(of: appState.pendingShortcutDestination) { _, destination in
            handleShortcutDestination(destination)
        }
        // HIG：顶层 Tab / 侧边栏导航切换不应触发触觉反馈。
        // selection 触觉应保留给 Picker / Segmented Control 等真"选择"控件，
        // 否则用户会习惯性忽略触觉信号。
        // （已移除 .onChange { HapticFeedback.selection() }）
        // P3-5: 应用启动时检查是否有冷启动期间暂存的快捷方式目标
        .task {
            if let destination = appState.pendingShortcutDestination {
                handleShortcutDestination(destination)
            }
        }
        // v5.0 P0: regular → compact 状态同步。
        // iPad sidebar 选中「更多」9 页之一时（selectedTab 已变化但 compact 状态未跟上），
        // 同步切换 compact 底部 Tab 到 .more 并把路径推到对应页，保证切到 iPhone 后保持选中。
        // 反向（compact → regular）由 moreMenuView 的 onChange(of: moreNavigationPath)
        // 和 compactView 的 onChange(of: selectedCompactTab) 已覆盖。
        .onChange(of: selectedTab) { _, newTab in
            guard let tab = newTab else { return }
            switch tab {
            case .sessions, .agents, .tasks, .settings:
                // 4 个主 Tab：若 compact 当前不在该 Tab，则切过去（保证 size class 切换后选中一致）
                if let compact = CompactTab(rawValue: tab.rawValue),
                   compact != .more, selectedCompactTab != compact {
                    selectedCompactTab = compact
                }
            default:
                // 9 个「更多」页面：切到 .more Tab 并把路径推到该页（若尚未在该页）
                if selectedCompactTab != .more {
                    selectedCompactTab = .more
                }
                if moreNavigationPath.last != tab {
                    moreNavigationPath = [tab]
                }
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
            ("tools.command_palette", { appState.showCommandPalette = true }),
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
        // compact 模式：sessions/agents/tasks/settings 直接切到底部 Tab；
        // 其余 9 个「更多」页面切到 .more Tab 并 push 到对应 Detail。
        switch tab {
        case .sessions: selectedCompactTab = .sessions
        case .agents:   selectedCompactTab = .agents
        case .tasks:    selectedCompactTab = .tasks
        case .settings: selectedCompactTab = .settings
        default:
            selectedCompactTab = .more
            // 替换路径栈，避免重复 push 同一页面
            if moreNavigationPath.last != tab {
                moreNavigationPath = [tab]
            }
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
    /// 浮动药丸形态：iOS 26 液态玻璃 + 状态色 tint。
    /// - .connecting: 橙色 tint + 旋转图标
    /// - .connected:  绿色 tint + 勾选图标
    /// - .error:      红色 tint + 警告图标
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
        // HIG (iOS 26 Liquid Glass)：移除不透明色块背景，改为玻璃 tint；
        // 白色文字在 tint 玻璃上保持高对比，Dark Mode 下玻璃会自适应采样的暗背景
        // R4: 改用 glassTinted 包装，内部 if #available(iOS 26, *) 守卫，
        // iOS 18 走 ultraThinMaterial + tint 色块叠加回退
        .glassTinted(
            statusBarColor(for: status).opacity(0.6),
            in: GlassTokens.pillShape
        )
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

    /// compact 尺寸：底部 TabView，5 个 Tab（会话 / Agent / 任务 / 设置 / 更多）。
    /// 「更多」Tab 内 NavigationStack 列表承载 Marketplace / Workflow / Plugins /
    /// MCP / Compare / VoiceChat / Activity / Insights / DeviceSync 共 9 个页面。
    private var compactView: some View {
        TabView(selection: $selectedCompactTab) {
            SessionsView()
                .tabItem { Label(String(localized: "tab.sessions"), systemImage: "bubble.left.and.bubble.right") }
                .tag(CompactTab.sessions)

            AgentsView()
                .tabItem { Label(String(localized: "tab.agents"), systemImage: "cpu") }
                .tag(CompactTab.agents)

            TasksView()
                .tabItem { Label(String(localized: "tab.tasks"), systemImage: "checklist") }
                .tag(CompactTab.tasks)

            SettingsView()
                .tabItem { Label(String(localized: "tab.settings"), systemImage: "gear") }
                .tag(CompactTab.settings)

            moreMenuView
                .tabItem { Label(String(localized: "tab.more"), systemImage: "ellipsis.circle") }
                .tag(CompactTab.more)
        }
        // compact ↔ regular 状态同步：切到非 .more Tab 时同步 selectedTab；
        // 切到 .more 时不主动改 selectedTab，由 moreNavigationPath 变化驱动同步。
        .onChange(of: selectedCompactTab) { _, newTab in
            switch newTab {
            case .sessions: selectedTab = .sessions
            case .agents:   selectedTab = .agents
            case .tasks:    selectedTab = .tasks
            case .settings: selectedTab = .settings
            case .more:     break
            }
        }
    }

    /// 「更多」Tab 内的菜单视图：按 Section 分组列出 9 个二级页面，
    /// NavigationLink push 到 `detailView(for:)`。
    /// 路径变化时同步 `selectedTab`，便于切回 iPad 时保持选中。
    private var moreMenuView: some View {
        NavigationStack(path: $moreNavigationPath) {
            List {
                ForEach(SidebarSection.allCases, id: \.self) { section in
                    let tabsInSection = moreTabs.filter { $0.section == section }
                    if !tabsInSection.isEmpty {
                        Section(section.displayName) {
                            ForEach(tabsInSection) { tab in
                                NavigationLink(value: tab) {
                                    Label(tab.displayName, systemImage: tab.systemImage)
                                }
                                .tint(AppTheme.primaryColor)
                            }
                        }
                    }
                }
            }
            .navigationTitle(String(localized: "tab.more"))
            .navigationDestination(for: SidebarTab.self) { tab in
                detailView(for: tab)
                    .navigationTitle(tab.displayName)
                    .navigationBarTitleDisplayMode(.inline)
            }
        }
        // 路径栈顶变化 → 同步 selectedTab（用于切回 iPad 时保持选中页）
        // 回到更多菜单根（路径空）时保持原 selectedTab，避免切回 iPad 时 sidebar
        // 显示「选择一个页面」占位
        .onChange(of: moreNavigationPath) { _, newPath in
            if let top = newPath.last {
                selectedTab = top
            }
        }
    }

    /// compact 模式「更多」Tab 中展示的 9 个页面（排除已有独立 Tab 的 sessions /
    /// agents / tasks / settings）。
    private var moreTabs: [SidebarTab] {
        SidebarTab.allCases.filter { tab in
            switch tab {
            case .sessions, .agents, .tasks, .settings:
                return false
            default:
                return true
            }
        }
    }

    // MARK: - 侧边栏

    /// 按 Section 分组的侧边栏列表；选中项以品牌色 tint 高亮
    private var sidebar: some View {
        List(selection: $selectedTab) {
            ForEach(SidebarSection.allCases, id: \.self) { section in
                Section(section.displayName) {
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
                String(localized: "sidebar.select_page"),
                systemImage: "sidebar.left",
                description: Text(String(localized: "sidebar.select_page.description"))
            )
        }
    }
}
