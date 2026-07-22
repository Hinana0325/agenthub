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

    /// UserDefaults 持久化键(因 DataController 未提供 McpServerEntity,使用 JSON 编码方式持久化)
    private static let storageKey = "mcp_servers"

    var body: some View {
        NavigationStack {
            List {
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
            }
            .navigationTitle("MCP")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showingAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("添加 MCP 服务器")
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
                loadServers()
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
                        Text(connectionState == .connected ? "断开" : "连接")
                            .font(.caption)
                    }
                }
                .buttonStyle(.bordered)
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
    @Environment(\.dismiss) private var dismiss
    let onAdd: (McpServer) -> Void

    @State private var name = ""
    @State private var transportUrl = ""
    @State private var apiKey = ""
    @State private var transportType: McpTransportType = .http

    var body: some View {
        NavigationStack {
            Form {
                Section("服务器信息") {
                    TextField("名称", text: $name)
                    TextField("传输地址 (URL)", text: $transportUrl)
                        .keyboardType(.URL)
                        // CI-fix: `TextInputAutocapitalization` 仅有 `.never / .words /
                        // .sentences / .characters` 四个 case，无 `.disabled`。原代码
                        // `.disabled` 是 `View.disabled(_:)` 修饰符的命名空间，不属于
                        // `TextInputAutocapitalization`，导致隐式成员表达式解析失败、
                        // Section 的 ViewBuilder 无法推断泛型 V。
                        .textInputAutocapitalization(.never)
                    SecureField("API Key (可选)", text: $apiKey)

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
                        onAdd(server)
                        dismiss()
                    }
                    .disabled(transportUrl.isEmpty)
                }
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
