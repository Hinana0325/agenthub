import SwiftUI

// MARK: - WorkflowView

/// 工作流管理和执行页面，对应 Android WorkflowScreen。
/// 提供预置模板选择、输入执行、DAG 可视化、日志查看等功能。
struct WorkflowView: View {
    @Environment(AppState.self) private var appState

    /// 预置模板列表
    private let templates: [Workflow] = WorkflowTemplates.allTemplates()

    /// 当前选中的模板
    @State private var selectedWorkflow: Workflow? = nil

    /// 用户输入文本
    @State private var inputText: String = ""

    /// 执行状态（从 workflowEngine 观察）
    private var executionState: WorkflowExecutionState {
        appState.workflowEngine.executionState
    }

    /// 日志区域是否展开
    @State private var showLogs: Bool = false

    /// 执行任务引用（用于取消）
    @State private var executeTask: Task<String, Never>?

    /// v4.9.0: 工作流执行历史（落库记录，按时间倒序）。
    /// 通过 `WorkflowEngine.fetchHistory(workflowId: nil)` 拉取全部工作流的历史。
    @State private var history: [WorkflowRunEntity] = []

    /// v4.9.0: 历史列表是否展开
    @State private var showHistory: Bool = false

    // MARK: - 加载/错误态(v5.0 P0)
    /// 首屏骨架屏开关：true 时渲染骨架占位
    @State private var isLoading: Bool = true
    /// 加载错误信息：非 nil 时覆盖列表渲染 ErrorStateView
    @State private var errorMessage: String? = nil

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                if isLoading {
                    // v5.0 P0: 首屏骨架屏占位
                    SkeletonList(repeat: 5) {
                        ListRowSkeleton()
                            .padding(16)
                            .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
                    }
                } else {
                    // MARK: 模板选择区
                    templateSection

                    // MARK: 执行区域
                    if selectedWorkflow != nil {
                        executionSection
                    }

                    // MARK: DAG 可视化
                    if selectedWorkflow != nil {
                        dagVisualization
                    }

                    // MARK: 执行结果
                    if !executionState.output.isEmpty {
                        resultSection
                    }

                    // MARK: 错误展示
                    if let error = executionState.error {
                        errorBanner(error)
                    }

                    // MARK: 日志区域
                    if !executionState.logs.isEmpty {
                        logSection
                    }

                    // MARK: 执行历史
                    historySection
                }
            }
            .padding(16)
        }
        .navigationTitle("工作流")
        // v5.0 P0: 加载错误时覆盖列表展示 ErrorStateView + onRetry 重载
        .overlay {
            if let errorMessage {
                ErrorStateView(
                    icon: "arrow.triangle.branch",
                    title: "加载失败",
                    message: errorMessage,
                    onRetry: { reloadWorkflow() }
                )
                .background(AppTheme.backgroundColor)
            }
        }
        // v4.9.0: 进入页面时加载执行历史（落库记录，按时间倒序）
        .task {
            if isLoading {
                await loadWorkflowInitial()
            }
        }
        // 视图销毁时取消未完成的工作流执行任务，避免后台继续占用资源 / 触发无用 LLM 调用
        .onDisappear {
            executeTask?.cancel()
            executeTask = nil
        }
    }

    // MARK: - 模板选择

    /// 预置模板选择区域
    private var templateSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("选择模板", systemImage: "square.stack.3d.up")
                .font(AppTheme.Typography.headline)

            // 模板选择按钮行
            HStack(spacing: 10) {
                ForEach(templates) { template in
                    templateButton(template)
                }
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    /// 单个模板选择按钮
    private func templateButton(_ template: Workflow) -> some View {
        let isSelected = selectedWorkflow?.id == template.id

        return Button {
            // 切换模板时重置状态
            if isSelected {
                selectedWorkflow = nil
            } else {
                selectedWorkflow = template
                appState.workflowEngine.reset()
                inputText = ""
            }
        } label: {
            VStack(spacing: 6) {
                Image(systemName: templateIcon(template.id))
                    .font(AppTheme.Typography.title2)
                    .foregroundStyle(isSelected ? .white : AppTheme.primaryColor)

                Text(template.name)
                    .font(AppTheme.Typography.caption)
                    .fontWeight(.medium)
                    .foregroundStyle(isSelected ? .white : .primary)
                    .lineLimit(1)

                Text(template.description)
                    .font(AppTheme.Typography.caption2)
                    .foregroundStyle(isSelected ? .white.opacity(0.8) : .secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .padding(.horizontal, 8)
            .background(
                isSelected ? AppTheme.primaryColor : Color.clear,
                in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm)
                    .stroke(isSelected ? AppTheme.primaryColor : Color(.separator), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    /// 根据模板 ID 返回图标
    private func templateIcon(_ id: String) -> String {
        switch id {
        case "translation_chain": return "character.book.closed"
        case "code_review":       return "checkmark.shield"
        case "research_assistant": return "magnifyingglass"
        default:                  return "square.stack"
        }
    }

    // MARK: - 执行区域

    /// 输入框 + 执行按钮
    private var executionSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("执行工作流", systemImage: "play.circle")
                .font(AppTheme.Typography.headline)

            TextField("输入内容…", text: $inputText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(2...5)

            HStack {
                if executionState.isRunning {
                    // 运行中：显示停止按钮（HIG：触控高度 ≥ 44pt）
                    Button {
                        executeTask?.cancel()
                        appState.workflowEngine.reset()
                    } label: {
                        Label("停止", systemImage: "stop.fill")
                            .font(AppTheme.Typography.subheadline.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                    }
                    // R4: glassTinted 包装守卫，iOS 18 走 ultraThinMaterial + tint 回退
                    .glassTinted(
                        Color.red,
                        in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm)
                    )
                } else {
                    // 空闲：显示执行按钮（HIG：触控高度 ≥ 44pt）
                    Button {
                        executeWorkflow()
                    } label: {
                        Label("执行", systemImage: "play.fill")
                            .font(AppTheme.Typography.subheadline.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                    }
                    .disabled(!canExecute)
                    // R4: glassTinted 包装守卫
                    .glassTinted(
                        canExecute ? AppTheme.primaryColor : Color.gray,
                        in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm)
                    )
                }

                Spacer()

                // 已完成节点数 / 总节点数
                if let workflow = selectedWorkflow {
                    let completed = executionState.completedNodeIds.count
                    let total = workflow.nodes.count
                    Text("\(completed) / \(total) 节点完成")
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    /// 是否允许执行
    private var canExecute: Bool {
        guard selectedWorkflow != nil else { return false }
        return !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !executionState.isRunning
    }

    // MARK: - 执行逻辑

    /// 异步执行工作流
    private func executeWorkflow() {
        guard let workflow = selectedWorkflow else { return }
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        appState.workflowEngine.reset()
        showLogs = true

        executeTask = Task {
            let result = await appState.workflowEngine.execute(workflow: workflow, input: text)
            // SW-M7: Task 闭包已通过 appState 访问继承 MainActor 隔离，无需 MainActor.run
            // executionState 已通过 @Observable 自动更新
            // 修复: 原实现用 `result.hasPrefix("Error:")` 字符串嗅探判断成功/失败，
            // 一旦 WorkflowEngine 改变错误返回格式（本地化、改前缀）会误判。
            // 改为检查 executionState.error（@Observable 已暴露的错误状态）。
            if appState.workflowEngine.executionState.error == nil {
                inputText = ""
            }
            // v4.9.0: 执行完成后刷新历史列表（无论成功/失败/取消均已落库一条记录）
            await loadHistory()
            return result
        }
    }

    // MARK: - 执行历史（v4.9.0）

    /// 加载工作流执行历史（按时间倒序，最多 50 条）。
    /// `workflowId` 传 nil 取全部工作流的历史，对齐 Android `WorkflowEngine.getHistoryFlow(null)`。
    private func loadHistory() async {
        history = await appState.workflowEngine.fetchHistory(workflowId: nil, limit: 50)
    }

    // MARK: - 加载/错误态(v5.0 P0)

    /// 首屏加载：展示骨架屏后从 workflowEngine 拉取执行历史。
    /// `fetchHistory` 当前为非抛错 async API；保留 errorMessage 框架便于
    /// 未来切换为可失败加载时无缝接入。
    private func loadWorkflowInitial() async {
        // 短暂展示骨架屏，让用户感知「正在加载」
        try? await Task.sleep(nanoseconds: 250_000_000)
        await loadHistory()
        isLoading = false
    }

    /// 错误重试入口：重置状态后重新加载（onRetry 闭包要求 () -> Void）
    private func reloadWorkflow() {
        isLoading = true
        errorMessage = nil
        Task { await loadWorkflowInitial() }
    }

    /// 执行历史列表段 — 可展开的近期执行记录。
    /// 每行展示状态图标、工作流名称、相对时间与输入预览。
    /// 无历史记录时不渲染整个段（`@ViewBuilder` 下 if 分支不命中即产出 EmptyView，
    /// 避免空卡片占据页面空间）。
    @ViewBuilder
    private var historySection: some View {
        if !history.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Button {
                    withAnimation { showHistory.toggle() }
                } label: {
                    HStack {
                        Label("执行历史 (\(history.count))", systemImage: "clock.arrow.circlepath")
                            .font(AppTheme.Typography.headline)
                            .foregroundStyle(.primary)

                        Spacer()

                        Image(systemName: showHistory ? "chevron.up" : "chevron.down")
                            .font(AppTheme.Typography.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                if showHistory {
                    LazyVStack(spacing: 8) {
                        // WorkflowRunEntity 是 SwiftData @Model，未自动遵循 Identifiable，
                        // 故用 id: \.id 显式指定（实体已有 @Attribute(.unique) var id: String）。
                        ForEach(history, id: \.id) { run in
                            historyRow(run)
                        }
                    }
                }
            }
            .padding(16)
            .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
        }
    }

    /// 单条历史记录行
    private func historyRow(_ run: WorkflowRunEntity) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                // 状态图标
                Image(systemName: historyStatusIcon(run.status))
                    .foregroundStyle(historyStatusColor(run.status))
                    .font(AppTheme.Typography.subheadline)

                // 工作流名称
                Text(run.workflowName)
                    .font(AppTheme.Typography.subheadline)
                    .fontWeight(.medium)
                    .lineLimit(1)

                Spacer()

                // 状态标签
                Text(run.status)
                    .font(AppTheme.Typography.caption2)
                    .fontWeight(.medium)
                    .foregroundStyle(historyStatusColor(run.status))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(historyStatusColor(run.status).opacity(0.12), in: Capsule())
            }

            // 时间 + 输入预览
            HStack(spacing: 8) {
                Text(historyTimeText(run.startedAt))
                    .font(AppTheme.Typography.caption)
                    .foregroundStyle(.secondary)

                if !run.input.isEmpty {
                    Text("·")
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.tertiary)
                    Text(run.input)
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }

            // 失败时展示错误信息
            if run.status == "FAILED", let error = run.error, !error.isEmpty {
                Text(error)
                    .font(AppTheme.Typography.caption)
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
    }

    /// 历史状态对应的 SF Symbol 图标
    private func historyStatusIcon(_ status: String) -> String {
        switch status {
        case "COMPLETED": return "checkmark.circle.fill"
        case "FAILED":    return "xmark.circle.fill"
        case "CANCELLED": return "minus.circle.fill"
        case "RUNNING":   return "circle.dashed"
        default:          return "circle"
        }
    }

    /// 历史状态对应的颜色
    private func historyStatusColor(_ status: String) -> Color {
        switch status {
        case "COMPLETED": return .green
        case "FAILED":    return .red
        case "CANCELLED": return .orange
        case "RUNNING":   return AppTheme.primaryColor
        default:          return .secondary
        }
    }

    /// 毫秒时间戳格式化为可读文本（日期 + 时:分）
    private func historyTimeText(_ milliseconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter.string(from: date)
    }

    // MARK: - DAG 可视化

    /// 简化的 DAG 节点链可视化
    private var dagVisualization: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("工作流节点", systemImage: "arrow.triangle.branch")
                .font(AppTheme.Typography.headline)

            if let workflow = selectedWorkflow {
                // 用 VStack + HStack 画出节点链
                VStack(spacing: 0) {
                    ForEach(Array(workflow.nodes.enumerated()), id: \.element.id) { index, node in
                        nodeRow(node: node, index: index, total: workflow.nodes.count)
                    }
                }
                .padding(12)
                .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    /// 单个 DAG 节点行
    private func nodeRow(node: WorkflowNode, index: Int, total: Int) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                // 节点状态图标
                nodeStatusIcon(node: node)

                // 节点信息
                VStack(alignment: .leading, spacing: 2) {
                    Text(node.label.isEmpty ? node.id : node.label)
                        .font(AppTheme.Typography.subheadline)
                        .fontWeight(.medium)

                    HStack(spacing: 8) {
                        // 节点类型标签
                        nodeTypeBadge(node.type)

                        // Agent 类型（如果适用）
                        if let agentType = node.agentType {
                            Text(agentType.displayName)
                                .font(AppTheme.Typography.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Spacer()

                // 当前正在执行的节点显示指示器
                if executionState.currentNodeId == node.id && executionState.isRunning {
                    ProgressView()
                        .scaleEffect(0.8)
                }
            }
            .padding(.vertical, 8)

            // 节点之间的连线箭头
            if index < total - 1 {
                HStack {
                    Spacer()
                        .frame(width: 15)
                    Rectangle()
                        .fill(Color(.separator))
                        .frame(width: 2, height: 20)
                    Spacer()
                }
            }
        }
    }

    /// 节点状态图标
    private func nodeStatusIcon(node: WorkflowNode) -> some View {
        let isCompleted = executionState.completedNodeIds.contains(node.id)
        let isRunning = executionState.currentNodeId == node.id && executionState.isRunning

        return Group {
            if isCompleted {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            } else if isRunning {
                Image(systemName: "circle.dashed")
                    .foregroundStyle(AppTheme.primaryColor)
            } else {
                Image(systemName: "circle")
                    .foregroundStyle(Color(.separator))
            }
        }
        .font(AppTheme.Typography.title3)
    }

    /// 节点类型标签
    private func nodeTypeBadge(_ type: NodeType) -> some View {
        let (text, color) = nodeTypeDisplay(type)
        return Text(text)
            .font(AppTheme.Typography.caption2)
            .fontWeight(.medium)
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.12), in: Capsule())
    }

    /// 节点类型的显示名称和颜色
    private func nodeTypeDisplay(_ type: NodeType) -> (String, Color) {
        switch type {
        case .input:    return ("INPUT", .blue)
        case .agent:    return ("AGENT", .green)
        case .transform: return ("TRANSFORM", .orange)
        case .output:   return ("OUTPUT", .purple)
        }
    }

    // MARK: - 执行结果

    /// 执行完成后的结果展示
    private var resultSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("执行结果", systemImage: "doc.text")
                .font(AppTheme.Typography.headline)

            Text(executionState.output)
                .font(AppTheme.Typography.body)
                .foregroundStyle(.primary)
                .textSelection(.enabled)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    // MARK: - 错误横幅

    /// 执行错误展示
    private func errorBanner(_ error: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)

            Text(error)
                .font(AppTheme.Typography.subheadline)
                .foregroundStyle(.red)
                .lineLimit(3)
        }
        .padding(12)
        .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
        .padding(.horizontal, 16)
    }

    // MARK: - 日志区域

    /// 可展开的执行日志列表
    private var logSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation { showLogs.toggle() }
            } label: {
                HStack {
                    Label("执行日志 (\(executionState.logs.count))", systemImage: "list.bullet.rectangle")
                        .font(AppTheme.Typography.headline)
                        .foregroundStyle(.primary)

                    Spacer()

                    Image(systemName: showLogs ? "chevron.up" : "chevron.down")
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if showLogs {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(executionState.logs.enumerated()), id: \.offset) { index, log in
                            HStack(alignment: .top, spacing: 8) {
                                Text("\(index + 1)")
                                    .font(AppTheme.Typography.caption2)
                                    .foregroundStyle(.tertiary)
                                    .frame(width: 20, alignment: .trailing)

                                Text(log)
                                    .font(AppTheme.Typography.caption)
                                    .foregroundStyle(
                                        log.hasPrefix("Error") ? .red : .secondary
                                    )
                                    .textSelection(.enabled)
                            }
                        }
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
                }
                .frame(maxHeight: 200)
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }
}