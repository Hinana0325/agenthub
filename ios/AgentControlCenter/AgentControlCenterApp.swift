import SwiftUI
import SwiftData

// MARK: - AgentControlCenterApp
// iOS App 入口，对应 Android com.agentcontrolcenter.app.Agent Control CenterApplication + MainActivity

/// 应用入口 — Agent Control Center iOS 应用。
///
/// 职责：
/// - 创建全局 `AppState` 依赖容器（通过 `@State` 持有生命周期）
/// - 将 `AppState` 注入 SwiftUI 环境，子视图通过 `@Environment(AppState.self)` 访问依赖
/// - 将 `DataController.container` 注入 SwiftUI 的 SwiftData 环境，
///   使 `@Query`、`@Environment(\.modelContext)` 等自动使用此容器
/// - 挂载根视图 `ContentView`
@main
struct AgentControlCenterApp: App {

    /// 全局应用状态（依赖容器），由 `@State` 持有，与应用生命周期一致
    @State private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                // 注入 AppState 到 SwiftUI 环境，子视图通过 @Environment(AppState.self) 访问
                .environment(appState)
                // 注入 SwiftData ModelContainer，子视图可通过 @Query / @Environment(\.modelContext) 使用
                .modelContainer(appState.dataController.container)
        }
    }
}
