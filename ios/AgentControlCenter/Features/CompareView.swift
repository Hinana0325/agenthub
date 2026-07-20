import SwiftUI

// MARK: - CompareView

/// Agent 对比功能页面，对应 Android CompareScreen。
/// 支持选择两个 Agent 并行发送相同消息，对比回复效果。
struct CompareView: View {
    @Environment(AppState.self) private var appState

    // MARK: - Agent 选择

    /// 左侧 Agent ID
    @State private var agentAId: String = ""
    /// 右侧 Agent ID
    @State private var agentBId: String = ""

    // MARK: - 输入与响应

    /// 共享输入文本
    @State private var inputText: String = ""
    /// Agent A 的流式响应
    @State private var responseA: String = ""
    /// Agent B 的流式响应
    @State private var responseB: String = ""

    // MARK: - 状态

    /// 是否正在对比中
    @State private var isComparing: Bool = false
    /// Agent A 是否完成
    @State private var isAFinished: Bool = false
    /// Agent B 是否完成
    @State private var isBFinished: Bool = false
    /// Agent A 的错误信息
    @State private var errorA: String? = nil
    /// Agent B 的错误信息
    @State private var errorB: String? = nil

    /// 对比任务引用（用于取消）
    @State private var compareTask: Task<Void, Never>?

    // MARK: - 可选 Agent 列表（有配置的）

    /// 有配置的 Agent 列表（用于 Picker 选择）
    private var availableAgents: [Agent] {
        appState.agentManager.agents.filter { $0.config != nil || $0.status == .online }
    }

    var body: some View {
        VStack(spacing: 0) {
            // MARK: Agent 选择区
            agentPickerSection

            Divider()

            // MARK: 输入区域
            inputSection

            Divider()

            // MARK: 响应对比区
            if isComparing || !responseA.isEmpty || !responseB.isEmpty || errorA != nil || errorB != nil {
                comparisonSection
            } else {
                Spacer()
                emptyHint
                Spacer()
            }
        }
        .navigationTitle("Agent 对比")
    }

    // MARK: - Agent 选择区

    /// 两个 Agent Picker 横向排列
    private var agentPickerSection: some View {
        HStack(spacing: 12) {
            // 左侧 Agent A
            agentPickerColumn(
                label: "Agent A",
                selection: $agentAId,
                color: .blue.opacity(0.08)
            )

            // 中间 VS 标识
            Text("VS")
                .font(.caption)
                .fontWeight(.bold)
                .foregroundStyle(.secondary)
                .padding(.vertical, 4)

            // 右侧 Agent B
            agentPickerColumn(
                label: "Agent B",
                selection: $agentBId,
                color: .orange.opacity(0.08)
            )
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(AppTheme.secondaryBackground)
    }

    /// 单个 Agent Picker 列
    private func agentPickerColumn(
        label: String,
        selection: Binding<String>,
        color: Color
    ) -> some View {
        VStack(spacing: 6) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)

            Picker(label, selection: selection) {
                Text("请选择").tag("")
                ForEach(availableAgents) { agent in
                    Text(agent.name).tag(agent.id)
                }
            }
            .pickerStyle(.menu)
            .frame(height: 32)
        }
        .frame(maxWidth: .infinity)
        .padding(8)
        .background(color, in: RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - 输入区域

    /// 共享输入框 + 发送/停止按钮
    private var inputSection: some View {
        HStack(spacing: 10) {
            TextField("输入对比内容…", text: $inputText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(1...3)
                .submitLabel(.send)
                .onSubmit { startComparison() }

            if isComparing {
                Button {
                    cancelComparison()
                } label: {
                    Image(systemName: "stop.fill")
                        .font(.title3)
                        .foregroundStyle(.white)
                        .padding(8)
                        .background(Color.red, in: Circle())
                }
            } else {
                Button {
                    startComparison()
                } label: {
                    Image(systemName: "paperplane.fill")
                        .font(.title3)
                        .foregroundStyle(canStart ? .white : .secondary)
                        .padding(8)
                        .background(
                            canStart ? AppTheme.primaryColor : Color.gray.opacity(0.3),
                            in: Circle()
                        )
                }
                .disabled(!canStart)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    /// 是否允许开始对比
    private var canStart: Bool {
        !agentAId.isEmpty
            && !agentBId.isEmpty
            && !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && agentAId != agentBId
    }

    // MARK: - 响应对比区

    /// 左右两列并行响应展示
    private var comparisonSection: some View {
        HStack(alignment: .top, spacing: 0) {
            // 左列：Agent A
            responseColumn(
                response: responseA,
                agentName: agentName(for: agentAId),
                isFinished: isAFinished,
                error: errorA,
                color: .blue.opacity(0.06),
                borderColor: .blue.opacity(0.3)
            )

            // 分隔线
            Rectangle()
                .fill(Color(.separator))
                .frame(width: 1)
                .padding(.vertical, 8)

            // 右列：Agent B
            responseColumn(
                response: responseB,
                agentName: agentName(for: agentBId),
                isFinished: isBFinished,
                error: errorB,
                color: .orange.opacity(0.06),
                borderColor: .orange.opacity(0.3)
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// 单列响应展示
    private func responseColumn(
        response: String,
        agentName: String,
        isFinished: Bool,
        error: String?,
        color: Color,
        borderColor: Color
    ) -> some View {
        VStack(spacing: 0) {
            // 列标题
            HStack {
                Text(agentName.isEmpty ? "—" : agentName)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .lineLimit(1)

                Spacer()

                if isFinished && error == nil {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                        .font(.caption)
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            .padding(.bottom, 4)

            // 响应内容
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let error = error {
                        // 错误展示
                        HStack(spacing: 6) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.red)
                                .font(.caption)
                            Text(error)
                                .font(.caption)
                                .foregroundStyle(.red)
                        }
                        .padding(12)
                    } else if response.isEmpty && isComparing {
                        // 等待响应
                        HStack {
                            ProgressView()
                                .scaleEffect(0.7)
                            Text("等待回复…")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(12)
                    } else {
                        // 响应文本
                        Text(response)
                            .font(.body)
                            .textSelection(.enabled)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }

            // 字数统计
            if !response.isEmpty {
                HStack {
                    Text("\(response.count) 字")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
            }
        }
        .frame(maxWidth: .infinity)
        .background(color)
        .overlay(
            RoundedRectangle(cornerRadius: 0)
                .strokeBorder(borderColor, lineWidth: 1)
        )
    }

    // MARK: - 空状态提示

    /// 对比前的引导提示
    private var emptyHint: some View {
        VStack(spacing: 16) {
            Image(systemName: "arrow.left.arrow.right")
                .font(.system(size: 48))
                .foregroundStyle(.secondary.opacity(0.5))

            Text("选择两个 Agent 并输入内容")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Text("相同的消息将同时发送给两个 Agent，\n方便你对比不同模型的回复效果。")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }

    // MARK: - 辅助方法

    /// 根据 Agent ID 获取名称
    private func agentName(for id: String) -> String {
        appState.agentManager.getAgent(id)?.name ?? "未知 Agent"
    }

    // MARK: - 取消对比

    /// 取消正在进行的对比
    private func cancelComparison() {
        compareTask?.cancel()
        compareTask = nil
        isComparing = false
    }

    // MARK: - 开始对比

    /// 并行向两个 Agent 发送消息并收集流式响应
    private func startComparison() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        // 获取 Agent 和配置
        guard let agentA = appState.agentManager.getAgent(agentAId),
              let agentB = appState.agentManager.getAgent(agentBId) else {
            return
        }

        // 重置状态
        responseA = ""
        responseB = ""
        errorA = nil
        errorB = nil
        isAFinished = false
        isBFinished = false
        isComparing = true

        // Agent A 和 Agent B 的配置（优先使用 Agent.config，否则用默认配置）
        let configA = agentA.config ?? Self.makeDefaultConfig(for: agentA.config?.type ?? .openAI)
        let configB = agentB.config ?? Self.makeDefaultConfig(for: agentB.config?.type ?? .openAI)

        let typeA = configA.type
        let typeB = configB.type

        compareTask = Task {
            await withTaskGroup(of: Void.self) { group in
                // Agent A 的收集任务
                group.addTask {
                    await collectResponse(
                        agentType: typeA,
                        config: configA,
                        input: text,
                        onUpdate: { text in
                            Task { @MainActor in
                                self.responseA += text
                            }
                        },
                        onSet: { text in
                            Task { @MainActor in
                                self.responseA = text
                            }
                        },
                        onError: { msg in
                            Task { @MainActor in
                                self.errorA = msg
                                self.isAFinished = true
                            }
                        },
                        onComplete: {
                            Task { @MainActor in
                                self.isAFinished = true
                            }
                        }
                    )
                }

                // Agent B 的收集任务
                group.addTask {
                    await collectResponse(
                        agentType: typeB,
                        config: configB,
                        input: text,
                        onUpdate: { text in
                            Task { @MainActor in
                                self.responseB += text
                            }
                        },
                        onSet: { text in
                            Task { @MainActor in
                                self.responseB = text
                            }
                        },
                        onError: { msg in
                            Task { @MainActor in
                                self.errorB = msg
                                self.isBFinished = true
                            }
                        },
                        onComplete: {
                            Task { @MainActor in
                                self.isBFinished = true
                            }
                        }
                    )
                }

                // 等待所有任务完成
                await group.waitForAll()

                // 双方都结束后标记对比结束
                await MainActor.run {
                    isComparing = false
                    compareTask = nil
                }
            }
        }
    }

    // MARK: - 收集单个 Agent 响应

    /// 为单个 Agent 创建 Transport、连接、发送消息并收集流式事件
    ///
    /// 流程与 WorkflowEngine.executeAgent 类似：
    /// 1. TransportFactory.create() 创建传输层
    /// 2. connect() 建立连接
    /// 3. sendMessage() 发送消息
    /// 4. 消费 events AsyncStream 收集响应
    /// 5. 收到 StreamComplete 或 Error 时结束
    private func collectResponse(
        agentType: AgentType,
        config: AgentConfig,
        input: String,
        onUpdate: @escaping (String) -> Void,
        onSet: @escaping (String) -> Void,
        onError: @escaping (String) -> Void,
        onComplete: @escaping () -> Void
    ) async {
        let transport = TransportFactory.create(agentType)
        let sessionId = "compare_\(UUID().uuidString)"

        // 连接
        await transport.connect(config: config, e2eKey: nil)

        // 发送消息
        do {
            try await transport.sendMessage(sessionId: sessionId, content: input)
        } catch {
            onError("发送失败: \(error.localizedDescription)")
            transport.shutdown()
            return
        }

        // 收集事件
        do {
            for await event in transport.events {
                if Task.isCancelled { break }
                switch event {
                case .messageReceived(let content, let isDelta):
                    if isDelta {
                        onUpdate(content)
                    } else {
                        onSet(content)
                    }
                case .streamComplete:
                    onComplete()
                    transport.shutdown()
                    return
                case .error(let message):
                    onError(message)
                    transport.shutdown()
                    return
                default:
                    break
                }
            }
        } catch {
            if !Task.isCancelled {
                onError("连接异常中断")
            }
        }

        transport.shutdown()
        onComplete()
    }

    // MARK: - 默认配置

    /// 为指定 Agent 类型生成默认配置（CompareView 本地版本，避免依赖 WorkflowEngine 私有方法）
    private static func makeDefaultConfig(for agentType: AgentType) -> AgentConfig {
        AgentConfig(
            id: "compare_\(agentType.rawValue)",
            name: agentType.displayName,
            type: agentType,
            serverUrl: "",
            apiKey: "",
            model: "",
            systemPrompt: "",
            temperature: 0.7,
            maxTokens: 4096
        )
    }
}