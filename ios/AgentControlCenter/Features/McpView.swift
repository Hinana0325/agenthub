import SwiftUI

// MARK: - McpView
// MCP 服务器管理视图

/// MCP 服务器管理界面。
///
/// 功能：
/// - 显示已配置的 MCP Server 列表，含连接状态
/// - 添加/移除 MCP Server
/// - 连接/断开 MCP Server
/// - 查看已注册的 MCP 工具列表
struct McpView: View {
    @Environment(AppState.self) private var appState
    @State private var servers: [McpServer] = []
    @State private var showingAddSheet = false
    /// L-5 修复：保存加密失败时设置错误文本，UI 通过 alert 展示给用户
    @State private var lastError: String?

    // MARK: - 加载/错误态(v5.0 P0)
    /// 首屏骨架屏开关：true 时渲染 ListRowSkeleton 占位
    @State private var isLoading: Bool = true
    /// 加载错误信息：非 nil 时覆盖列表渲染 ErrorStateView
    @State private var errorMessage: String? = nil

    /// UserDefaults 持久化键(因 DataController 未提供 McpServerEntity,使用 JSON 编码方式持久化)
    private static let storageKey = "mcp_servers"

    var body: some View {
        NavigationStack {
            List {
                if isLoading {
                    // v5.0 P0: 首屏骨架屏占位
                    Section {
                        SkeletonList(repeat: 4) { ListRowSkeleton() }
                    }
                } else {
                // MCP Server 列表
                Section("MCP 服务器") {
                    if servers.isEmpty {
                        // 空状态使用 ContentUnavailableView,对齐 iOS 17 设计语言
                        ContentUnavailableView(
                            "暂无 MCP 服务器",
                            systemImage: "server.rack",
                            description: Text("点击右上角加号添加 MCP 服务器")
                        )
                    } else {
                        ForEach(servers) { server in
                            McpServerRow(server: server)
                        }
                        .onDelete { indexSet in
                            // 修复: 原实现 `indexSet.forEach { servers.remove(at: $0) }`
                            // 在删除多个非连续索引时会越界（删 index 0 后原 index 2 变成
                            // index 1，再删 index 2 越界）。改用 remove(atOffsets:) 正确处理。
                            servers.remove(atOffsets: indexSet)
                            // 删除后立即持久化,避免 App 重启后数据残留
                            saveServers()
                        }
                    }
                }

                // 已注册工具列表
                Section("可用工具") {
                    let toolNames = appState.mcpBridge.getAvailableTools()
                    if toolNames.isEmpty {
                        Text("暂无注册工具")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(toolNames, id: \.self) { name in
                            Label(name, systemImage: "wrench.and.screwdriver")
                        }
                    }
                }
                } // else
            }
            .navigationTitle("MCP")
            // v5.0 P0: 加载错误时覆盖列表展示 ErrorStateView + onRetry 重载
            .overlay {
                if let errorMessage {
                    ErrorStateView(
                        icon: "network",
                        title: "加载失败",
                        message: errorMessage,
                        onRetry: { reloadServers() }
                    )
                    .background(AppTheme.backgroundColor)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showingAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel(String(localized: "accessibility.add_mcp_server"))
                }
            }
            .sheet(isPresented: $showingAddSheet) {
                AddMcpServerSheet { newServer in
                    servers.append(newServer)
                    // 添加后立即持久化,确保 App 重启后服务器不丢失
                    saveServers()
                }
            }
            // SW-M2: 使用 .task 替代 .onAppear，由 SwiftUI 管理任务生命周期
            .task {
                if isLoading {
                    await loadServersInitial()
                }
            }
            // L-5 修复：加密失败时通过 alert 提示用户
            .alert(String(localized: "common.error.title"),
                   isPresented: Binding(
                       get: { lastError != nil },
                       set: { if !$0 { lastError = nil } }
                   ),
                   presenting: lastError
            ) { _ in
                Button(String(localized: "common.ok"), role: .cancel) { lastError = nil }
            } message: { msg in
                Text(msg)
            }
        }
    }

    // MARK: - 持久化

    /// 将 servers 列表编码为 JSON 并写入 UserDefaults。
    ///
    /// F17 修复：原实现直接 `JSONEncoder().encode(servers)`，apiKey 以明文写入
    /// UserDefaults（plist 文件，iCloud 备份可提取）。现对每个 server 的 apiKey
    /// 先经 `KeychainManager.encrypt` 加密为 `AKS:` 前缀格式再编码。
    /// 内存中的 `servers` 仍保留明文（UI 层与连接逻辑需要明文）。
    private func saveServers() {
        // 拷贝一份用于持久化，避免改动内存中的明文 servers
        var encryptedServers = servers
        // L-5 修复：跟踪加密失败的 server 名称，用于 UI 反馈
        var encryptionFailedNames: [String] = []
        for i in encryptedServers.indices {
            if let key = encryptedServers[i].apiKey, !key.isEmpty {
                // 加密失败（返回 nil）则置 nil，禁止明文落盘
                if let encrypted = KeychainManager.encrypt(key) {
                    encryptedServers[i].apiKey = encrypted
                } else {
                    // L-5 修复：记录失败的 server 名称并置 nil，禁止明文落盘
                    encryptionFailedNames.append(encryptedServers[i].name)
                    encryptedServers[i].apiKey = nil
                }
            }
        }
        if let data = try? JSONEncoder().encode(encryptedServers) {
            UserDefaults.standard.set(data, forKey: Self.storageKey)
        }
        // L-5 修复：加密失败时通过 lastError 触发 alert 提示用户
        if !encryptionFailedNames.isEmpty {
            lastError = "以下服务器的 apiKey 加密失败，已清除明文（可能因 Keychain 不可用）：\n"
                + encryptionFailedNames.joined(separator: "、")
        }
    }

    /// 从 UserDefaults 读取并解码 servers 列表。
    ///
    /// F17 修复：读取后对每个 server 的 apiKey 调用 `decryptOrRaw`：
    /// - 无 `AKS:` 前缀 → 视为旧版明文，原样返回（向后兼容）
    /// - `AKS:` 前缀但解密失败 → 返回空串（避免把损坏密文当明文使用）
    /// - `AKS:` 前缀且解密成功 → 返回明文
    private func loadServers() {
        if let data = UserDefaults.standard.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode([McpServer].self, from: data) {
            servers = decoded.map { server in
                var s = server
                if let key = s.apiKey, !key.isEmpty {
                    s.apiKey = KeychainManager.decryptOrRaw(key)
                }
                return s
            }
        }
    }

    // MARK: - 加载/错误态(v5.0 P0)

    /// 首屏加载：展示骨架屏后从 UserDefaults 拉取 MCP 服务器列表。
    /// `loadServers` 是同步 API，不会抛错；保留 isLoading/errorMessage 框架
    /// 便于未来切换异步数据源时无缝接入。
    private func loadServersInitial() async {
        // 短暂展示骨架屏，让用户感知「正在加载」
        try? await Task.sleep(nanoseconds: 250_000_000)
        loadServers()
        isLoading = false
    }

    /// 错误重试入口：重置状态后重新加载（onRetry 闭包要求 () -> Void）
    private func reloadServers() {
        isLoading = true
        errorMessage = nil
        Task { await loadServersInitial() }
    }
}

// MARK: - MCP Server Row

private struct McpServerRow: View {
    @Environment(AppState.self) private var appState
    let server: McpServer
    @State private var isConnecting = false

    var connectionState: McpBridge.ConnectionState {
        appState.mcpBridge.connectionStates[server.id]?.state ?? .disconnected
    }

    var statusColor: Color {
        switch connectionState {
        case .connected: .green
        case .connecting: .yellow
        case .failed: .red
        case .disconnected: .gray
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Circle()
                    .fill(statusColor)
                    .frame(width: 10, height: 10)
                    .accessibilityLabel(String(localized: "accessibility.connection_status"))

                VStack(alignment: .leading) {
                    Text(server.name)
                        .font(.body)
                    Text(server.transportUrl)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Button {
                    Task {
                        if connectionState == .connected {
                            await appState.mcpBridge.disconnectServer(server.id)
                        } else {
                            isConnecting = true
                            _ = await appState.mcpBridge.connectServer(server)
                            isConnecting = false
                        }
                    }
                } label: {
                    if isConnecting {
                        ProgressView()
                    } else {
                        Text(connectionState == .connected ? String(localized: "mcp.disconnect") : String(localized: "mcp.connect"))
                            .font(.caption)
                    }
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(connectionState == .connected ? String(localized: "accessibility.disconnect") : String(localized: "accessibility.connect"))
            }

            // 能力标签
            if server.capabilities.tools {
                Label("Tools", systemImage: "wrench")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Add MCP Server Sheet

private struct AddMcpServerSheet: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss
    let onAdd: (McpServer) -> Void

    @State private var name = ""
    @State private var transportUrl = ""
    @State private var apiKey = ""
    @State private var transportType: McpTransportType = .http

    // MARK: 校验错误状态
    // showingValidationAlert / validationErrorMessage 仅作为兜底展示首条错误弹窗
    @State private var showingValidationAlert = false
    @State private var validationErrorMessage = ""
    // 字段级错误回填：本地持有当前校验结果，按字段名渲染行内红色提示，
    // 避免污染 appState.lastValidationError 全局状态。sheet 重新打开时清空。
    @State private var validationErrors: ConfigValidationResult?

    /// 传输地址 TextField 的标签：STDIO 时显示「命令路径」，SSE/HTTP 时显示「传输地址 (URL)」
    private var transportFieldLabel: String {
        transportType == .stdio ? "命令路径" : "传输地址 (URL)"
    }

    /// STDIO 命令路径 placeholder（提示用户填可执行命令）
    private var transportFieldPlaceholder: String {
        transportType == .stdio
            ? "npx -y @modelcontextprotocol/server-xxx"
            : "https://example.com/mcp"
    }

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

    var body: some View {
        NavigationStack {
            Form {
                Section("服务器信息") {
                    TextField("名称", text: $name)
                    inlineErrorView(for: McpServerValidator.Field.name)

                    // 传输地址 / 命令路径：STDIO 时切换标签与键盘类型
                    TextField(transportFieldLabel, text: $transportUrl, prompt: Text(transportFieldPlaceholder))
                        .keyboardType(transportType == .stdio ? .default : .URL)
                        // CI-fix: `TextInputAutocapitalization` 仅有 `.never / .words /
                        // .sentences / .characters` 四个 case，无 `.disabled`。原代码
                        // `.disabled` 是 `View.disabled(_:)` 修饰符的命名空间，不属于
                        // `TextInputAutocapitalization`，导致隐式成员表达式解析失败、
                        // Section 的 ViewBuilder 无法推断泛型 V。
                        .textInputAutocapitalization(.never)
                    inlineErrorView(for: McpServerValidator.Field.transportUrl)

                    SecureField("API Key (可选)", text: $apiKey)
                    inlineErrorView(for: McpServerValidator.Field.apiKey)

                    Picker("传输类型", selection: $transportType) {
                        ForEach(McpTransportType.allCases, id: \.self) { type in
                            Text(type.rawValue).tag(type)
                        }
                    }
                }
            }
            .navigationTitle("添加 MCP 服务器")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("添加") {
                        let server = McpServer(
                            id: UUID().uuidString,
                            name: name.isEmpty ? "MCP Server" : name,
                            transportUrl: transportUrl,
                            transportType: transportType,
                            apiKey: apiKey.isEmpty ? nil : apiKey
                        )
                        // 添加前校验：失败则回填本地 validationErrors（驱动字段级行内提示）+
                        // appState.lastValidationError（全局缓存，保留作死状态字段不删）
                        // 并弹窗兜底展示首条错误，不执行 onAdd
                        let validationResult = McpServerValidator.validate(server)
                        guard validationResult.isValid else {
                            appState.lastValidationError = validationResult
                            validationErrors = validationResult
                            validationErrorMessage = validationResult.errors.first?.message ?? "配置校验失败"
                            showingValidationAlert = true
                            return
                        }
                        onAdd(server)
                        dismiss()
                    }
                    .disabled(transportUrl.isEmpty)
                }
            }
            // sheet 每次打开时清空字段级错误回填状态，避免上一次的残留错误显示
            .onAppear {
                validationErrors = nil
            }
            // 校验失败兜底提示：展示首条错误消息，字段级错误已在各字段下方行内显示
            .alert("无法添加", isPresented: $showingValidationAlert) {
                Button("确定", role: .cancel) {}
            } message: {
                Text(validationErrorMessage)
            }
        }
    }
}

// MARK: - McpTransportType CaseIterable

extension McpTransportType: CaseIterable {
    public static var allCases: [McpTransportType] {
        [.stdio, .sse, .http]
    }
}
