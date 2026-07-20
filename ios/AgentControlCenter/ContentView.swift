import SwiftUI

/// 应用根视图
/// 使用 NavigationSplitView 提供侧边栏 + 详情区的双栏布局
struct ContentView: View {
    @Environment(AppState.self) private var appState
    @State private var selectedTab: SidebarTab = .sessions

    /// 侧边栏标签枚举：会话 / Agent / 任务 / 工作流 / 插件 / 对比 / MCP / 活动 / 洞察 / 设置
    enum SidebarTab: String, CaseIterable, Identifiable {
        case sessions = "会话"
        case agents = "Agent"
        case tasks = "任务"
        case workflow = "工作流"
        case plugins = "插件"
        case compare = "对比"
        case mcp = "MCP"
        case activity = "活动"
        case insights = "洞察"
        case settings = "设置"
        var id: String { rawValue }

        var systemImage: String {
            switch self {
            case .sessions: "bubble.left.and.bubble.right"
            case .agents: "cpu"
            case .tasks: "checklist"
            case .workflow: "arrow.triangle.branch"
            case .plugins: "puzzlepiece"
            case .compare: "arrow.left.arrow.right"
            case .mcp: "network"
            case .activity: "clock"
            case .insights: "chart.bar"
            case .settings: "gear"
            }
        }
    }

    var body: some View {
        NavigationSplitView {
            List(SidebarTab.allCases, selection: $selectedTab) { tab in
                Label(tab.rawValue, systemImage: tab.systemImage)
                    .tag(tab)
            }
            .navigationTitle("Agent Control Center")
        } detail: {
            switch selectedTab {
            case .sessions: SessionsView()
            case .agents: AgentsView()
            case .tasks: TasksView()
            case .workflow: WorkflowView()
            case .plugins: PluginView()
            case .compare: CompareView()
            case .mcp: McpView()
            case .activity: ActivityView()
            case .insights: InsightsView()
            case .settings: SettingsView()
            }
        }
    }
}
