import SwiftUI

/// 设置视图
/// 包含 Agent 默认配置、字体大小、外观主题、端到端加密、数据管理、智能通知、关于、危险操作等分组
/// 对齐 Android SettingsScreen，移除 MCP 服务器管理（已独立为 McpView）
struct SettingsView: View {
    // 全局应用状态
    @Environment(AppState.self) private var appState
    // 检测当前系统配色方案
    @Environment(\.colorScheme) private var systemColorScheme

    // ============ Agent 默认配置 ============
    @AppStorage("defaultModel") private var defaultModel: String = "gpt-4"
    // CI-fix: 原 `temperature: Float = 0.7` 在 Xcode 16.4 / Swift 6 strict
    // concurrency 下触发 "@AppStorage 宏展开 no exact matches in call to
    // initializer"。改为 `Double` — `Slider` 与 `String(format:)` 均有 Double
    // 重载，行为等价；同时 Double 是 SwiftUI 中浮点 @AppStorage 的更常用类型。
    @AppStorage("temperature") private var temperature: Double = 0.7
    @AppStorage("maxTokens") private var maxTokens: Int = 4096

    // ============ 字体大小 ============
    // CI-fix: 原 `@AppStorage("fontSize") private var fontSize: FontSize = .medium`
    // 在 Xcode 16.4 下报 "no exact matches in call to initializer"（即使 FontSize
    // 是 String-backed RawRepresentable，宏展开在该 SDK 版本下无法解析枚举类型）。
    // 改为手动 UserDefaults 桥接：@State 持有内存值，.onChange 写回 UserDefaults。
    // 与 ChatView 中的相同模式保持一致。
    @State private var fontSize: FontSize = FontSize.loadFromUserDefaults()

    // ============ 外观主题 ============
    @AppStorage("theme") private var theme: AppThemePreference = .system

    // ============ 端到端加密 ============
    @AppStorage("encryptionEnabled") private var encryptionEnabled: Bool = false
    // 出于安全考虑，passphrase 不再存 UserDefaults（明文 plist），
    // 改为 Keychain 存储（ThisDeviceOnly，不随 iCloud 备份迁移）。
    // 用 @State 持有内存副本，onChange 时写回 Keychain。
    @State private var passphrase: String = ""
    @State private var showPassphrase: Bool = false

    // ============ 数据管理 ============
    @State private var exportedJSON: URL?
    @State private var isImporting: Bool = false
    @State private var importAlertMessage: String?
    @State private var showImportAlert: Bool = false

    // ============ 智能通知 ============
    @AppStorage("notifyHighPriority") private var notifyHighPriority: Bool = true
    @AppStorage("notifyMediumPriority") private var notifyMediumPriority: Bool = true
    @AppStorage("notifyLowPriority") private var notifyLowPriority: Bool = false

    // ============ 危险操作 ============
    @State private var showingClearConfirm = false

    // ============ 临时状态 ============
    @State private var showKeyCopiedToast = false

    // MARK: - Body

    var body: some View {
        NavigationStack {
            Form {
                agentDefaultConfigSection
                fontSizeSection
                appearanceSection
                encryptionSection
                dataManagementSection
                smartNotificationsSection
                aboutSection
                dangerZoneSection
            }
            .navigationTitle("设置")
            // HIG：preferredColorScheme 应只在根视图 ContentView 设置一次。
            // 在子视图重复设置会导致 sheet 切换时闪烁，并与其他场景的配色冲突。
            // 清除数据二次确认
            .alert("确认清除所有数据?", isPresented: $showingClearConfirm) {
                Button("取消", role: .cancel) {}
                Button("清除", role: .destructive) {
                    clearAllData()
                }
            } message: {
                Text("此操作不可恢复,将删除所有会话、Agent、任务及配置数据。")
            }
            // 导入结果提示
            .alert("导入结果", isPresented: $showImportAlert) {
                Button("确定", role: .cancel) {}
            } message: {
                Text(importAlertMessage ?? "")
            }
            // 文件导入器
            .fileImporter(
                isPresented: $isImporting,
                allowedContentTypes: [.json],
                allowsMultipleSelection: false
            ) { result in
                handleImportResult(result)
            }
            // SW-M2: 使用 .task 替代 .onAppear — Keychain 读取虽是同步 API，
            // 但 .task 由 SwiftUI 管理生命周期，统一所有视图数据加载入口
            .task {
                // 从 Keychain 加载 E2E passphrase 到内存 @State
                passphrase = KeychainManager.loadPassphrase()
            }
            .onChange(of: passphrase) { _, newValue in
                // 写回 Keychain。空串视为清除。
                if newValue.isEmpty {
                    KeychainManager.clearPassphrase()
                } else {
                    KeychainManager.savePassphrase(newValue)
                }
            }
            // CI-fix: fontSize 改为手动 UserDefaults 桥接（替代 @AppStorage）
            .onChange(of: fontSize) { _, newValue in
                newValue.saveToUserDefaults()
            }
            // 剪贴板复制提示
            .overlay {
                if showKeyCopiedToast {
                    ToastView(message: "已复制到剪贴板")
                        .animation(.easeInOut(duration: 0.3), value: showKeyCopiedToast)
                        .onAppear {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                withAnimation { showKeyCopiedToast = false }
                            }
                        }
                }
            }
        }
    }

    // MARK: - 计算属性

    // preferredColorSchemeBinding 已移除：preferredColorScheme 统一由根视图 ContentView 设置

    // MARK: - 功能分区

    /// 1. Agent 默认配置
    private var agentDefaultConfigSection: some View {
        Section("Agent 默认配置") {
            TextField("默认模型", text: $defaultModel)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("温度")
                    Spacer()
                    Text(String(format: "%.1f", temperature))
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Slider(value: $temperature, in: 0...2, step: 0.1)
            }

            Stepper("最大 Tokens: \(maxTokens)", value: $maxTokens, in: 256...32768, step: 256)
        }
    }

    /// 2. 字体大小
    private var fontSizeSection: some View {
        Section("字体大小") {
            Picker("字体大小", selection: $fontSize) {
                ForEach(FontSize.allCases) { size in
                    Text(size.displayName).tag(size)
                }
            }
        }
    }

    /// 3. 外观主题
    private var appearanceSection: some View {
        Section("外观") {
            Picker("主题", selection: $theme) {
                Text("浅色").tag(AppThemePreference.light)
                Text("深色").tag(AppThemePreference.dark)
                Text("跟随系统").tag(AppThemePreference.system)
            }

            // 当前生效方案指示
            HStack {
                Text("当前生效")
                Spacer()
                Text(effectiveColorSchemeText)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// 4. 端到端加密（增强）
    private var encryptionSection: some View {
        Section("端到端加密") {
            Toggle("启用加密", isOn: $encryptionEnabled)

            if encryptionEnabled {
                // 密码短语输入
                HStack {
                    if showPassphrase {
                        TextField("密码短语", text: $passphrase)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    } else {
                        SecureField("密码短语", text: $passphrase)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                    // 显示/隐藏密码按钮
                    Button {
                        showPassphrase.toggle()
                    } label: {
                        Image(systemName: showPassphrase ? "eye.slash" : "eye")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(showPassphrase ? "隐藏密码" : "显示密码")
                }

                // 操作按钮行
                HStack(spacing: 12) {
                    // 显示密钥 → 复制到剪贴板
                    Button {
                        copyPassphraseToClipboard()
                    } label: {
                        Label("显示密钥", systemImage: "doc.on.doc")
                            .font(.subheadline)
                    }
                    .buttonStyle(.bordered)

                    // 重新生成 → 清空旧值
                    Button {
                        regeneratePassphrase()
                    } label: {
                        Label("重新生成", systemImage: "arrow.clockwise")
                            .font(.subheadline)
                    }
                    .buttonStyle(.bordered)

                    // 导入 → 从剪贴板粘贴
                    Button {
                        importPassphraseFromClipboard()
                    } label: {
                        Label("导入", systemImage: "doc.badge.plus")
                            .font(.subheadline)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    /// 5. 数据管理
    private var dataManagementSection: some View {
        Section("数据管理") {
            // 导出聊天历史
            Button {
                exportChatHistory()
            } label: {
                Label("导出聊天历史", systemImage: "square.and.arrow.up")
            }

            // 导入聊天历史
            Button {
                isImporting = true
            } label: {
                Label("导入聊天历史", systemImage: "square.and.arrow.down")
            }

            // 数据洞察
            NavigationLink {
                InsightsView()
            } label: {
                Label("数据洞察", systemImage: "chart.bar.xaxis")
            }

            // 性能监控
            NavigationLink {
                PerformanceView()
            } label: {
                Label("性能监控", systemImage: "gauge.medium")
            }
        }
    }

    /// 6. 智能通知
    private var smartNotificationsSection: some View {
        Section("智能通知") {
            Toggle("高优先级通知", isOn: $notifyHighPriority)
            Toggle("中优先级通知", isOn: $notifyMediumPriority)
            Toggle("低优先级通知", isOn: $notifyLowPriority)
        }
    }

    /// 7. 关于
    private var aboutSection: some View {
        Section("关于") {
            HStack {
                Text("应用版本")
                Spacer()
                // 动态读取 Bundle 版本号(P2-12),回退到 "2.2.0"
                Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "2.2.0")
                    .foregroundStyle(.secondary)
            }
            HStack {
                Text("协议版本")
                Spacer()
                Text("v2")
                    .foregroundStyle(.secondary)
            }
            HStack {
                Text("构建号")
                Spacer()
                Text("2")
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// 8. 危险操作
    private var dangerZoneSection: some View {
        Section {
            Button(role: .destructive) {
                showingClearConfirm = true
            } label: {
                Label("清除所有数据", systemImage: "trash")
            }
        }
    }

    // MARK: - 当前配色方案文字

    private var effectiveColorSchemeText: String {
        switch theme {
        case .light: return "浅色模式"
        case .dark: return "深色模式"
        case .system:
            return systemColorScheme == .dark ? "深色模式（跟随系统）" : "浅色模式（跟随系统）"
        }
    }

    // MARK: - 加密操作

    /// 复制密码短语到剪贴板，30 秒后自动失效（防止永久驻留被其他 app 读取）
    private func copyPassphraseToClipboard() {
        guard !passphrase.isEmpty else { return }
        let pb = UIPasteboard.general
        pb.setItems(
            [[UIPasteboard.typeAutomatic: passphrase]],
            options: [.expirationDate: Date().addingTimeInterval(30)]
        )
        showKeyCopiedToast = true
    }

    /// 重新生成密码短语（清空旧值，用户需输入新值）
    private func regeneratePassphrase() {
        passphrase = ""
    }

    /// 从剪贴板导入密码短语。仅接受长度 ≥ 8 的非空字符串，避免误把无关内容设为密钥。
    private func importPassphraseFromClipboard() {
        guard let clipboardString = UIPasteboard.general.string,
              !clipboardString.isEmpty else {
            return
        }
        // 长度校验，防止用户误把短字符串或纯空格设为 E2E 主密钥
        guard clipboardString.count >= 8 else {
            importAlertMessage = "剪贴板内容过短，密码短语至少 8 字符"
            showImportAlert = true
            return
        }
        passphrase = clipboardString
        // 立即清除剪贴板，防止后续被其他 app 读取
        UIPasteboard.general.items = []
    }

    // MARK: - 数据导出

    /// 导出所有会话+消息为 JSON 文件，使用 ShareLink 分享
    private func exportChatHistory() {
        let sessions = appState.dataController.fetchSessions()

        // 构建可编码的导出结构
        var exportData: [[String: Any]] = []
        for session in sessions {
            let messages = appState.dataController.fetchMessages(sessionId: session.id)
            let messageDicts = messages.map { msg -> [String: Any] in
                [
                    "id": msg.id,
                    "role": msg.role.rawValue,
                    "content": msg.content,
                    "timestamp": msg.timestamp,
                    "status": msg.status.rawValue
                ]
            }
            exportData.append([
                "session": [
                    "id": session.id,
                    "title": session.title,
                    "createdAt": session.createdAt,
                    "updatedAt": session.updatedAt
                ],
                "messages": messageDicts
            ])
        }

        do {
            let data = try JSONSerialization.data(withJSONObject: exportData, options: [.prettyPrinted, .sortedKeys])
            // 写入临时文件供 ShareLink 使用
            let tempDir = FileManager.default.temporaryDirectory
            let fileURL = tempDir.appendingPathComponent("agenthub_chat_export_\(Int(Date().timeIntervalSince1970)).json")
            try data.write(to: fileURL)
            exportedJSON = fileURL
            // 使用 UIActivityViewController 分享
            shareFile(url: fileURL)
        } catch {
            importAlertMessage = "导出失败: \(error.localizedDescription)"
            showImportAlert = true
        }
    }

    /// 使用系统分享面板分享文件
    private func shareFile(url: URL) {
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let window = windowScene.windows.first,
              let rootVC = window.rootViewController?.presentedViewController ?? window.rootViewController else {
            return
        }

        let activityVC = UIActivityViewController(
            activityItems: [url],
            applicationActivities: nil
        )

        // iPad 需要设置 popoverPresentationController
        if let popOver = activityVC.popoverPresentationController {
            popOver.sourceView = window.rootViewController?.view
            popOver.sourceRect = CGRect(x: window.bounds.midX, y: window.bounds.maxY, width: 0, height: 0)
        }

        rootVC.present(activityVC, animated: true)
    }

    // MARK: - 数据导入

    /// 处理文件导入结果
    private func handleImportResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            do {
                let data = try Data(contentsOf: url)
                let json = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] ?? []

                var importedSessions = 0
                var importedMessages = 0

                for item in json {
                    guard let sessionDict = item["session"] as? [String: Any],
                          let sessionId = sessionDict["id"] as? String,
                          let title = sessionDict["title"] as? String,
                          let createdAt = sessionDict["createdAt"] as? Int64,
                          let updatedAt = sessionDict["updatedAt"] as? Int64 else {
                        continue
                    }

                    // 保存会话
                    let session = Session(
                        id: sessionId,
                        title: title,
                        createdAt: createdAt,
                        updatedAt: updatedAt
                    )
                    appState.dataController.saveSession(session)
                    importedSessions += 1

                    // 保存消息
                    if let messageDicts = item["messages"] as? [[String: Any]] {
                        for msgDict in messageDicts {
                            guard let msgId = msgDict["id"] as? String,
                                  let roleStr = msgDict["role"] as? String,
                                  let content = msgDict["content"] as? String,
                                  let timestamp = msgDict["timestamp"] as? Int64,
                                  let statusStr = msgDict["status"] as? String else {
                                continue
                            }

                            let role = MessageRole(rawValue: roleStr) ?? .user
                            let status = MessageStatus(rawValue: statusStr) ?? .sent

                            let message = Message(
                                id: msgId,
                                sessionId: sessionId,
                                role: role,
                                content: content,
                                timestamp: timestamp,
                                status: status
                            )
                            appState.dataController.saveMessage(message)
                            importedMessages += 1
                        }
                    }
                }

                importAlertMessage = "导入成功: \(importedSessions) 个会话, \(importedMessages) 条消息"
            } catch {
                importAlertMessage = "导入失败: \(error.localizedDescription)"
            }
            showImportAlert = true

        case .failure(let error):
            importAlertMessage = "文件读取失败: \(error.localizedDescription)"
            showImportAlert = true
        }
    }

    // MARK: - 清除数据

    /// 清除所有持久化数据:会话(含消息)/ 任务 / Agent 配置,并同步清理各 Manager 内存状态
    private func clearAllData() {
        for session in appState.dataController.fetchSessions() {
            appState.dataController.deleteMessages(sessionId: session.id)
            appState.dataController.deleteSession(session.id)
        }
        for task in appState.dataController.fetchTasks() {
            appState.dataController.deleteTask(task.id)
        }
        for config in appState.dataController.fetchAgentConfigs() {
            appState.dataController.deleteAgentConfig(config.id)
        }

        // 清理内存状态:持久化数据已清空,各 Manager 的内存缓存也需同步,
        // 否则 UI 仍会展示已删除的会话/任务,重启后才会刷新。
        // 说明:各 Manager 的集合均为 private(set),无法直接 removeAll,
        // 改用遍历调用对应的删除方法;Agent 配置保留(注释:注意保留 Agent 配置)。
        let sessionIds = appState.sessionManager.sessions.map(\.id)
        for id in sessionIds {
            appState.sessionManager.deleteSession(id)
        }
        let taskIds = appState.taskManager.tasks.map(\.id)
        for id in taskIds {
            appState.taskManager.deleteTask(id)
        }
    }
}

// MARK: - 字体大小枚举

/// 字体大小偏好
enum FontSize: String, CaseIterable, Identifiable {
    case small = "小"
    case medium = "中"
    case large = "大"

    var id: String { rawValue }

    /// 显示名称
    var displayName: String { rawValue }

    /// 映射到 SwiftUI Font
    var font: Font {
        switch self {
        case .small: return .caption
        case .medium: return .body
        case .large: return .title3
        }
    }

    /// UserDefaults 存储 key
    static let userDefaultsKey = "fontSize"

    /// 从 UserDefaults 读取字体大小偏好，缺失或非法值时回退到 `.medium`。
    /// 用于替代 `@AppStorage("fontSize")`（Xcode 16.4 下对 RawRepresentable
    /// 枚举的宏展开存在 bug，编译报 "no exact matches in call to initializer"）。
    static func loadFromUserDefaults() -> FontSize {
        let raw = UserDefaults.standard.string(forKey: userDefaultsKey) ?? FontSize.medium.rawValue
        return FontSize(rawValue: raw) ?? .medium
    }

    /// 将当前值写回 UserDefaults。
    func saveToUserDefaults() {
        UserDefaults.standard.set(rawValue, forKey: Self.userDefaultsKey)
    }
}

// MARK: - 主题偏好枚举

/// 外观主题偏好
enum AppThemePreference: String, CaseIterable, Identifiable {
    case light = "light"
    case dark = "dark"
    case system = "system"

    var id: String { rawValue }
}

// MARK: - Toast 提示视图

/// 简单的 Toast 提示视图，用于"已复制到剪贴板"等反馈
private struct ToastView: View {
    let message: String

    var body: some View {
        VStack {
            Spacer()
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.black.opacity(0.8), in: Capsule())
                .padding(.bottom, 60)
        }
        .allowsHitTesting(false)
        .transition(.opacity)
    }
}

// MARK: - 性能监控视图（Settings 中跳转目标）

/// 性能监控详情页
/// 展示 PerformanceMonitor 采集的实时指标
struct PerformanceView: View {
    @State private var monitor = PerformanceMonitor()

    var body: some View {
        List {
            // 消息延迟
            Section("消息延迟") {
                MetricRow(
                    title: "平均延迟",
                    value: String(format: "%.1f ms", monitor.metrics.avgMessageLatencyMs),
                    icon: "clock.arrow.2.circlepath"
                )
                MetricRow(
                    title: "连接质量",
                    value: monitor.metrics.connectionQuality.capitalized,
                    icon: "antenna.radiowaves.left.and.right",
                    color: connectionQualityColor(monitor.metrics.connectionQuality)
                )
            }

            // 系统资源
            Section("系统资源") {
                MetricRow(
                    title: "内存使用",
                    value: String(format: "%.1f MB", monitor.metrics.memoryUsageMB),
                    icon: "memorychip"
                )
            }

            // 统计
            Section("统计") {
                MetricRow(
                    title: "总消息数",
                    value: "\(monitor.metrics.totalMessages)",
                    icon: "message"
                )
                MetricRow(
                    title: "运行时间",
                    value: formatUptime(monitor.metrics.uptimeMinutes),
                    icon: "timer"
                )
                MetricRow(
                    title: "活跃连接",
                    value: "\(monitor.metrics.activeConnections)",
                    icon: "network"
                )
            }
        }
        .navigationTitle("性能监控")
        // 离开视图时清理定时器
        .onDisappear {
            // monitor 会在 deinit 时自动清理
        }
    }

    /// 根据连接质量返回对应颜色
    private func connectionQualityColor(_ quality: String) -> Color {
        switch quality {
        case "excellent": return .green
        case "good": return .blue
        case "fair": return .yellow
        case "poor": return .red
        default: return .gray
        }
    }

    /// 格式化运行时间
    private func formatUptime(_ minutes: Int) -> String {
        if minutes < 60 {
            return "\(minutes) 分钟"
        } else if minutes < 1440 {
            return "\(minutes / 60) 小时 \(minutes % 60) 分钟"
        } else {
            return "\(minutes / 1440) 天 \(minutes / 60) 小时"
        }
    }
}

// MARK: - 指标行组件

/// 单个指标展示行
private struct MetricRow: View {
    let title: String
    let value: String
    let icon: String
    var color: Color = .primary

    var body: some View {
        HStack {
            Image(systemName: icon)
                .foregroundStyle(color)
                .frame(width: 24)
            Text(title)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
    }
}
