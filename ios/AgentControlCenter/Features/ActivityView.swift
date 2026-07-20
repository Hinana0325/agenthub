import SwiftUI

// MARK: - ActivityType

/// 活动类型枚举，每种类型对应不同的图标和颜色
enum ActivityType: String, CaseIterable {
    case message    // 消息活动
    case connection // 连接活动
    case error      // 错误活动
    case command    // 命令活动
    case workflow   // 工作流活动

    /// SF Symbol 图标名称
    var systemImage: String {
        switch self {
        case .message:    return "bubble.left.fill"
        case .connection: return "link.circle.fill"
        case .error:      return "exclamationmark.triangle.fill"
        case .command:    return "terminal.fill"
        case .workflow:   return "arrow.triangle.branch"
        }
    }

    /// 活动类型对应的颜色
    var tintColor: Color {
        switch self {
        case .message:    return .blue
        case .connection: return .green
        case .error:      return .red
        case .command:    return .orange
        case .workflow:   return .purple
        }
    }

    /// 中文显示名称
    var displayName: String {
        switch self {
        case .message:    return "消息"
        case .connection: return "连接"
        case .error:      return "错误"
        case .command:    return "命令"
        case .workflow:   return "工作流"
        }
    }
}

// MARK: - ActivityItem

/// 活动日志条目
struct ActivityItem: Identifiable {
    let id = UUID()
    let type: ActivityType
    let title: String
    let description: String
    let timestamp: Date
}

// MARK: - ActivityView

/// 活动日志时间线视图，对应 Android ActivityScreen。
/// 以时间线样式展示应用内的各类活动事件。
struct ActivityView: View {
    @Environment(AppState.self) private var appState

    /// 活动列表数据
    @State private var activities: [ActivityItem] = []

    /// 当前筛选的活动类型（nil 表示全部）
    @State private var filterType: ActivityType? = nil

    var body: some View {
        Group {
            if filteredActivities.isEmpty {
                emptyView
            } else {
                activityList
            }
        }
        .navigationTitle("活动日志")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    // 全部类型
                    Button {
                        filterType = nil
                    } label: {
                        Label("全部", systemImage: "line.3.horizontal.decrease.circle")
                    }

                    Divider()

                    // 按活动类型筛选
                    ForEach(ActivityType.allCases, id: \.self) { type in
                        Button {
                            filterType = type
                        } label: {
                            Label(type.displayName, systemImage: type.systemImage)
                        }
                    }
                } label: {
                    Image(systemName: "line.3.horizontal.decrease.circle")
                }
            }
        }
        .onAppear {
            generateSampleActivities()
        }
    }

    // MARK: - 筛选后的活动列表

    /// 根据筛选条件返回活动列表
    private var filteredActivities: [ActivityItem] {
        guard let filterType else { return activities }
        return activities.filter { $0.type == filterType }
    }

    // MARK: - 空状态

    /// 无活动时的空状态提示
    private var emptyView: some View {
        ContentUnavailableView {
            Label("暂无活动记录", systemImage: "clock.badge.questionmark")
        } description: {
            Text("活动日志将在你使用 Agent、发送消息或执行工作流时自动记录。")
        } actions: {
            Button("刷新") {
                Task { try? await Task.sleep(nanoseconds: 500_000_000) }
            }
        }
    }

    // MARK: - 活动列表

    /// 时间线样式的活动列表
    private var activityList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(filteredActivities) { item in
                    activityRow(item)
                        .padding(.vertical, 8)

                    // 最后一个条目不显示分隔线
                    if item.id != filteredActivities.last?.id {
                        Divider()
                            .padding(.leading, 52)
                    }
                }
            }
            .padding(.horizontal, 16)
        }
        .refreshable {
            // 模拟下拉刷新
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    // MARK: - 活动行

    /// 单条活动记录的时间线样式行
    private func activityRow(_ item: ActivityItem) -> some View {
        HStack(alignment: .top, spacing: 12) {
            // 左侧：类型图标
            typeIcon(item.type)

            // 右侧：标题 + 描述 + 时间
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .foregroundStyle(.primary)

                if !item.description.isEmpty {
                    Text(item.description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }

                Text(timeAgo(item.timestamp))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
    }

    // MARK: - 类型图标

    /// 活动类型的圆形图标
    private func typeIcon(_ type: ActivityType) -> some View {
        Image(systemName: type.systemImage)
            .font(.callout)
            .foregroundStyle(.white)
            .frame(width: 36, height: 36)
            .background(type.tintColor, in: Circle())
            .overlay(
                Circle()
                    .stroke(type.tintColor.opacity(0.2), lineWidth: 2)
            )
    }

    // MARK: - 时间格式化

    /// 将 Date 格式化为相对时间字符串
    private func timeAgo(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    // MARK: - 生成示例数据

    /// 根据应用当前状态生成示例活动记录
    private func generateSampleActivities() {
        var items: [ActivityItem] = []
        let now = Date()

        // 从 Agent 状态生成连接活动
        for agent in appState.agentManager.agents {
            let statusText: String
            let descText: String
            let actType: ActivityType

            switch agent.status {
            case .online:
                statusText = "\(agent.name) 已连接"
                descText = "Agent 连接成功，可以正常通信"
                actType = .connection
            case .connecting:
                statusText = "\(agent.name) 正在连接"
                descText = "正在建立传输层连接…"
                actType = .connection
            case .error:
                statusText = "\(agent.name) 连接错误"
                descText = "传输层连接失败，请检查配置"
                actType = .error
            case .offline:
                statusText = "\(agent.name) 已断开"
                descText = "Agent 当前处于离线状态"
                actType = .connection
            }

            items.append(ActivityItem(
                type: actType,
                title: statusText,
                description: descText,
                timestamp: now.addingTimeInterval(-Double.random(in: 60...3600))
            ))
        }

        // 从会话生成消息活动
        for session in appState.sessionManager.sessions.prefix(5) {
            items.append(ActivityItem(
                type: .message,
                title: "会话: \(session.title)",
                description: "共 \(session.messageCount) 条消息",
                timestamp: Date(
                    timeIntervalSince1970: TimeInterval(session.updatedAt) / 1000
                )
            ))
        }

        // 从任务生成命令/工作流活动
        let tasks = appState.dataController.fetchTasks()
        for task in tasks.prefix(5) {
            let type: ActivityType = task.type == .workflow ? .workflow : .command
            let statusDesc: String
            switch task.status {
            case .completed: statusDesc = "已完成"
            case .failed:    statusDesc = "执行失败"
            case .running:   statusDesc = "执行中"
            case .cancelled: statusDesc = "已取消"
            case .pending:   statusDesc = "等待执行"
            }
            items.append(ActivityItem(
                type: type,
                title: "任务: \(task.input.prefix(30))",
                description: statusDesc + (task.error.map { " - \($0)" } ?? ""),
                timestamp: Date(
                    timeIntervalSince1970: TimeInterval(task.createdAt) / 1000
                )
            ))
        }

        // 按时间降序排列
        activities = items.sorted { $0.timestamp > $1.timestamp }
    }
}