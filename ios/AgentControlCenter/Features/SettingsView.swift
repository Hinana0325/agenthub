import SwiftUI
import CryptoKit

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
    // 修复 1: .task 从 Keychain 加载 passphrase 后会触发 .onChange(of: passphrase)，
    // 导致每次进入设置页都把刚读出来的值再写回 Keychain 一次（无意义 I/O）。
    // 用 isPassphraseLoaded 标记「初始加载完成」，仅在用户真正修改时才写回。
    @State private var isPassphraseLoaded: Bool = false
    // 修复 7: TextField/SecureField 每输入一个字符就触发 .onChange → Keychain 写入。
    // Keychain SecItemUpdate 是系统调用，开销大；输入 20 字符 = 20 次写入。
    // 用 debounce Task 在用户停止输入 0.6s 后才写回。
    @State private var passphraseSaveTask: Task<Void, Never>?

    // ============ 数据管理 ============
    @State private var isImporting: Bool = false
    @State private var importAlertMessage: String?
    @State private var showImportAlert: Bool = false

    // ============ 智能通知 ============
    // 修复 6: 移除三个 @AppStorage("notify*") — 改为直接绑定到
    // appState.notificationManager.config 的对应字段，didSet 自动持久化。
    // 保留原 UserDefaults 键名（"notifyHighPriority" / "notifyMediumPriority" /
    // "notifyLowPriority"），由 SmartNotificationManager.NotificationConfig 管理。

    // ============ 危险操作 ============
    @State private var showingClearConfirm = false

    // ============ 设置页搜索 ============
    /// searchable 绑定的搜索文本。空串时展示全部 Section；非空时按 Section 标题
    /// / 行标题 contains 过滤（不区分大小写）。
    @State private var searchText = ""

    // ============ 敏感 Feature Flag 关闭确认 ============
    /// 待确认的敏感 flag 关闭操作（flag, 目标值）。nil 表示无待确认。
    /// 仅 E2E_ENCRYPTION / DEVICE_SYNC 从 true→false 时触发确认弹窗。
    @State private var pendingFlagToggle: (FeatureFlagManager.FeatureFlag, Bool)?

    // ============ 临时状态 ============
    @State private var showKeyCopiedToast = false

    // MARK: - Body

    var body: some View {
        NavigationStack {
            Form {
                // 搜索过滤：searchText 非空时仅展示标题包含搜索词的 Section；
                // 空串时展示全部。Form 的 ViewBuilder 支持 if 条件渲染。
                if searchText.isEmpty || "Agent 默认配置".localizedCaseInsensitiveContains(searchText) {
                    agentDefaultConfigSection
                }
                if searchText.isEmpty || "字体大小".localizedCaseInsensitiveContains(searchText) {
                    fontSizeSection
                }
                if searchText.isEmpty || "外观".localizedCaseInsensitiveContains(searchText) {
                    appearanceSection
                }
                if searchText.isEmpty || "端到端加密".localizedCaseInsensitiveContains(searchText)
                    || "加密".localizedCaseInsensitiveContains(searchText) {
                    encryptionSection
                }
                if searchText.isEmpty || "数据管理".localizedCaseInsensitiveContains(searchText) {
                    dataManagementSection
                }
                if searchText.isEmpty || "智能通知".localizedCaseInsensitiveContains(searchText)
                    || "通知".localizedCaseInsensitiveContains(searchText) {
                    smartNotificationsSection
                }
                if searchText.isEmpty || "关于".localizedCaseInsensitiveContains(searchText) {
                    aboutSection
                }
                if searchText.isEmpty || "实验性功能".localizedCaseInsensitiveContains(searchText)
                    || "Feature Flag".localizedCaseInsensitiveContains(searchText) {
                    experimentalFeaturesSection
                }
                if searchText.isEmpty || "危险操作".localizedCaseInsensitiveContains(searchText)
                    || "清除".localizedCaseInsensitiveContains(searchText) {
                    dangerZoneSection
                }
            }
            .navigationTitle("设置")
            // 设置页搜索栏：SwiftUI 标准 searchable，过滤由上面 Form 内 if 实现
            .searchable(text: $searchText, prompt: "搜索设置项")
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
            // 敏感 Feature Flag 关闭确认：仅 E2E_ENCRYPTION / DEVICE_SYNC 从 true→false 时触发
            .alert("确认关闭", isPresented: Binding(
                get: { pendingFlagToggle != nil },
                set: { if !$0 { pendingFlagToggle = nil } }
            ), presenting: pendingFlagToggle) { pending in
                Button("取消", role: .cancel) {
                    pendingFlagToggle = nil
                }
                Button("确认关闭", role: .destructive) {
                    let flag = pending.0
                    let value = pending.1
                    appState.featureFlagManager.setOverride(flag, enabled: value)
                    pendingFlagToggle = nil
                }
            } message: { _ in
                Text("关闭此功能可能影响已加密数据 / 已同步状态，确认继续？")
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
                // 从 Keychain 加载 E2E passphrase 到内存 @State。
                // 修复 1（修正实现）: 原实现把 isPassphraseLoaded = true 放在 passphrase = loaded
                // 之前，导致 onChange 触发时 guard 已通过，仍会无意义写回 Keychain。
                // 正确顺序：先赋值（此时 flag 还是 false，guard 拦截），再置 flag。
                passphrase = KeychainManager.loadPassphrase()
                isPassphraseLoaded = true
            }
            .onChange(of: passphrase) { _, newValue in
                // 修复 1: 跳过 .task 初始加载触发的 onChange
                guard isPassphraseLoaded else { return }
                // 修复 7: debounce 0.6s 后写回 Keychain，避免每个按键都触发
                // SecItemUpdate 系统调用
                passphraseSaveTask?.cancel()
                passphraseSaveTask = Task {
                    try? await Task.sleep(nanoseconds: 600_000_000)
                    if Task.isCancelled { return }
                    if newValue.isEmpty {
                        KeychainManager.clearPassphrase()
                    } else {
                        KeychainManager.savePassphrase(newValue)
                    }
                    // Keychain 写入完成后，触发 AppPreferences 重新同步 SecurityConfig.e2eKey，
                    // 使 AppState 的 e2eKey 观察回调触发，热更新活动 transport 的密钥。
                    // 注意：此调用在 Task 内（@MainActor 隔离由 SwiftUI View 继承），
                    // 安全访问 appState.preferences。
                    appState.preferences.refreshSecurityConfig()
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
                        // 修复 10: 原 .onAppear + DispatchQueue.main.asyncAfter 在连续点击
                        // "显示密钥"时，第一次的计时器仍会触发提前把 toast 置 false。
                        // 改用 .task — SwiftUI 会在 toast 视图重建时自动取消上一次 task。
                        .task {
                            try? await Task.sleep(nanoseconds: 1_500_000_000)
                            if !Task.isCancelled {
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
            // 修复 3: 关闭加密时同步清空 passphrase + Keychain，避免重启后旧密钥仍可启用加密。
            // 启用前若没有 passphrase，自动生成一个，避免「已启用但无密钥」的失效状态。
            Toggle("启用加密", isOn: $encryptionEnabled)
                .onChange(of: encryptionEnabled) { _, isOn in
                    if isOn {
                        if passphrase.isEmpty {
                            passphrase = Self.generateRandomPassphrase()
                        }
                    } else {
                        // 关闭加密：清空内存 + Keychain + 取消待写回任务
                        passphraseSaveTask?.cancel()
                        passphrase = ""
                        KeychainManager.clearPassphrase()
                    }
                    // 触发 AppPreferences 重新同步 SecurityConfig（encryptionEnabled 已写入
                    // UserDefaults，需让 configuration.security 重新读取并发布 @Observable 变更，
                    // 进而使 AppState 的 e2eKey 观察回调触发，热更新活动 transport 的密钥）。
                    appState.preferences.refreshSecurityConfig()
                }

            if encryptionEnabled {
                // 密码短语为空时提示用户先设置/生成密钥
                if passphrase.isEmpty {
                    Text("尚未设置密码短语，请点击「重新生成」或「导入」")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

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

                    // 重新生成 → 生成新的随机密钥（修复 2: 原 implementation 只清空）
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
            // 修复 6: 原 Toggle 绑定到本地 @AppStorage("notifyHighPriority") 等，
            // 但没有任何代码读取这三个键（SmartNotificationManager 用独立内存 config），
            // 形成"死 UI"。改为绑定到 Manager 的 config 字段，通过 updateConfig 持久化。
            // 注：不能用 didSet（@Observable 宏把存储属性转为计算属性，didSet 不触发）
            Toggle("高优先级通知", isOn: Binding(
                get: { appState.notificationManager.config.highPriorityEnabled },
                set: { newValue in
                    appState.notificationManager.updateConfig { $0.highPriorityEnabled = newValue }
                }
            ))
            Toggle("中优先级通知", isOn: Binding(
                get: { appState.notificationManager.config.mediumPriorityEnabled },
                set: { newValue in
                    appState.notificationManager.updateConfig { $0.mediumPriorityEnabled = newValue }
                }
            ))
            Toggle("低优先级通知", isOn: Binding(
                get: { appState.notificationManager.config.lowPriorityEnabled },
                set: { newValue in
                    appState.notificationManager.updateConfig { $0.lowPriorityEnabled = newValue }
                }
            ))
        }
    }

    /// 7. 关于
    private var aboutSection: some View {
        Section("关于") {
            HStack {
                Text("应用版本")
                Spacer()
                // 修复 8: 回退值 "2.2.0" 与当前 4.6.1 严重不符，改为 "未知"
                Text(appVersion)
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
                // 修复 8: 原硬编码 "2"，改为动态读取 CFBundleVersion
                Text(buildNumber)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// 应用版本（CFBundleShortVersionString），缺失时回退到 "未知"
    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "未知"
    }

    /// 构建号（CFBundleVersion），缺失时回退到 "未知"
    private var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "未知"
    }

    /// 8. 实验性功能（Feature Flag 覆盖入口）
    ///
    /// 遍历 `FeatureFlagManager.FeatureFlag.allCases`，每个 flag 渲染一个 Toggle，
    /// 绑定到 `featureFlagManager.isEnabled(_:)` / `setOverride(_:enabled:)`。
    /// 显示 `displayName` 作为标题、`description` 作为副标题说明。
    ///
    /// 敏感 flag（E2E_ENCRYPTION / DEVICE_SYNC）从 true→false 时先弹确认 alert，
    /// 用户确认后才真正 setOverride；其他 flag 直接切换。
    private var experimentalFeaturesSection: some View {
        Section("实验性功能") {
            ForEach(FeatureFlagManager.FeatureFlag.allCases, id: \.self) { flag in
                VStack(alignment: .leading, spacing: 4) {
                    Toggle(flag.displayName, isOn: Binding(
                        get: { appState.featureFlagManager.isEnabled(flag) },
                        set: { newValue in
                            // 敏感 flag 关闭（true→false）需二次确认，避免误关影响已加密数据 / 已同步状态
                            let isSensitive = (flag == .e2eEncryption || flag == .deviceSync)
                            if isSensitive && newValue == false {
                                pendingFlagToggle = (flag, newValue)
                            } else {
                                appState.featureFlagManager.setOverride(flag, enabled: newValue)
                            }
                        }
                    ))
                    Text(flag.description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    /// 9. 危险操作
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

    /// 重新生成密码短语（生成 32 字节随机密钥，base64 编码后赋值给 passphrase）。
    /// 修复 2: 原 implementation 只清空，与按钮名「重新生成」语义不符。
    /// 使用 CryptoKit 的 SymmetricKey 生成密码学安全随机字节，base64 编码便于复制/粘贴。
    private func regeneratePassphrase() {
        passphrase = Self.generateRandomPassphrase()
    }

    /// 生成 32 字节随机密钥的 base64 字符串。
    /// 抽取为静态方法供 Toggle 启用加密时复用（passphrase 为空时自动生成）。
    private static func generateRandomPassphrase() -> String {
        let key = SymmetricKey(size: .bits256)
        return key.withUnsafeBytes { Data($0).base64EncodedString() }
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
            // 修复 9: 移除 exportedJSON 死状态（从未被读取）
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

        // 修复 H1: 原 shareFile 后立即返回，导出临时文件（含全部会话与消息明文）
        // 永远不会被显式删除，系统低存储压力时才清理，期间敏感数据留在磁盘上。
        // 设置 completionWithItemsHandler 在分享面板关闭后删除临时文件。
        activityVC.completionWithItemsHandler = { _, _, _, _ in
            try? FileManager.default.removeItem(at: url)
        }

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
            // 修复 C2: fileImporter 返回的 URL 是 security-scoped URL。
            // 沙盒外文件（iCloud Drive / Files App）必须先 startAccessingSecurityScopedResource
            // 才能 Data(contentsOf:)，否则抛 NSCocoaErrorDomain 257（权限拒绝）。
            // 同仓库 ChatRepository.importSession / BackupManager / AgentsView 均已正确处理，
            // 此处遗漏导致从 iCloud Drive 选 JSON 100% 失败。
            let didStartAccessing = url.startAccessingSecurityScopedResource()
            defer {
                if didStartAccessing {
                    url.stopAccessingSecurityScopedResource()
                }
            }
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

    /// 清除所有持久化数据:会话(含消息)/ 任务 / Agent 配置,并同步清理各 Manager 内存状态、
    /// UserDefaults 偏好、Keychain E2E 密钥。
    ///
    /// 修复 4: 原 implementation 存在三个问题：
    /// 1. 注释说"Agent 配置保留"，但代码实际调用了 deleteAgentConfig，注释与行为不符。
    /// 2. 未清理 UserDefaults（defaultModel/temperature/theme/通知开关等），用户期望"清除所有数据"包括偏好。
    /// 3. 未清理 Keychain 中的 E2E passphrase。
    /// 修正：注释与行为对齐（Agent 配置一并清除），并补齐 UserDefaults + Keychain 清理。
    ///
    /// 优化：UserDefaults / Keychain / configuration 内存镜像统一交给
    /// `appState.preferences.clearAllPreferences()` 处理，避免直接操作 UserDefaults
    /// 绕过 preferences 实例导致 configuration 内存镜像不重置。
    private func clearAllData() {
        // 1. 持久化数据 — SwiftData
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

        // 2. 内存缓存 — 各 Manager 的集合为 private(set)，无法直接 removeAll，
        //    遍历调用 delete 方法同步清空（DB 已删，这里只清内存）
        let sessionIds = appState.sessionManager.sessions.map(\.id)
        for id in sessionIds {
            appState.sessionManager.deleteSession(id)
        }
        let taskIds = appState.taskManager.tasks.map(\.id)
        for id in taskIds {
            appState.taskManager.deleteTask(id)
        }

        // 3. 统一交给 preferences.clearAllPreferences() 清理：
        //    - UserDefaults 全部偏好键（含 mcp_servers / deviceSyncAutoSync / 通知开关等）
        //    - Keychain E2E passphrase
        //    - configuration 内存镜像重置为 default（触发 @Observable 发布）
        //    onboarding_completed 不在 allPreferenceKeys 中，不强制用户重做引导。
        appState.preferences.clearAllPreferences()

        // 4. 同步当前视图的 @AppStorage / @State（避免 UI 立刻读到旧值）
        //    顺序：先处理 passphrase（取消待写任务 + 清空），再置 encryptionEnabled = false，
        //    避免 onChange(of: encryptionEnabled) 触发时 passphraseSaveTask 仍 pending
        passphraseSaveTask?.cancel()
        passphrase = ""
        defaultModel = "gpt-4"
        temperature = 0.7
        maxTokens = 4096
        theme = .system
        encryptionEnabled = false
        // 通知开关绑定到 notificationManager.config，清 UserDefaults 后同步内存 config
        appState.notificationManager.updateConfig { config in
            config.highPriorityEnabled = true
            config.mediumPriorityEnabled = true
            config.lowPriorityEnabled = false
        }
        fontSize = .medium

        // 5. 用户反馈
        importAlertMessage = "已清除所有数据（会话/任务/Agent 配置/偏好/E2E 密钥）"
        showImportAlert = true
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

    // 修复 5: SettingsView 修改 fontSize 写入 UserDefaults 后，已打开的 ChatView
    // 不会自动刷新（其 fontSize 是 @State 初始化一次的值）。通过 NotificationCenter
    // 广播 fontSize 变更，ChatView 监听后重新 loadFromUserDefaults 刷新 @State。
    static let didChangeNotification = Notification.Name("com.agentcontrolcenter.app.fontSize.didChange")

    /// 从 UserDefaults 读取字体大小偏好，缺失或非法值时回退到 `.medium`。
    /// 用于替代 `@AppStorage("fontSize")`（Xcode 16.4 下对 RawRepresentable
    /// 枚举的宏展开存在 bug，编译报 "no exact matches in call to initializer"）。
    static func loadFromUserDefaults() -> FontSize {
        let raw = UserDefaults.standard.string(forKey: userDefaultsKey) ?? FontSize.medium.rawValue
        return FontSize(rawValue: raw) ?? .medium
    }

    /// 将当前值写回 UserDefaults，并广播变更通知让已打开的 ChatView 刷新。
    func saveToUserDefaults() {
        UserDefaults.standard.set(rawValue, forKey: Self.userDefaultsKey)
        NotificationCenter.default.post(name: Self.didChangeNotification, object: rawValue)
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
                // 黑框修复: 原固定 .white 文字 + .black.opacity(0.8) 背景，
                // 深色模式下整个屏幕已深色，黑色 toast 与背景对比度不足，
                // 视觉上像"边缘模糊的纯黑矩形"。改用自适应色：
                // - 背景用 .regularMaterial（系统自适应材质，深浅模式都有合理对比度）
                // - 文字用 .primary（深色模式白字 / 浅色模式黑字）
                .foregroundStyle(.primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule())
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
    // 修复 H3: 原 `@State private var monitor = PerformanceMonitor()` 每次进入页面都新建
    // 一个 monitor 实例，导致 totalMessages 永远显示 0、uptimeMinutes 永远从 0 开始、
    // avgMessageLatencyMs 永远 0（因为 incrementMessageCount / recordMessageLatency
    // 调用的是 appState.performanceMonitor，不是这个本地实例）。
    // 改用 @Environment(AppState.self) 读取 appState.performanceMonitor，
    // 与 App 启动时实例化并启动 5 秒定时器的那个 monitor 是同一实例。
    @Environment(AppState.self) private var appState

    private var monitor: PerformanceMonitor { appState.performanceMonitor }

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
            // 修复 H4: 原 `minutes / 60` 对 1500 分钟输出 "1 天 25 小时"。
            // 应该取扣除整天后剩余的分钟数再换算成小时。
            return "\(minutes / 1440) 天 \((minutes % 1440) / 60) 小时"
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
