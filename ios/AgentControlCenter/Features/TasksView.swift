import SwiftUI

/// 任务视图
/// 展示 taskManager 中的任务列表,支持按状态过滤、滑动取消 / 删除
struct TasksView: View {
    // 全局应用状态
    @Environment(AppState.self) private var appState
    // 当前过滤条件
    @State private var filter: TaskFilter = .all

    /// 任务过滤枚举:全部 / 进行中 / 已完成
    enum TaskFilter: String, CaseIterable, Identifiable {
        case all = "全部"
        case active = "进行中"
        case completed = "已完成"
        var id: String { rawValue }
    }

    // 根据过滤条件计算要展示的任务
    var filteredTasks: [AgentTask] {
        switch filter {
        case .all:
            return appState.taskManager.tasks
        case .active:
            // pending / running 视为进行中
            return appState.taskManager.tasks.filter {
                $0.status == .pending || $0.status == .running
            }
        case .completed:
            // completed / failed / cancelled 视为已结束
            return appState.taskManager.tasks.filter {
                $0.status == .completed || $0.status == .failed || $0.status == .cancelled
            }
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // 顶部状态过滤分段控件
                Picker("过滤", selection: $filter) {
                    ForEach(TaskFilter.allCases) { f in
                        Text(f.rawValue).tag(f)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)

                // 任务列表
                List {
                    ForEach(filteredTasks) { task in
                        TaskRow(task: task)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                if task.status == .running || task.status == .pending {
                                    // 进行中的任务:滑动取消
                                    Button(role: .destructive) {
                                        appState.taskManager.cancelTask(task.id)
                                    } label: {
                                        Label("取消", systemImage: "stop.circle")
                                    }
                                    .tint(.orange)
                                } else {
                                    // 已结束的任务:滑动删除
                                    Button(role: .destructive) {
                                        appState.taskManager.deleteTask(task.id)
                                    } label: {
                                        Label("删除", systemImage: "trash")
                                    }
                                }
                            }
                    }
                }
                .listStyle(.insetGrouped)
            }
            .navigationTitle("任务")
            // 空状态占位
            .overlay {
                if filteredTasks.isEmpty {
                    ContentUnavailableView(
                        "暂无任务",
                        systemImage: "checklist",
                        description: Text("这里将展示 Agent 执行的任务记录")
                    )
                }
            }
        }
    }
}

/// 单个任务行视图:类型图标 + 输入预览 + 状态徽章 + 时间
private struct TaskRow: View {
    let task: AgentTask

    // 根据任务类型(TaskType 枚举)返回对应图标
    var typeIcon: String {
        switch task.type {
        case .chat: return "bubble.left"
        case .code: return "chevron.left.forwardslash.chevron.right"
        case .workflow: return "flowchart"
        case .toolCall: return "wrench.and.screwdriver"
        case .fileOperation: return "folder"
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            // 类型图标(颜色随状态变化)
            Image(systemName: typeIcon)
                .font(.title3)
                .foregroundStyle(AppTheme.taskStatusColors[task.status] ?? .gray)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 4) {
                // 输入预览(最多两行)
                Text(task.input)
                    .font(.subheadline)
                    .lineLimit(2)

                HStack(spacing: 8) {
                    // 状态徽章(颜色随状态变化)
                    Text(task.status.rawValue)
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(
                            (AppTheme.taskStatusColors[task.status] ?? .gray).opacity(0.15),
                            in: Capsule()
                        )
                        .foregroundStyle(AppTheme.taskStatusColors[task.status] ?? .gray)

                    // 创建时间(毫秒时间戳)
                    Text(AppTheme.timeAgo(task.createdAt))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }
}
