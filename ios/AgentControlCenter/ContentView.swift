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

    /// 主题偏好（rawValue 与 `SettingsView.AppThemePreference` 一致）
    @AppStorage("theme") private var themeRaw = AppTheme.ThemePreference.system.rawValue

    /// regular 模式下选中的侧边栏项（nil 表示未选中）
    @State private var selectedTab: SidebarTab? = .sessions

    /// compact 模式下选中的底部 Tab
    @State private var selectedCompactTab: CompactTab = .sessions

    // MARK: - 侧边栏标签

    /// 侧边栏所有可导航页面
    enum SidebarTab: String, CaseIterable, Identifiable {
        case sessions, agents, tasks, workflow, plugins, compare, mcp, activity, insights, settings

        var id: String { rawValue }

        /// 中文显示名
        var displayName: String {
            switch self {
            case .sessions: "会话"
            case .agents:   "Agent"
            case .tasks:    "任务"
            case .workflow: "工作流"
            case .plugins:  "插件"
            case .compare:  "对比"
            case .mcp:      "MCP"
            case .activity: "活动"
            case .insights: "洞察"
            case .settings: "设置"
            }
        }

        /// SF Symbol 图标名
        var systemImage: String {
            switch self {
            case .sessions: "bubble.left.and.bubble.right"
            case .agents:   "cpu"
            case .tasks:    "checklist"
            case .workflow: "arrow.triangle.branch"
            case .plugins:  "puzzlepiece"
            case .compare:  "arrow.left.arrow.right"
            case .mcp:      "network"
            case .activity: "clock"
            case .insights: "chart.bar"
            case .settings: "gear"
            }
        }

        /// 所属分组
        var section: SidebarSection {
            switch self {
            case .sessions, .agents, .tasks:            .main
            case .workflow, .plugins, .compare, .mcp:   .tools
            case .activity, .insights:                  .data
            case .settings:                             .system
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
            if sizeClass == .regular {
                splitView
            } else {
                compactView
            }
        }
        .preferredColorScheme(preferredColorScheme)
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
        case .sessions:  SessionsView()
        case .agents:    AgentsView()
        case .tasks:     TasksView()
        case .workflow:  WorkflowView()
        case .plugins:   PluginView()
        case .compare:   CompareView()
        case .mcp:       McpView()
        case .activity:  ActivityView()
        case .insights:  InsightsView()
        case .settings:  SettingsView()
        case .none:
            ContentUnavailableView(
                "选择一个页面",
                systemImage: "sidebar.left",
                description: Text("从侧边栏选择要查看的内容")
            )
        }
    }
}
