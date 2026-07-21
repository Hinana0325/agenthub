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

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
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
            }
            .padding(16)
        }
        .navigationTitle("工作流")
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
                .font(.headline)

            // 模板选择按钮行
            HStack(spacing: 10) {
                ForEach(templates) { template in
                    templateButton(template)
                }
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
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
                    .font(.title2)
                    .foregroundStyle(isSelected ? .white : AppTheme.primaryColor)

                Text(template.name)
                    .font(.caption)
                    .fontWeight(.medium)
                    .foregroundStyle(isSelected ? .white : .primary)
                    .lineLimit(1)

                Text(template.description)
                    .font(.caption2)
                    .foregroundStyle(isSelected ? .white.opacity(0.8) : .secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .padding(.horizontal, 8)
            .background(
                isSelected ? AppTheme.primaryColor : Color.clear,
                in: RoundedRectangle(cornerRadius: 10)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10)
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
                .font(.headline)

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
                            .font(.subheadline.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                    }
                    // R4: glassTinted 包装守卫，iOS 18 走 ultraThinMaterial + tint 回退
                    .glassTinted(
                        Color.red,
                        in: RoundedRectangle(cornerRadius: 8)
                    )
                } else {
                    // 空闲：显示执行按钮（HIG：触控高度 ≥ 44pt）
                    Button {
                        executeWorkflow()
                    } label: {
                        Label("执行", systemImage: "play.fill")
                            .font(.subheadline.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                    }
                    .disabled(!canExecute)
                    // R4: glassTinted 包装守卫
                    .glassTinted(
                        canExecute ? AppTheme.primaryColor : Color.gray,
                        in: RoundedRectangle(cornerRadius: 8)
                    )
                }

                Spacer()

                // 已完成节点数 / 总节点数
                if let workflow = selectedWorkflow {
                    let completed = executionState.completedNodeIds.count
                    let total = workflow.nodes.count
                    Text("\(completed) / \(total) 节点完成")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
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
            if !result.hasPrefix("Error:") {
                inputText = ""
            }
        }
    }

    // MARK: - DAG 可视化

    /// 简化的 DAG 节点链可视化
    private var dagVisualization: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("工作流节点", systemImage: "arrow.triangle.branch")
                .font(.headline)

            if let workflow = selectedWorkflow {
                // 用 VStack + HStack 画出节点链
                VStack(spacing: 0) {
                    ForEach(Array(workflow.nodes.enumerated()), id: \.element.id) { index, node in
                        nodeRow(node: node, index: index, total: workflow.nodes.count)
                    }
                }
                .padding(12)
                .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 8))
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
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
                        .font(.subheadline)
                        .fontWeight(.medium)

                    HStack(spacing: 8) {
                        // 节点类型标签
                        nodeTypeBadge(node.type)

                        // Agent 类型（如果适用）
                        if let agentType = node.agentType {
                            Text(agentType.displayName)
                                .font(.caption2)
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
        .font(.title3)
    }

    /// 节点类型标签
    private func nodeTypeBadge(_ type: NodeType) -> some View {
        let (text, color) = nodeTypeDisplay(type)
        return Text(text)
            .font(.caption2)
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
                .font(.headline)

            Text(executionState.output)
                .font(.body)
                .foregroundStyle(.primary)
                .textSelection(.enabled)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 8))
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - 错误横幅

    /// 执行错误展示
    private func errorBanner(_ error: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)

            Text(error)
                .font(.subheadline)
                .foregroundStyle(.red)
                .lineLimit(3)
        }
        .padding(12)
        .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
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
                        .font(.headline)
                        .foregroundStyle(.primary)

                    Spacer()

                    Image(systemName: showLogs ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if showLogs {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(executionState.logs.enumerated()), id: \.offset) { index, log in
                            HStack(alignment: .top, spacing: 8) {
                                Text("\(index + 1)")
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                                    .frame(width: 20, alignment: .trailing)

                                Text(log)
                                    .font(.caption)
                                    .foregroundStyle(
                                        log.hasPrefix("Error") ? .red : .secondary
                                    )
                                    .textSelection(.enabled)
                            }
                        }
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 8))
                }
                .frame(maxHeight: 200)
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
    }
}