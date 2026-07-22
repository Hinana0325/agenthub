import SwiftUI
import UniformTypeIdentifiers

// MARK: - AgentsView
// 对应 Android AgentsScreen — Agent 完整 CRUD + 导入/导出
//
// 功能概览：
// 1. Agent 列表：活跃分区 + 全量列表，空状态占位
// 2. 添加/编辑：共用 AgentFormSheet，编辑模式预填充
// 3. 删除：滑动删除 + 长按菜单，带二次确认 alert
// 4. 导入/导出：Codable JSON + ShareLink / fileImporter
// 5. 点击设活跃
// 6. 自适应布局：NavigationStack + List（iPad 双栏由 ContentView 处理）

/// Agent 管理视图 — 提供完整的增删改查及导入导出能力
struct AgentsView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    // MARK: - 表单 Sheet 状态
    /// 控制添加/编辑表单 Sheet 的显示
    @State private var showingFormSheet = false
    /// 编辑模式时持有的目标 Agent（nil 表示新建模式）
    @State private var editingAgent: Agent?
    /// 编辑模式时持有的目标配置
    @State private var editingConfig: AgentConfig?

    // MARK: - 删除确认状态
    /// 待删除的 Agent（用于 alert 回调中获取目标）
    @State private var agentToDelete: Agent?
    /// 控制删除确认弹窗的显示
    @State private var showingDeleteAlert = false

    // MARK: - 导入状态
    /// 控制文件选择器的显示
    @State private var showingImporter = false
    /// 通用操作结果提示文本
    @State private var resultMessage = ""
    /// 控制结果提示弹窗的显示
    @State private var showingResultAlert = false

    // MARK: - 导出状态
    /// 控制导出分享 Sheet 的显示
    @State private var showingExportSheet = false
    /// 导出生成的临时文件 URL
    @State private var exportFileUrl: URL?
    /// 本次导出的配置数量
    @State private var exportCount: Int = 0

    // MARK: - Body

    var body: some View {
        NavigationStack {
            List {
                // MARK: 当前活跃 Agent
                if let active = appState.agentManager.activeAgent {
                    Section("当前活跃 Agent") {
                        AgentRow(agent: active, isActive: true)
                    }
                }

                // MARK: 所有 Agent
                Section("所有 Agent") {
                    ForEach(appState.agentManager.agents) { agent in
                        // HIG：交互行应使用 Button 而非 onTapGesture，
                        // 这样 VoiceOver / 大字体 / 系统滑动手势才能正确处理
                        Button {
                            HapticFeedback.medium()
                            appState.agentManager.setActive(agentId: agent.id)
                        } label: {
                            AgentRow(
                                agent: agent,
                                isActive: agent.id == appState.agentManager.activeAgent?.id
                            )
                        }
                        .buttonStyle(.plain)
                        // 长按上下文菜单：编辑 / 删除
                        .contextMenu {
                            Button {
                                editAgent(agent)
                            } label: {
                                Label("编辑", systemImage: "pencil")
                            }

                            Divider()

                            Button(role: .destructive) {
                                agentToDelete = agent
                                showingDeleteAlert = true
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                        // 左滑：设为活跃
                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                            Button {
                                HapticFeedback.medium()
                                appState.agentManager.setActive(agentId: agent.id)
                            } label: {
                                Label("设为活跃", systemImage: "star.fill")
                            }
                            .tint(.orange)
                        }
                        // 右滑：删除（需二次确认）
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                agentToDelete = agent
                                showingDeleteAlert = true
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
            }
            .listStyle(.insetGrouped)
            .animation(.easeInOut(duration: 0.25), value: appState.agentManager.agents.count)
            .navigationTitle("Agent")
            // 空状态占位视图
            .overlay {
                if appState.agentManager.agents.isEmpty {
                    ContentUnavailableView(
                        "暂无 Agent",
                        systemImage: "cpu",
                        description: Text("点击右上角 + 添加新 Agent，或通过菜单导入配置")
                    )
                }
            }
            .toolbar {
                // HIG：单个 ToolbarItem(.primaryAction) 应只承载一个主操作。
                // 拆分为两个 .topBarTrailing 让系统分别为其渲染玻璃 chrome。
                ToolbarItem(placement: .topBarTrailing) {
                    // 更多操作菜单：导入 / 导出
                    Menu {
                        Button {
                            showingImporter = true
                        } label: {
                            Label("导入", systemImage: "square.and.arrow.down")
                        }
                        Button {
                            exportConfigs()
                        } label: {
                            Label("导出所有", systemImage: "square.and.arrow.up")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .accessibilityLabel("更多操作")
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    // 添加 Agent 按钮
                    Button {
                        editingAgent = nil
                        editingConfig = nil
                        showingFormSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("添加 Agent")
                }
            }
            // MARK: Sheet: 添加/编辑表单
            .sheet(isPresented: $showingFormSheet) {
                AgentFormSheet(
                    existingAgent: editingAgent,
                    existingConfig: editingConfig
                )
            }
            // MARK: Sheet: 导出分享
            .sheet(isPresented: $showingExportSheet) {
                if let url = exportFileUrl {
                    ExportShareSheet(url: url, count: exportCount)
                }
            }
            // MARK: FileImporter: 选择 JSON 文件导入
            .fileImporter(
                isPresented: $showingImporter,
                allowedContentTypes: [.json],
                allowsMultipleSelection: false
            ) { result in
                handleImportResult(result)
            }
            // MARK: Alert: 删除二次确认
            .alert("确认删除", isPresented: $showingDeleteAlert) {
                Button("取消", role: .cancel) {
                    agentToDelete = nil
                }
                Button("删除", role: .destructive) {
                    if let agent = agentToDelete {
                        deleteAgent(agent)
                    }
                    agentToDelete = nil
                }
            } message: {
                if let agent = agentToDelete {
                    Text("确定要删除「\(agent.name)」吗？此操作不可撤销。")
                }
            }
            // MARK: Alert: 操作结果提示
            .alert("提示", isPresented: $showingResultAlert) {
                Button("确定", role: .cancel) {}
            } message: {
                Text(resultMessage)
            }
        }
    }

    // MARK: - 编辑 Agent

    /// 设置编辑状态并弹出表单 Sheet
    private func editAgent(_ agent: Agent) {
        editingAgent = agent
        editingConfig = agent.config
        showingFormSheet = true
    }

    // MARK: - 删除 Agent

    /// 从持久化层和运行时管理器中移除指定 Agent
    private func deleteAgent(_ agent: Agent) {
        HapticFeedback.heavy()
        // 从 SwiftData 中删除持久化配置
        appState.dataController.deleteAgentConfig(agent.id)
        // 从 AgentManager 中注销运行时实例
        appState.agentManager.unregister(agentId: agent.id)
    }

    // MARK: - 导出所有配置

    /// 将所有 AgentConfig 编码为格式化 JSON，写入临时文件后弹出分享 Sheet
    private func exportConfigs() {
        let configs = appState.dataController.fetchAgentConfigs()

        guard !configs.isEmpty else {
            resultMessage = "没有可导出的 Agent 配置。"
            showingResultAlert = true
            return
        }

        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(configs)

            // 写入临时目录
            let tempDir = FileManager.default.temporaryDirectory
            let fileName = "agent_configs_\(Int(Date().timeIntervalSince1970)).json"
            let fileUrl = tempDir.appendingPathComponent(fileName)
            try data.write(to: fileUrl, options: .atomic)

            // 记录导出信息并弹出分享 Sheet
            exportCount = configs.count
            exportFileUrl = fileUrl
            showingExportSheet = true
        } catch {
            resultMessage = "导出失败：\(error.localizedDescription)"
            showingResultAlert = true
        }
    }

    // MARK: - 导入配置

    /// 处理文件选择器返回的结果，解码 JSON 并批量注册
    private func handleImportResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            guard url.startAccessingSecurityScopedResource() else {
                resultMessage = "无法访问所选文件。"
                showingResultAlert = true
                return
            }
            defer { url.stopAccessingSecurityScopedResource() }

            do {
                let data = try Data(contentsOf: url)

                // 修复: 原实现整体 decode [AgentConfig]，单条字段损坏会导致整批失败。
                // 改为逐条解码：先用 JSONSerialization 拆成元素数组，再对每个元素
                // 独立 decode，单条失败只跳过该条，不影响其余配置导入。
                guard let jsonArray = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                    resultMessage = "文件格式不是有效的 JSON 数组。"
                    showingResultAlert = true
                    return
                }

                guard !jsonArray.isEmpty else {
                    resultMessage = "文件中没有有效的 Agent 配置。"
                    showingResultAlert = true
                    return
                }

                let decoder = JSONDecoder()
                var importedCount = 0
                var failedCount = 0
                for element in jsonArray {
                    guard let elementData = try? JSONSerialization.data(withJSONObject: element),
                          let config = try? decoder.decode(AgentConfig.self, from: elementData) else {
                        failedCount += 1
                        continue
                    }
                    let agent = Agent(
                        id: config.id,
                        name: config.name,
                        endpoint: config.serverUrl,
                        config: config
                    )
                    appState.dataController.saveAgentConfig(config)
                    appState.agentManager.register(agent)
                    importedCount += 1
                }

                if importedCount == 0 {
                    resultMessage = "导入失败：\(failedCount) 条配置均无法解析。"
                } else if failedCount > 0 {
                    resultMessage = "成功导入 \(importedCount) 个 Agent 配置，\(failedCount) 条解析失败已跳过。"
                } else {
                    resultMessage = "成功导入 \(importedCount) 个 Agent 配置。"
                }
            } catch {
                resultMessage = "导入失败：\(error.localizedDescription)"
            }
            showingResultAlert = true

        case .failure(let error):
            resultMessage = "文件读取失败：\(error.localizedDescription)"
            showingResultAlert = true
        }
    }
}

// MARK: - AgentStatus 显示名称扩展

private extension AgentStatus {
    /// 状态的中文显示名称（用于 UI 展示）
    var displayText: String {
        switch self {
        case .online: return "在线"
        case .offline: return "离线"
        case .connecting: return "连接中"
        case .error: return "错误"
        }
    }
}

// MARK: - AgentCapability 显示名称扩展

private extension AgentCapability {
    /// 能力的简短中文显示名称（用于标签展示）
    var displayText: String {
        switch self {
        case .chat: return "对话"
        case .task: return "任务"
        case .workflow: return "工作流"
        case .mcp: return "MCP"
        case .filesystem: return "文件系统"
        case .terminal: return "终端"
        case .voice: return "语音"
        case .imageGen: return "图像生成"
        case .codeExecution: return "代码执行"
        }
    }
}

// MARK: - AgentRow

/// 单个 Agent 行视图
/// 布局：状态点 + 名称 + 活跃标识 + 类型标签 + 状态文本 + 能力标签（最多3个）
private struct AgentRow: View {
    let agent: Agent
    let isActive: Bool

    var body: some View {
        HStack(spacing: 12) {
            // 在线/离线/连接中/错误 状态圆点
            Circle()
                .fill(AppTheme.statusColors[agent.status] ?? .gray)
                .frame(width: 10, height: 10)
                .overlay {
                    // 在线状态增加呼吸光环效果
                    if agent.status == .online {
                        Circle()
                            .stroke(AppTheme.statusColors[.online] ?? .green, lineWidth: 1)
                            .frame(width: 16, height: 16)
                            .opacity(0.4)
                    }
                }

            VStack(alignment: .leading, spacing: 4) {
                // 名称 + 活跃勾选标识
                HStack(spacing: 4) {
                    Text(agent.name)
                        .font(.headline)
                        .lineLimit(1)
                    if isActive {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                            .font(.caption)
                    }
                }

                // 类型标签 + 状态文本
                HStack(spacing: 8) {
                    Text(agent.config?.type.displayName ?? "未知")
                        .font(.caption)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(.blue.opacity(0.15), in: Capsule())
                        .foregroundStyle(.blue)

                    Text(agent.status.displayText)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                // 能力标签（最多展示 3 个，超出显示 +N）
                if !agent.capabilities.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(Array(agent.capabilities.prefix(3)), id: \.self) { cap in
                            Text(cap.displayText)
                                .font(.caption2)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(.gray.opacity(0.15), in: Capsule())
                                .foregroundStyle(.secondary)
                        }
                        // 超过 3 个能力时显示剩余数量
                        if agent.capabilities.count > 3 {
                            Text("+\(agent.capabilities.count - 3)")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                    }
                }
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }
}

// MARK: - AgentFormSheet

/// Agent 添加/编辑表单 Sheet
///
/// 新建和编辑共用同一视图：
/// - **新建模式**：`existingAgent` 和 `existingConfig` 为 nil，保存时分配新 UUID
/// - **编辑模式**：传入现有 Agent 和 AgentConfig，表单预填充，保存时更新
///
/// 表单字段：名称 / 类型 / 服务器地址 / API Key / 模型 / System Prompt / 温度 / 最大 Tokens
private struct AgentFormSheet: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    /// 编辑模式时持有的目标 Agent（nil = 新建）
    let existingAgent: Agent?
    /// 编辑模式时持有的目标配置
    let existingConfig: AgentConfig?

    /// 是否为编辑模式
    private var isEditing: Bool { existingAgent != nil }

    // MARK: 表单字段状态
    @State private var name: String = ""
    @State private var type: AgentType = .openAI
    @State private var serverUrl: String = ""
    @State private var apiKey: String = ""
    @State private var model: String = ""
    @State private var systemPrompt: String = ""
    @State private var temperature: Float = 0.7
    @State private var maxTokens: Int = 4096

    // MARK: 校验错误状态（保存前由 AgentConfigValidator 校验，失败时弹窗提示并阻止落库）
    @State private var showingValidationAlert = false
    @State private var validationErrorMessage = ""

    init(existingAgent: Agent? = nil, existingConfig: AgentConfig? = nil) {
        self.existingAgent = existingAgent
        self.existingConfig = existingConfig
    }

    var body: some View {
        NavigationStack {
            Form {
                // MARK: 基本信息
                Section("基本信息") {
                    TextField("名称", text: $name)
                        .textContentType(.name)

                    Picker("类型", selection: $type) {
                        ForEach(AgentType.allCases, id: \.self) { t in
                            Text(t.displayName).tag(t)
                        }
                    }
                }

                // MARK: 连接配置
                Section("连接") {
                    TextField("服务器地址", text: $serverUrl)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                    SecureField("API Key", text: $apiKey)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                    TextField("模型", text: $model)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }

                // MARK: 高级配置
                Section("高级配置") {
                    // System Prompt 多行输入（3~8 行）
                    TextField("System Prompt", text: $systemPrompt, axis: .vertical)
                        .lineLimit(3...8)

                    // 温度滑块（0 ~ 2，步进 0.1，默认 0.7）
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("温度")
                            Spacer()
                            Text(String(format: "%.1f", temperature))
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                        Slider(value: $temperature, in: 0...2, step: 0.1)
                    }

                    // 最大 Tokens 步进器（256 ~ 128000，步进 256，默认 4096）
                    Stepper(
                        "最大 Tokens: \(maxTokens)",
                        value: $maxTokens,
                        in: 256...128_000,
                        step: 256
                    )
                }

                // MARK: 安全说明
                Section {
                    Text("API Key 在保存时将自动加密存储。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(isEditing ? "编辑 Agent" : "添加 Agent")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            // 编辑模式：在视图出现时预填充已有配置值
            // SW-M2: 使用 .task 替代 .onAppear，由 SwiftUI 管理生命周期，
            // 视图销毁时自动取消，避免页面快速切换时的孤儿任务
            .task {
                guard let config = existingConfig else { return }
                name = config.name
                type = config.type
                serverUrl = config.serverUrl
                apiKey = config.apiKey
                model = config.model
                systemPrompt = config.systemPrompt
                temperature = config.temperature
                maxTokens = config.maxTokens
            }
            // 校验失败提示：展示首条错误消息，错误详情已写入 appState.lastValidationError
            .alert("无法保存", isPresented: $showingValidationAlert) {
                Button("确定", role: .cancel) {}
            } message: {
                Text(validationErrorMessage)
            }
        }
    }

    // MARK: - 保存

    /// 保存 Agent 配置到持久化层并注册到运行时管理器
    ///
    /// 流程：
    /// 1. 构造 `AgentConfig`（编辑模式复用原 id，新建模式分配新 UUID）
    /// 2. 调用 `AgentConfigValidator.validate` 校验；失败时写入 `appState.lastValidationError`
    ///    并弹窗提示首条错误，阻止落库
    /// 3. 通过 `DataController.saveAgentConfig` 持久化（apiKey 自动加密）
    /// 4. 构造 `Agent` 运行时实例
    /// 5. 通过 `AgentManager.register` 注册（已存在则更新）
    private func save() {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        guard !trimmedName.isEmpty else { return }

        // 统一构造待校验配置：编辑模式复用原 agent id，新建模式分配新 UUID
        let configId = isEditing ? (existingAgent?.id ?? UUID().uuidString) : UUID().uuidString
        let config = AgentConfig(
            id: configId,
            name: trimmedName,
            type: type,
            serverUrl: serverUrl,
            apiKey: apiKey,
            model: model,
            systemPrompt: systemPrompt,
            temperature: temperature,
            maxTokens: maxTokens
        )

        // 保存前校验：失败则回填 appState.lastValidationError 并弹窗提示，不落库
        let validationResult = AgentConfigValidator.validate(config)
        guard validationResult.isValid else {
            appState.lastValidationError = validationResult
            validationErrorMessage = validationResult.errors.first?.message ?? "配置校验失败"
            showingValidationAlert = true
            return
        }

        if isEditing, let agent = existingAgent {
            // 编辑模式：持久化 + 构造更新后的 Agent 实例并重新注册
            appState.dataController.saveAgentConfig(config)
            var updatedAgent = agent
            updatedAgent.name = trimmedName
            updatedAgent.endpoint = serverUrl
            updatedAgent.config = config
            appState.agentManager.register(updatedAgent)
        } else {
            // 新建模式：持久化 + 注册新 Agent 实例
            let agent = Agent(
                id: config.id,
                name: config.name,
                endpoint: config.serverUrl,
                config: config
            )
            appState.dataController.saveAgentConfig(config)
            appState.agentManager.register(agent)
        }

        dismiss()
    }
}

// MARK: - ExportShareSheet

/// 导出分享 Sheet
///
/// 展示导出成功信息并提供 ShareLink，用户可通过系统分享面板
/// 将 JSON 文件发送到 AirDrop、邮件、信息等渠道。
private struct ExportShareSheet: View {
    let url: URL
    let count: Int

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()

                // 导出成功图标
                Image(systemName: "square.and.arrow.up")
                    .font(.system(size: 48))
                    .foregroundStyle(.blue)

                // 导出结果描述
                Text("导出成功")
                    .font(.title2.bold())

                Text("已导出 \(count) 个 Agent 配置为 JSON 文件")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                // 系统分享按钮
                ShareLink(item: url) {
                    Label("分享文件", systemImage: "share")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.horizontal, 32)

                Spacer()
            }
            .navigationTitle("导出 Agent 配置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}