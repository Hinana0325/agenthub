import SwiftUI

/// 任务视图
/// 展示来自 DataController（SwiftData 持久化）+ TaskManager（内存）的任务列表
/// 支持按状态过滤、滑动取消/删除、创建新任务（Sheet）
struct TasksView: View {
    // 全局应用状态
    @Environment(AppState.self) private var appState

    // 当前过滤条件
    @State private var filter: TaskFilter = .all

    // 任务创建 Sheet
    @State private var showingCreateSheet = false

    // 本地缓存的任务列表（从 DataController 加载）
    @State private var tasks: [AgentTask] = []

    /// 任务过滤枚举: 全部 / 进行中 / 已完成
    enum TaskFilter: String, CaseIterable, Identifiable {
        case all = "全部"
        case active = "进行中"
        case completed = "已完成"
        var id: String { rawValue }
    }

    // MARK: - Body

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
                                if task.status == .pending || task.status == .running {
                                    // 进行中的任务: 滑动取消（同时更新内存+持久化）
                                    Button(role: .destructive) {
                                        cancelTask(task)
                                    } label: {
                                        Label("取消", systemImage: "stop.circle")
                                    }
                                    .tint(.orange)
                                } else {
                                    // 已结束的任务: 滑动删除（同时从内存+持久化删除）
                                    Button(role: .destructive) {
                                        deleteTask(task)
                                    } label: {
                                        Label("删除", systemImage: "trash")
                                    }
                                }
                            }
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
                .listStyle(.insetGrouped)
                .animation(.easeInOut(duration: 0.25), value: tasks.count)
                // 下拉刷新(P1-13):重新从 DataController 加载任务列表
                .refreshable {
                    await refreshTasks()
                }
            }
            .navigationTitle("任务")
            // 工具栏创建按钮
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showingCreateSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("创建任务")
                }
            }
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
            // 创建任务 Sheet
            .sheet(isPresented: $showingCreateSheet) {
                CreateTaskSheet { task in
                    HapticFeedback.success()
                    // 同时保存到内存层和持久化层
                    appState.taskManager.submitTask(
                        agentId: task.agentId,
                        type: task.type,
                        input: task.input,
                        sessionId: task.sessionId
                    )
                    appState.dataController.saveTask(task)
                    reloadTasks()
                }
            }
            // SW-M2: 使用 .task 替代 .onAppear，由 SwiftUI 管理任务生命周期
            .task {
                reloadTasks()
            }
        }
    }

    // MARK: - 计算属性

    /// 根据过滤条件计算要展示的任务
    var filteredTasks: [AgentTask] {
        switch filter {
        case .all:
            return tasks
        case .active:
            // pending / running 视为进行中
            return tasks.filter {
                $0.status == .pending || $0.status == .running
            }
        case .completed:
            // completed / failed / cancelled 视为已结束
            return tasks.filter {
                $0.status == .completed || $0.status == .failed || $0.status == .cancelled
            }
        }
    }

    // MARK: - 任务操作

    /// 取消任务：同步更新内存层和持久化层
    private func cancelTask(_ task: AgentTask) {
        // 更新内存中的 TaskManager
        appState.taskManager.cancelTask(task.id)
        // 构建更新后的任务对象并持久化
        var updatedTask = task
        updatedTask.status = .cancelled
        updatedTask.completedAt = Int64(Date().timeIntervalSince1970 * 1000)
        appState.dataController.saveTask(updatedTask)
        reloadTasks()
    }

    /// 删除任务：同时从内存层和持久化层移除
    private func deleteTask(_ task: AgentTask) {
        HapticFeedback.medium()
        // 从 TaskManager 内存中删除
        appState.taskManager.deleteTask(task.id)
        // 从 DataController 持久化层删除
        appState.dataController.deleteTask(task.id)
        reloadTasks()
    }

    /// 从 DataController 重新加载任务列表
    private func reloadTasks() {
        tasks = appState.dataController.fetchTasks()
    }

    /// 下拉刷新专用(P1-13):异步包装 reloadTasks,让 .refreshable 有正常动画
    private func refreshTasks() async {
        // 让出当前任务,使下拉刷新动画能正常展示
        await Task.yield()
        // SW-M7: refreshTasks 在 View 内定义，闭包继承 MainActor 隔离，
        // reloadTasks 也是 MainActor 方法，无需 MainActor.run
        reloadTasks()
    }
}

// MARK: - 任务行视图

/// 单个任务行视图: 类型图标 + 输入预览 + 状态徽章 + Agent 名称 + 时间
private struct TaskRow: View {
    let task: AgentTask

    /// 根据任务类型(TaskType 枚举)返回对应图标
    var typeIcon: String {
        switch task.type {
        case .chat: return "bubble.left"
        case .code: return "chevron.left.forwardslash.chevron.right"
        case .workflow: return "flowchart"
        case .toolCall: return "wrench.and.screwdriver"
        case .fileOperation: return "folder"
        }
    }

    /// 任务类型显示名
    var typeDisplayName: String {
        switch task.type {
        case .chat: return "聊天"
        case .code: return "代码"
        case .workflow: return "工作流"
        case .toolCall: return "工具调用"
        case .fileOperation: return "文件操作"
        }
    }

    /// 状态显示名
    var statusDisplayName: String {
        switch task.status {
        case .pending: return "等待中"
        case .running: return "运行中"
        case .completed: return "已完成"
        case .failed: return "失败"
        case .cancelled: return "已取消"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                // 类型图标（颜色随状态变化）
                Image(systemName: typeIcon)
                    .font(.title3)
                    .foregroundStyle(AppTheme.taskStatusColors[task.status] ?? .gray)
                    .frame(width: 32)

                VStack(alignment: .leading, spacing: 4) {
                    // 输入内容作为标题（最多两行）
                    Text(task.input.isEmpty ? "(无内容)" : task.input)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .lineLimit(2)

                    HStack(spacing: 8) {
                        // 状态徽章（颜色随状态变化）
                        Text(statusDisplayName)
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(
                                (AppTheme.taskStatusColors[task.status] ?? .gray).opacity(0.15),
                                in: Capsule()
                            )
                            .foregroundStyle(AppTheme.taskStatusColors[task.status] ?? .gray)

                        // 类型标签
                        Text(typeDisplayName)
                            .font(.caption2)
                            .foregroundStyle(.secondary)

                        // 创建时间
                        Text(AppTheme.timeAgo(task.createdAt))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()
            }

            // Agent 名称（如果有 agentId）
            if !task.agentId.isEmpty {
                HStack(spacing: 4) {
                    Image(systemName: "person.fill")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Text(task.agentId)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .padding(.leading, 44)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - 创建任务 Sheet

/// 创建新任务的 Sheet
/// 包含标题、类型选择、Agent 选择、输入内容
private struct CreateTaskSheet: View {
    /// 创建完成回调，将新任务传回父视图
    let onCreate: (AgentTask) -> Void

    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    @State private var taskInput: String = ""
    @State private var selectedType: TaskType = .chat
    @State private var selectedAgentId: String = ""

    /// 可选的 Agent 列表（从 AgentManager 获取）
    private var availableAgents: [Agent] {
        appState.agentManager.agents
    }

    var body: some View {
        NavigationStack {
            Form {
                // 任务输入
                Section("任务内容") {
                    TextField("输入任务描述", text: $taskInput, axis: .vertical)
                        .lineLimit(3...6)
                }

                // 任务类型
                Section("任务类型") {
                    Picker("类型", selection: $selectedType) {
                        ForEach(TaskType.allCases, id: \.self) { type in
                            Text(taskTypeDisplayName(type)).tag(type)
                        }
                    }
                }

                // Agent 选择
                Section("目标 Agent") {
                    if availableAgents.isEmpty {
                        Text("暂无可用 Agent")
                            .foregroundStyle(.secondary)
                    } else {
                        Picker("Agent", selection: $selectedAgentId) {
                            Text("不指定").tag("")
                            ForEach(availableAgents) { agent in
                                Text(agent.name).tag(agent.id)
                            }
                        }
                    }
                }
            }
            .navigationTitle("新建任务")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        let task = AgentTask(
                            id: "task_\(UUID().uuidString)",
                            agentId: selectedAgentId.isEmpty ? "" : selectedAgentId,
                            sessionId: nil,
                            type: selectedType,
                            input: taskInput
                        )
                        onCreate(task)
                        dismiss()
                    }
                    .disabled(taskInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    /// 任务类型显示名
    private func taskTypeDisplayName(_ type: TaskType) -> String {
        switch type {
        case .chat: return "聊天"
        case .code: return "代码"
        case .workflow: return "工作流"
        case .toolCall: return "工具调用"
        case .fileOperation: return "文件操作"
        }
    }
}
