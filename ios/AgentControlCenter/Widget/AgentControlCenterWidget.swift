import WidgetKit
import SwiftUI

// MARK: - Widget 数据模型

/// Widget 时间线条目
struct AgentEntry: TimelineEntry {
    let date: Date
    let lastMessage: String?
    let sessionTitle: String?
    let agentName: String?
    let isConfigured: Bool
}

/// Widget 配置 — 用户可选择显示哪个会话
struct AgentWidgetConfiguration: Identifiable {
    let id = "default"
}

// MARK: - Widget 数据提供者

/// 从 App Group UserDefaults 读取最近消息
struct AgentWidgetProvider: TimelineProvider {

    private let defaults = UserDefaults(suiteName: "group.com.agentcontrolcenter.app.ios")

    func placeholder(in context: Context) -> AgentEntry {
        AgentEntry(date: Date(), lastMessage: nil, sessionTitle: "Agent Control Center", agentName: nil, isConfigured: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (AgentEntry) -> Void) {
        let entry = loadLatestEntry()
        completion(entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<AgentEntry>) -> Void) {
        let entry = loadLatestEntry()
        // 5 分钟后刷新
        let nextUpdate = Calendar.current.date(byAdding: .minute, value: 5, to: Date())!
        let timeline = Timeline(entries: [entry], policy: .after(nextUpdate))
        completion(timeline)
    }

    private func loadLatestEntry() -> AgentEntry {
        let message = defaults?.string(forKey: "widget_last_message")
        let title = defaults?.string(forKey: "widget_session_title")
        let agent = defaults?.string(forKey: "widget_agent_name")
        let configured = defaults?.bool(forKey: "widget_configured") ?? false

        return AgentEntry(
            date: Date(),
            lastMessage: message,
            sessionTitle: title,
            agentName: agent,
            isConfigured: configured
        )
    }
}

// MARK: - Widget 视图

struct AgentWidgetView: View {
    let entry: AgentEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // 标题行
            HStack {
                Image(systemName: "cpu")
                    .foregroundStyle(.blue)
                Text(entry.sessionTitle ?? "Agent Control Center")
                    .font(.caption)
                    .fontWeight(.medium)
                    .lineLimit(1)
                Spacer()
                if let agent = entry.agentName {
                    Text(agent)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            // 最近消息
            if let message = entry.lastMessage {
                Text(message)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(family == .systemSmall ? 2 : 4)
            } else {
                Text("暂无消息")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(12)
    }
}

// MARK: - Widget 定义

struct AgentControlCenterWidget: Widget {
    let kind = "AgentControlCenterWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: AgentWidgetProvider()) { entry in
            AgentWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Agent Control Center")
        .description("快捷查看最近 Agent 对话")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

// MARK: - Widget Bundle
// 注意: 此 @main 仅在 Widget Extension Target 中生效
// 主 App Target 不应编译此文件（通过 project.yml 的 target 归属控制）

@main
struct AgentWidgetBundle: WidgetBundle {
    var body: some Widget {
        AgentControlCenterWidget()
    }
}

// MARK: - Widget 数据更新辅助

/// 在主 App 中调用此方法更新 Widget 显示
enum WidgetUpdater {
    static func updateWidget(
        lastMessage: String,
        sessionTitle: String,
        agentName: String
    ) {
        let defaults = UserDefaults(suiteName: "group.com.agentcontrolcenter.app.ios")
        defaults?.set(true, forKey: "widget_configured")
        defaults?.set(lastMessage, forKey: "widget_last_message")
        defaults?.set(sessionTitle, forKey: "widget_session_title")
        defaults?.set(agentName, forKey: "widget_agent_name")
        WidgetCenter.shared.reloadAllTimelines()
    }
}
