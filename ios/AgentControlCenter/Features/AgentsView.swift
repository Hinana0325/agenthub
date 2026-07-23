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

    // MARK: - 加载/错误态(v5.0 P0)
    /// 首屏骨架屏开关：true 时渲染 AgentCardSkeletonRow×4 占位
    @State private var isLoading: Bool = true
    /// 加载错误信息：非 nil 时覆盖列表渲染 ErrorStateView
    @State private var errorMessage: String? = nil

    // MARK: - Body

    var body: some View {
        NavigationStack {
            List {
                if isLoading {
                    // v5.0 P0: 首屏骨架屏占位
                    Section {
                        SkeletonList(repeat: 4) { AgentCardSkeletonRow() }
                    }
                } else {
                    // MARK: 当前活跃 Agent
                    if let active = appState.agentManager.activeAgent {
                        Section(String(localized: "agent.active")) {
                            AgentRow(agent: active, isActive: true)
                        }
                    }

                    // MARK: 所有 Agent
                    Section(String(localized: "agent.all")) {
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
                                    Label(String(localized: "agent.edit"), systemImage: "pencil")
                                }

                                Divider()

                                Button(role: .destructive) {
                                    agentToDelete = agent
                                    showingDeleteAlert = true
                                } label: {
                                    Label(String(localized: "agent.delete"), systemImage: "trash")
                                }
                            }
                            // 左滑：设为活跃
                            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                Button {
                                    HapticFeedback.medium()
                                    appState.agentManager.setActive(agentId: agent.id)
                                } label: {
                                    Label(String(localized: "agent.set_active"), systemImage: "star.fill")
                                }
                                .tint(.orange)
                            }
                            // 右滑：删除（需二次确认）
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    agentToDelete = agent
                                    showingDeleteAlert = true
                                } label: {
                                    Label(String(localized: "agent.delete"), systemImage: "trash")
                                }
                            }
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .animation(.easeInOut(duration: 0.25), value: appState.agentManager.agents.count)
            .navigationTitle("Agent")
            // 空状态占位视图
            .overlay {
                if let errorMessage {
                    // v5.0 P0: 加载错误时覆盖列表展示 ErrorStateView + onRetry 重载
                    ErrorStateView(
                        icon: "cpu",
                        title: String(localized: "common.load_failed"),
                        message: errorMessage,
                        onRetry: { reloadAgents() }
                    )
                    .background(AppTheme.backgroundColor)
                } else if !isLoading && appState.agentManager.agents.isEmpty {
                    ContentUnavailableView(
                        String(localized: "agent.empty.title"),
                        systemImage: "cpu",
                        description: Text(String(localized: "agent.empty.description_alt"))
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
                            Label(String(localized: "agent.form.import"), systemImage: "square.and.arrow.down")
                        }
                        Button {
                            exportConfigs()
                        } label: {
                            Label(String(localized: "agent.export_all"), systemImage: "square.and.arrow.up")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .accessibilityLabel(String(localized: "accessibility.more_actions"))
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
                    .accessibilityLabel(String(localized: "accessibility.add_agent"))
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
            .alert(String(localized: "agent.delete.confirm.title"), isPresented: $showingDeleteAlert) {
                Button(String(localized: "common.cancel"), role: .cancel) {
                    agentToDelete = nil
                }
                Button(String(localized: "common.delete"), role: .destructive) {
                    if let agent = agentToDelete {
                        deleteAgent(agent)
                    }
                    agentToDelete = nil
                }
            } message: {
                if let agent = agentToDelete {
                    Text(String(format: String(localized: "agent.delete.confirm.message"), agent.name))
                }
            }
            // MARK: Alert: 操作结果提示
            .alert(String(localized: "common.notice"), isPresented: $showingResultAlert) {
                Button(String(localized: "common.ok"), role: .cancel) {}
            } message: {
                Text(resultMessage)
            }
            // v5.0 P0: 首屏加载骨架屏入口
            .task {
                if isLoading {
                    await loadAgents()
                }
            }
        }
    }

    // MARK: - 加载(v5.0 P0)

    /// 加载 Agent 列表。
    /// Agent 数据由 `appState.agentManager` 持有（已在 App 启动时从 SwiftData 加载），
    /// 此处仅做骨架屏过渡 + 错误兜底。
    private func loadAgents() async {
        // 短暂展示骨架屏，让用户感知「正在加载」
        try? await Task.sleep(nanoseconds: 250_000_000)
        // 当前数据源是同步可读的 @Observable 状态，无抛错路径；
        // 若未来切换为远程 API，在此处 do/catch 并设置 errorMessage
        isLoading = false
    }

    /// 错误重试入口：重置状态后重新加载（onRetry 闭包要求 () -> Void）
    private func reloadAgents() {
        isLoading = true
        errorMessage = nil
        Task { await loadAgents() }
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
            resultMessage = String(localized: "agent.export.empty")
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
            resultMessage = String(format: String(localized: "error.export.failed"), error.localizedDescription)
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
                resultMessage = String(localized: "agent.import.access_error")
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
                    resultMessage = String(localized: "agent.import.invalid_json")
                    showingResultAlert = true
                    return
                }

                guard !jsonArray.isEmpty else {
                    resultMessage = String(localized: "agent.import.empty")
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
                    resultMessage = String(format: String(localized: "agent.import.all_failed"), failedCount)
                } else if failedCount > 0 {
                    resultMessage = String(format: String(localized: "agent.import.partial"), importedCount, failedCount)
                } else {
                    resultMessage = String(format: String(localized: "agent.import.success"), importedCount)
                }
            } catch {
                resultMessage = String(format: String(localized: "error.import.failed"), error.localizedDescription)
            }
            showingResultAlert = true

        case .failure(let error):
            resultMessage = String(format: String(localized: "agent.import.read_error"), error.localizedDescription)
            showingResultAlert = true
        }
    }
}

// MARK: - AgentStatus 显示名称扩展

private extension AgentStatus {
    /// 状态的本地化显示名称（用于 UI 展示）
    var displayText: String {
        switch self {
        case .online: return String(localized: "agent.status.online")
        case .offline: return String(localized: "agent.status.offline")
        case .connecting: return String(localized: "agent.status.connecting")
        case .error: return String(localized: "agent.status.error")
        }
    }
}

// MARK: - AgentCapability 显示名称扩展

private extension AgentCapability {
    /// 能力的简短本地化显示名称（用于标签展示）
    var displayText: String {
        switch self {
        case .chat: return String(localized: "agent.capability.chat")
        case .task: return String(localized: "agent.capability.task")
        case .workflow: return String(localized: "agent.capability.workflow")
        case .mcp: return String(localized: "agent.capability.mcp")
        case .filesystem: return String(localized: "agent.capability.filesystem")
        case .terminal: return String(localized: "agent.capability.terminal")
        case .voice: return String(localized: "agent.capability.voice")
        case .imageGen: return String(localized: "agent.capability.imageGen")
        case .codeExecution: return String(localized: "agent.capability.codeExecution")
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
            // Agent 类型图标（按类型语义化展示，对应 Android AgentTypeUi.icon）
            Image(systemName: AgentTypeUI.icon(for: agent.config?.type ?? .hermes))
                .font(.title2)
                .foregroundStyle(.secondary)
                .frame(width: 28)
                .accessibilityLabel(String(localized: "accessibility.agent_type_icon"))

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
                .accessibilityLabel(String(localized: "accessibility.status_dot"))

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
                    Text(agent.config?.type.displayName ?? String(localized: "agent.type.unknown"))
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

    // MARK: 校验错误状态
    // showingValidationAlert / validationErrorMessage 仅作为兜底展示首条错误弹窗
    @State private var showingValidationAlert = false
    @State private var validationErrorMessage = ""
    // 字段级错误回填：本地持有当前校验结果，按字段名渲染行内红色提示，
    // 避免污染 appState.lastValidationError 全局状态。sheet 重新打开时清空。
    @State private var validationErrors: ConfigValidationResult?

    init(existingAgent: Agent? = nil, existingConfig: AgentConfig? = nil) {
        self.existingAgent = existingAgent
        self.existingConfig = existingConfig
    }

    // MARK: 字段级错误回填辅助
    /// 渲染单条行内错误提示（红色三角图标 + 错误消息，caption 字号）
    @ViewBuilder
    private func inlineErrorView(for field: String) -> some View {
        if let message = validationErrors?.errorFor(field) {
            Label(message, systemImage: "exclamationmark.triangle")
                .foregroundStyle(.red)
                .font(.caption)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// serverUrl 占位提示：优先使用 AgentTypeUI 的类型专属提示，为空时回退通用文案
    private var serverUrlFieldPlaceholder: String {
        let placeholder = AgentTypeUI.serverUrlPlaceholder(for: type)
        return placeholder.isEmpty ? String(localized: "agent.form.server.placeholder") : placeholder
    }

    var body: some View {
        NavigationStack {
            Form {
                // MARK: 基本信息
                Section(String(localized: "agent.form.section.basic")) {
                    TextField(String(localized: "agent.form.name"), text: $name)
                        .textContentType(.name)
                    inlineErrorView(for: AgentConfigValidator.Field.name)

                    Picker(String(localized: "agent.form.type"), selection: $type) {
                        ForEach(AgentType.allCases, id: \.self) { t in
                            Text(t.displayName).tag(t)
                        }
                    }
                    // 切换类型时预填合理默认值（仅填充空白 / 默认字段，不覆盖用户已输入内容）
                    .onChange(of: type) { _, newType in
                        var draft = AgentConfig(
                            name: name,
                            type: newType,
                            serverUrl: serverUrl,
                            apiKey: apiKey,
                            model: model,
                            systemPrompt: systemPrompt,
                            temperature: temperature,
                            maxTokens: maxTokens
                        )
                        draft = AgentTypeUI.withDefaults(for: draft)
                        serverUrl = draft.serverUrl
                        model = draft.model
                        systemPrompt = draft.systemPrompt
                        temperature = draft.temperature
                        maxTokens = draft.maxTokens
                    }
                }

                // MARK: 连接配置
                // 当 type == .localModel 时，serverUrl / apiKey 字段不展示（豁免校验），
                // 仅保留 model 字段。LocalModel 走 LocalModelManager，无需远程地址。
                Section(String(localized: "agent.form.section.connection")) {
                    if type != .localModel {
                        TextField(serverUrlFieldPlaceholder, text: $serverUrl)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        inlineErrorView(for: AgentConfigValidator.Field.serverUrl)

                        SecureField(String(localized: "agent.form.apiKey"), text: $apiKey)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        inlineErrorView(for: AgentConfigValidator.Field.apiKey)
                        // 本地部署类型（ComfyUI）通常无需 API Key，给出可选提示
                        if AgentTypeUI.apiKeyOptional(for: type) {
                            Label(String(localized: "agent.form.local.no_apikey"), systemImage: "info.circle")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        // LocalModel 豁免提示（参考 SetupWizardView 文案风格）
                        Label(String(localized: "agent.form.local.exempt"), systemImage: "info.circle")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    // model 字段：标签与占位提示按类型语义化（ComfyUI → Checkpoint 文件名）
                    VStack(alignment: .leading, spacing: 4) {
                        Text(AgentTypeUI.modelLabel(for: type))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextField(AgentTypeUI.modelPlaceholder(for: type), text: $model)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                    inlineErrorView(for: AgentConfigValidator.Field.model)
                }

                // MARK: 高级配置
                Section(String(localized: "agent.form.section.advanced")) {
                    // System Prompt 多行输入（3~8 行）
                    TextField(AgentTypeUI.systemPromptLabel(for: type), text: $systemPrompt, axis: .vertical)
                        .lineLimit(3...8)
                    inlineErrorView(for: AgentConfigValidator.Field.systemPrompt)

                    // 温度滑块（0 ~ 2，步进 0.1，默认 0.7）
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(AgentTypeUI.temperatureLabel(for: type))
                            Spacer()
                            Text(String(format: "%.1f", temperature))
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                        Slider(value: $temperature, in: 0...2, step: 0.1)
                        inlineErrorView(for: AgentConfigValidator.Field.temperature)
                    }

                    // 最大 Tokens 步进器（256 ~ 128000，步进 256，默认 4096）
                    Stepper(
                        "\(AgentTypeUI.maxTokensLabel(for: type)): \(maxTokens)",
                        value: $maxTokens,
                        in: 256...128_000,
                        step: 256
                    )
                    inlineErrorView(for: AgentConfigValidator.Field.maxTokens)
                }

                // MARK: 安全说明
                Section {
                    Text(String(localized: "agent.form.security.note"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(isEditing ? String(localized: "agent.edit") : String(localized: "agent.add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "agent.form.cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "agent.form.save")) { save() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            // 视图启动：编辑模式预填充已有配置值；新建模式预填 agentDefaults 默认值
            // SW-M2: 使用 .task 替代 .onAppear，由 SwiftUI 管理生命周期，
            // 视图销毁时自动取消，避免页面快速切换时的孤儿任务
            .task {
                if let config = existingConfig {
                    // 编辑模式：从已有配置预填
                    name = config.name
                    type = config.type
                    serverUrl = config.serverUrl
                    apiKey = config.apiKey
                    model = config.model
                    systemPrompt = config.systemPrompt
                    temperature = config.temperature
                    maxTokens = config.maxTokens
                } else {
                    // 新建模式：从统一偏好仓库预填 agentDefaults（若用户在设置页设过默认值）
                    // name / serverUrl / apiKey 仍留空（需用户填）
                    let defaults = appState.preferences.configuration.agentDefaults
                    if !defaults.defaultModel.isEmpty {
                        model = defaults.defaultModel
                    }
                    // AgentDefaults.defaultTemperature 为 Double，转 Float 给表单
                    temperature = Float(defaults.defaultTemperature)
                    maxTokens = defaults.defaultMaxTokens
                }
                // sheet 每次打开时清空字段级错误回填状态，避免上一次的残留错误显示
                validationErrors = nil
            }
            // 校验失败兜底提示：展示首条错误消息，字段级错误已在各字段下方行内显示
            .alert(String(localized: "agent.form.save_failed"), isPresented: $showingValidationAlert) {
                Button(String(localized: "common.ok"), role: .cancel) {}
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

        // 保存前校验：失败则回填本地 validationErrors（驱动字段级行内提示）+
        // appState.lastValidationError（全局缓存，保留作死状态字段不删）
        // 并弹窗兜底展示首条错误，不落库
        let validationResult = AgentConfigValidator.validate(config)
        guard validationResult.isValid else {
            appState.lastValidationError = validationResult
            validationErrors = validationResult
            validationErrorMessage = validationResult.errors.first?.message ?? String(localized: "agent.form.validation_failed")
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
                Text(String(localized: "agent.export.success"))
                    .font(.title2.bold())

                Text(String(format: String(localized: "agent.export.summary"), count))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                // 系统分享按钮
                ShareLink(item: url) {
                    Label(String(localized: "agent.export.share"), systemImage: "share")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.horizontal, 32)

                Spacer()
            }
            .navigationTitle(String(localized: "agent.export.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.done")) { dismiss() }
                }
            }
        }
    }
}