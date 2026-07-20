import SwiftUI

/// 应用根视图
/// 使用 NavigationSplitView 提供侧边栏 + 详情区的双栏布局
struct ContentView: View {
    // 通过环境获取全局 AppState(@Observable 模型)
    @Environment(AppState.self) private var appState
    // 当前选中的侧边栏标签
    @State private var selectedTab: SidebarTab = .sessions

    /// 侧边栏标签枚举:会话 / Agent / 任务 / MCP / 设置
    enum SidebarTab: String, CaseIterable, Identifiable {
        case sessions = "会话"
        case agents = "Agent"
        case tasks = "任务"
        case mcp = "MCP"
        case settings = "设置"
        var id: String { rawValue }

        // 每个标签对应的 SF Symbol 图标
        var systemImage: String {
            switch self {
            case .sessions: "bubble.left.and.bubble.right"
            case .agents: "cpu"
            case .tasks: "checklist"
            case .mcp: "network"
            case .settings: "gear"
            }
        }
    }

    var body: some View {
        NavigationSplitView {
            // 侧边栏:标签列表
            List(SidebarTab.allCases, selection: $selectedTab) { tab in
                Label(tab.rawValue, systemImage: tab.systemImage)
                    .tag(tab)
            }
            .navigationTitle("Agent Control Center")
        } detail: {
            // 详情区:根据选中标签切换对应视图
            switch selectedTab {
            case .sessions: SessionsView()
            case .agents: AgentsView()
            case .tasks: TasksView()
            case .mcp: McpView()
            case .settings: SettingsView()
            }
        }
    }
}
