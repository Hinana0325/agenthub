import SwiftUI

// MARK: - SetupWizardView
// 对应 Android SetupWizard — 首次启动配置向导（4 步）
//
// 与 OnboardingView 的区别：
// - OnboardingView：3 页纯介绍卡片，无配置交互（保留作为 fallback）
// - SetupWizardView：4 步配置向导，引导用户完成首个 Agent 的连接配置，
//   每步实时校验，最后一步通过 AgentConfigValidator 校验后保存。
//
// 不破坏现有 OnboardingView；本视图为可选升级路径，调用方可按需在
// ContentView 中替换 OnboardingView 为 SetupWizardView（通过 feature flag 或直接替换）。

/// 首次启动配置向导。
///
/// 4 步流程：
/// 1. 欢迎 + 选择 AgentType
/// 2. 填写 serverUrl（实时 URLValidator 校验）
/// 3. 填写 apiKey（SecureField）+ model（TextField）
/// 4. 测试连接 + 保存（AgentConfigValidator 校验后落库）
struct SetupWizardView: View {

    /// 完成回调：保存成功后由调用方切换到主界面
    let onComplete: () -> Void

    @Environment(AppState.self) private var appState

    // MARK: - 步骤与表单状态

    /// 当前步骤（0...3）
    @State private var step: Int = 0

    @State private var type: AgentType = .openAI
    @State private var serverUrl: String = ""
    @State private var apiKey: String = ""
    @State private var model: String = ""
    @State private var temperature: Float = 0.7
    @State private var maxTokens: Int = 4096

    // MARK: - 校验 / 保存状态

    /// 当前步骤的即时错误提示（非校验失败总结，仅用于单步阻断）
    @State private var stepErrorMessage: String?
    /// 保存阶段的错误提示（弹窗展示）
    @State private var saveErrorMessage: String?
    @State private var showingSaveError: Bool = false
    @State private var isSaving: Bool = false

    // MARK: 网络连通性测试状态
    /// 测试连接状态机：idle / testing / success / failure(String)
    @State private var testConnectionState: TestState = .idle
    /// 持有测试 Task 用于取消（用户点取消或离开页面时）
    @State private var testTask: Task<Void, Never>?

    /// 网络测试状态枚举
    enum TestState: Equatable {
        case idle
        case testing
        case success
        case failure(String)
    }

    private let totalSteps = 4

    var body: some View {
        VStack(spacing: 0) {
            // 顶部进度条
            ProgressView(value: Double(step + 1), total: Double(totalSteps))
                .padding(.horizontal)
                .padding(.top, 8)

            // 步骤内容（分页）
            TabView(selection: $step) {
                stepWelcome.tag(0)
                stepServerUrl.tag(1)
                stepCredentials.tag(2)
                stepTestAndSave.tag(3)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            // 底部导航按钮
            HStack(spacing: 12) {
                if step > 0 {
                    Button("上一步") {
                        stepErrorMessage = nil
                        withAnimation { step -= 1 }
                    }
                    .buttonStyle(.bordered)
                }
                Spacer()
                if step < totalSteps - 1 {
                    Button("下一步") { advance() }
                        .buttonStyle(.borderedProminent)
                } else {
                    Button("完成") { finish() }
                        .buttonStyle(.borderedProminent)
                        .disabled(isSaving)
                }
            }
            .padding()
        }
        .navigationTitle("初始配置向导")
        .navigationBarTitleDisplayMode(.inline)
        .alert("保存失败", isPresented: $showingSaveError) {
            Button("确定", role: .cancel) {}
        } message: {
            Text(saveErrorMessage ?? "")
        }
    }

    // MARK: - Step 1: 欢迎 + 选择 AgentType

    private var stepWelcome: some View {
        VStack(spacing: AppTheme.Spacing.lg) {
            Spacer()
            Image(systemName: "cpu")
                .font(.system(size: 64))
                .foregroundStyle(AppTheme.primaryColor)
                .frame(width: 110, height: 110)
                .background(AppTheme.primaryColor.opacity(0.1), in: Circle())

            Text("配置你的第一个 Agent")
                .font(.title2.bold())

            Text("选择 Agent 类型，我们将引导你完成连接配置。可随时在设置页修改。")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, AppTheme.Spacing.lg)

            // AgentType 选择器（CaseIterable）
            Picker("Agent 类型", selection: $type) {
                ForEach(AgentType.allCases, id: \.self) { t in
                    Text(t.displayName).tag(t)
                }
            }
            .pickerStyle(.wheel)
            // 切换类型时预填合理默认值（仅填充空白 / 默认字段，不覆盖用户已输入内容）
            .onChange(of: type) { _, newType in
                var draft = AgentConfig(
                    name: "Default Agent",
                    type: newType,
                    serverUrl: serverUrl,
                    apiKey: apiKey,
                    model: model,
                    systemPrompt: "",
                    temperature: temperature,
                    maxTokens: maxTokens
                )
                draft = AgentTypeUI.withDefaults(for: draft)
                serverUrl = draft.serverUrl
                model = draft.model
                temperature = draft.temperature
                maxTokens = draft.maxTokens
            }

            Spacer()
        }
        .padding(.horizontal, AppTheme.Spacing.lg)
    }

    // MARK: - Step 2: serverUrl（实时 URLValidator 校验）

    private var stepServerUrl: some View {
        VStack(spacing: AppTheme.Spacing.lg) {
            Spacer()
            VStack(alignment: .leading, spacing: AppTheme.Spacing.md) {
                Label("服务器地址", systemImage: "network")
                    .font(.headline)

                TextField(serverUrlFieldPlaceholder, text: $serverUrl)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .textFieldStyle(.roundedBorder)

                if let err = serverUrlValidationError {
                    Label(err, systemImage: "exclamationmark.triangle")
                        .font(.caption)
                        .foregroundStyle(.orange)
                } else if !serverUrl.trimmingCharacters(in: .whitespaces).isEmpty {
                    Label("地址格式校验通过", systemImage: "checkmark.circle")
                        .font(.caption)
                        .foregroundStyle(.green)
                }
            }
            .padding(.horizontal, AppTheme.Spacing.lg)

            if type == .localModel {
                Text("LocalModel 类型豁免 URL 校验，可留空（将走 LocalModelManager）")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, AppTheme.Spacing.lg)
            }
            Spacer()
        }
    }

    /// serverUrl 实时校验错误（LocalModel 豁免）
    private var serverUrlValidationError: String? {
        if type == .localModel { return nil }
        let trimmed = serverUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil } // 留空时不立即报错，仅在「下一步」阻断
        guard URLValidator.validate(trimmed) != nil else {
            return "地址不合法或存在安全风险"
        }
        return nil
    }

    /// serverUrl 占位提示：优先使用 AgentTypeUI 的类型专属提示，为空时回退通用文案
    private var serverUrlFieldPlaceholder: String {
        let placeholder = AgentTypeUI.serverUrlPlaceholder(for: type)
        return placeholder.isEmpty ? "https://api.example.com/v1" : placeholder
    }

    /// model 占位提示：优先使用 AgentTypeUI 的类型专属提示，为空时回退通用文案
    private var modelFieldPlaceholder: String {
        let placeholder = AgentTypeUI.modelPlaceholder(for: type)
        return placeholder.isEmpty ? "gpt-4o / claude-3.5-sonnet / ..." : placeholder
    }

    // MARK: - Step 3: apiKey + model

    private var stepCredentials: some View {
        VStack(spacing: AppTheme.Spacing.lg) {
            Spacer()
            VStack(alignment: .leading, spacing: AppTheme.Spacing.md) {
                Label("API Key", systemImage: "key.fill")
                    .font(.headline)
                SecureField("sk-...", text: $apiKey)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .textFieldStyle(.roundedBorder)
                // LocalModel / ComfyUI 豁免 apiKey（与 AgentConfigValidator / AgentTypeUI 对齐）
                if !AgentTypeUI.apiKeyOptional(for: type)
                    && apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
                    Label("LocalModel / ComfyUI 之外的类型需要 API Key", systemImage: "info.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Label(AgentTypeUI.modelLabel(for: type), systemImage: "cube")
                    .font(.headline)
                TextField(modelFieldPlaceholder, text: $model)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .textFieldStyle(.roundedBorder)
            }
            .padding(.horizontal, AppTheme.Spacing.lg)
            Spacer()
        }
    }

    // MARK: - Step 4: 测试连接 + 保存

    private var stepTestAndSave: some View {
        VStack(spacing: AppTheme.Spacing.lg) {
            Spacer()
            VStack(alignment: .leading, spacing: AppTheme.Spacing.md) {
                Label("配置概览", systemImage: "list.bullet.rectangle")
                    .font(.headline)

                configSummaryRow("类型", type.displayName)
                configSummaryRow("服务器", serverUrl.isEmpty ? "（未填写）" : serverUrl)
                configSummaryRow("API Key", apiKey.isEmpty ? "（未填写）" : String(repeating: "•", count: min(apiKey.count, 12)))
                configSummaryRow(AgentTypeUI.modelLabel(for: type), model.isEmpty ? "（未填写）" : model)
                configSummaryRow(AgentTypeUI.temperatureLabel(for: type), String(format: "%.1f", temperature))
                configSummaryRow(AgentTypeUI.maxTokensLabel(for: type), "\(maxTokens)")
            }
            .padding(.horizontal, AppTheme.Spacing.lg)

            // 测试连接区块（在「完成」按钮上方）
            // LocalModel 类型无需网络测试，直接显示提示
            if type == .localModel {
                Label("LocalModel 无需网络测试", systemImage: "checkmark.circle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, AppTheme.Spacing.lg)
            } else {
                testConnectionSection
            }

            Text("点击「完成」将校验并保存配置。LocalModel 类型豁免 URL / API Key 校验。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal, AppTheme.Spacing.lg)
                .multilineTextAlignment(.center)
            Spacer()
        }
    }

    /// 测试连接区块：按钮 + 状态显示 + 重试
    @ViewBuilder
    private var testConnectionSection: some View {
        VStack(spacing: AppTheme.Spacing.sm) {
            switch testConnectionState {
            case .idle:
                Button {
                    startTestConnection()
                } label: {
                    Label("测试连接", systemImage: "antenna.radiowaves.left.and.right")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

            case .testing:
                // 测试中：ProgressView + 取消按钮
                HStack(spacing: AppTheme.Spacing.sm) {
                    ProgressView()
                    Text("测试中...")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("取消") {
                        cancelTestConnection()
                    }
                    .buttonStyle(.bordered)
                }

            case .success:
                Label("✓ 连接成功", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    startTestConnection()
                } label: {
                    Label("重新测试", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

            case .failure(let message):
                Label("✗ \(message)", systemImage: "xmark.circle.fill")
                    .foregroundStyle(.red)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    startTestConnection()
                } label: {
                    Label("重试", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(.horizontal, AppTheme.Spacing.lg)
    }

    // MARK: - 网络测试

    /// 启动网络连通性测试。
    /// 用临时 AgentConfig 构造的 endpoint 尝试一次轻量 HTTP 请求（HEAD {serverUrl}），
    /// 8 秒超时。Task 中执行网络请求，结果通过 @MainActor 隔离的 @State 回主线程更新 UI。
    private func startTestConnection() {
        // 取消上一次未完成的测试
        testTask?.cancel()
        testConnectionState = .testing
        // 复制表单当前值为本地常量，避免 Task 中读取 @State 时的并发隐患
        let urlToTest = serverUrl
        let keyToUse = apiKey
        testTask = Task {
            let result = await performPing(serverUrl: urlToTest, apiKey: keyToUse)
            // Task 取消时不更新 UI（用户已主动取消）
            if Task.isCancelled { return }
            testConnectionState = result
        }
    }

    /// 取消正在进行的测试连接
    private func cancelTestConnection() {
        testTask?.cancel()
        testTask = nil
        testConnectionState = .idle
    }

    /// 执行真实网络探测：对 {serverUrl} 发起 HEAD 请求，8 秒超时。
    /// - Returns: `.success` 表示可达（2xx/401/403/404 均视为可达，与 OpenAIHTTPTransport.probeEndpoint 一致）；
    ///   `.failure(String)` 表示不可达，附带错误描述。
    /// 注：方法为 `nonisolated` 友好（仅使用 URLSession 局部变量与 Sendable 类型），
    /// 由 @MainActor 的 Task 调用，结果通过返回值回主线程。
    private func performPing(serverUrl: String, apiKey: String) async -> TestState {
        let trimmed = serverUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        // 复用 URLValidator 做 SSRF 防护（与 OpenAIHTTPTransport.probeEndpoint 一致）
        guard let url = URLValidator.validate(trimmed, allowLocalhost: true) else {
            return .failure("地址不合法或存在安全风险")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        // 携带 Authorization 头（部分服务器对未鉴权请求返回 401，仍视为可达）
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        request.timeoutInterval = 8  // 8 秒超时

        // 独立 URLSession 实例，避免与 transport 内 session 互相干扰
        let session = URLSession(configuration: .ephemeral)
        defer { session.finishTasksAndInvalidate() }

        do {
            let (_, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return .failure("响应非 HTTP")
            }
            // 与 OpenAIHTTPTransport.probeEndpoint 一致：2xx/401/403/404 视为可达
            if (200...299).contains(http.statusCode) ||
                http.statusCode == 401 || http.statusCode == 403 || http.statusCode == 404 {
                return .success
            }
            return .failure("HTTP \(http.statusCode)")
        } catch is CancellationError {
            return .failure("测试已取消")
        } catch {
            // URLError 转友好描述
            if let urlError = error as? URLError {
                switch urlError.code {
                case .timedOut: return .failure("连接超时")
                case .notConnectedToInternet: return .failure("无网络连接")
                case .cannotFindHost, .cannotConnectToHost: return .failure("无法连接到主机")
                default: return .failure(urlError.localizedDescription)
                }
            }
            return .failure(error.localizedDescription)
        }
    }

    /// 配置概览行
    private func configSummaryRow(_ key: String, _ value: String) -> some View {
        HStack {
            Text(key)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    /// 根据 AgentType 推导默认的 AgentProtocol（与 Android SetupWizardViewModel.updateAgentType 对齐）。
    ///
    /// - Hermes / OpenClaw / OpenCode → WebSocket
    /// - OpenAI / XiaomiMiMo / OpenWebUI → HttpSSE
    /// - LocalModel → Local
    /// - ComfyUI → HttpSSE（HTTP 工作流提交 + 轮询，归类为 HTTP）
    private static func protocolType(for type: AgentType) -> AgentProtocol {
        switch type {
        case .hermes, .openClaw, .openCode:
            return .webSocket
        case .openAI, .xiaomiMiMo, .openWebUI, .comfyUI:
            return .httpSSE
        case .localModel:
            return .local
        }
    }

    // MARK: - 步骤推进 / 完成

    /// 是否允许从当前步骤前进到下一步（基于即时校验）
    private var canAdvance: Bool {
        switch step {
        case 0:
            return true
        case 1:
            // serverUrl：LocalModel 豁免；否则非空 + URLValidator 通过
            if type == .localModel { return true }
            let trimmed = serverUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            return !trimmed.isEmpty && URLValidator.validate(trimmed) != nil
        case 2:
            // model 非空；apiKey 在非 LocalModel / 非 ComfyUI 类型下非空
            // （与 AgentConfigValidator 对齐：ComfyUI 本地部署通常无认证，豁免 apiKey）
            let modelOk = !model.trimmingCharacters(in: .whitespaces).isEmpty
            let apiKeyOptional = type == .localModel || type == .comfyUI
            let apiKeyOk = apiKeyOptional || !apiKey.trimmingCharacters(in: .whitespaces).isEmpty
            return modelOk && apiKeyOk
        default:
            return true
        }
    }

    /// 推进到下一步（失败时设置 stepErrorMessage）
    private func advance() {
        stepErrorMessage = nil
        guard canAdvance else {
            switch step {
            case 1:
                stepErrorMessage = serverUrlValidationError ?? "请填写有效的服务器地址"
            case 2:
                // ComfyUI 与 LocalModel 一样豁免 apiKey，错误提示不附加 "与 API Key"
                let apiKeyOptional = type == .localModel || type == .comfyUI
                stepErrorMessage = "请填写模型名称" + (apiKeyOptional ? "" : "与 API Key")
            default:
                stepErrorMessage = "请补全当前步骤所需信息"
            }
            return
        }
        if step < totalSteps - 1 {
            withAnimation { step += 1 }
        }
    }

    /// 完成向导：构造 AgentConfig → 校验 → 落库 → 回调
    private func finish() {
        var config = AgentConfig(
            name: "Default Agent",
            type: type,
            serverUrl: serverUrl,
            apiKey: apiKey,
            model: model,
            systemPrompt: "",
            temperature: temperature,
            maxTokens: maxTokens,
            // 根据 AgentType 联动选择 protocolType（与 Android SetupWizardViewModel.updateAgentType 对齐）：
            // - Hermes / OpenClaw / OpenCode → WebSocket
            // - OpenAI / XiaomiMiMo / OpenWebUI → HttpSSE（OpenWebUI 与 OpenAI 共用 HTTP+SSE 范式）
            // - LocalModel → Local
            // - ComfyUI → HttpSSE（HTTP 工作流提交 + 轮询，归类为 HTTP；与 WebSocket 不同）
            protocolType: Self.protocolType(for: type)
        )
        config.id = "default"

        // 校验（与 AgentFormSheet.save 一致）
        let result = AgentConfigValidator.validate(config)
        guard result.isValid else {
            appState.lastValidationError = result
            saveErrorMessage = result.errors.first?.message ?? "配置校验未通过"
            showingSaveError = true
            return
        }

        isSaving = true
        // 落库 + 注册运行时实例（与 AgentsView.AgentFormSheet.save 一致）
        appState.dataController.saveAgentConfig(config)
        var agent = Agent(
            id: config.id,
            name: config.name,
            endpoint: config.serverUrl,
            config: config
        )
        agent.protocolType = config.protocolType
        appState.agentManager.register(agent)
        appState.agentManager.setActive(agentId: config.id)

        // 标记引导完成（写 onboarding_completed，ContentView @AppStorage 会感知）
        appState.preferences.update { $0.onboarding.completed = true }
        // 取消可能仍在进行的网络测试 Task，避免离开页面后孤儿请求
        testTask?.cancel()
        testTask = nil

        isSaving = false
        onComplete()
    }
}
