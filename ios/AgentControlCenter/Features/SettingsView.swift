import SwiftUI

/// 设置视图
/// 包含 Agent 默认配置、MCP 服务器管理、主题、端到端加密、关于、清除数据等分组
struct SettingsView: View {
    // 全局应用状态
    @Environment(AppState.self) private var appState

    // 使用 @AppStorage 持久化用户偏好
    @AppStorage("defaultModel") private var defaultModel: String = "gpt-4"
    @AppStorage("temperature") private var temperature: Double = 0.7
    @AppStorage("maxTokens") private var maxTokens: Int = 4096
    @AppStorage("theme") private var theme: String = "system"
    @AppStorage("encryptionEnabled") private var encryptionEnabled: Bool = false
    @AppStorage("encryptionPassphrase") private var passphrase: String = ""

    // 控制清除数据二次确认弹窗
    @State private var showingClearConfirm = false
    // 控制添加 MCP 服务器 Sheet
    @State private var showingAddServer = false
    // 本地维护已配置的 MCP 服务器列表
    // (McpBridge 仅暴露连接状态,不暴露 server 配置,故在 UI 层持有)
    @State private var mcpServers: [McpServer] = []

    var body: some View {
        NavigationStack {
            Form {
                // ============ Agent 默认配置 ============
                Section("Agent 默认配置") {
                    TextField("默认模型", text: $defaultModel)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                    HStack {
                        Text("温度")
                        Spacer()
                        Text(String(format: "%.1f", temperature))
                            .foregroundStyle(.secondary)
                    }
                    Slider(value: $temperature, in: 0...1, step: 0.1)

                    Stepper("最大 Tokens: \(maxTokens)", value: $maxTokens, in: 256...32768, step: 256)
                }

                // ============ MCP 服务器 ============
                Section("MCP 服务器") {
                    ForEach(mcpServers) { server in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(server.name)
                                    .font(.subheadline)
                                Text(server.transportUrl)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            // 连接状态指示点(从 mcpBridge.connectionStates 读取)
                            Circle()
                                .fill(isServerConnected(server.id) ? .green : .gray)
                                .frame(width: 8, height: 8)
                        }
                    }
                    .onDelete { offsets in
                        // 滑动删除:先断开连接,再从本地列表移除
                        let idsToRemove = offsets.map { mcpServers[$0].id }
                        for id in idsToRemove {
                            Task { await appState.mcpBridge.disconnectServer(id) }
                        }
                        mcpServers.removeAll { idsToRemove.contains($0.id) }
                    }

                    Button {
                        showingAddServer = true
                    } label: {
                        Label("添加服务器", systemImage: "plus.circle")
                    }
                }

                // ============ 外观主题 ============
                Section("外观") {
                    Picker("主题", selection: $theme) {
                        Text("浅色").tag("light")
                        Text("深色").tag("dark")
                        Text("跟随系统").tag("system")
                    }
                }

                // ============ 端到端加密 ============
                Section("端到端加密") {
                    Toggle("启用加密", isOn: $encryptionEnabled)
                    if encryptionEnabled {
                        SecureField("密码短语", text: $passphrase)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                }

                // ============ 关于 ============
                Section("关于") {
                    HStack {
                        Text("应用版本")
                        Spacer()
                        Text("1.0.0").foregroundStyle(.secondary)
                    }
                    HStack {
                        Text("协议版本")
                        Spacer()
                        Text("v2").foregroundStyle(.secondary)
                    }
                }

                // ============ 危险操作 ============
                Section {
                    Button(role: .destructive) {
                        showingClearConfirm = true
                    } label: {
                        Label("清除所有数据", systemImage: "trash")
                    }
                }
            }
            .navigationTitle("设置")
            // 清除数据二次确认
            .alert("确认清除所有数据?", isPresented: $showingClearConfirm) {
                Button("取消", role: .cancel) {}
                Button("清除", role: .destructive) {
                    clearAllData()
                }
            } message: {
                Text("此操作不可恢复,将删除所有会话、Agent、任务及配置数据。")
            }
            // 添加 MCP 服务器 Sheet
            .sheet(isPresented: $showingAddServer) {
                AddMcpServerSheet { newServer in
                    // 加入本地列表并异步发起连接(连接状态由 mcpBridge 维护)
                    mcpServers.append(newServer)
                    Task { _ = await appState.mcpBridge.connectServer(newServer) }
                }
            }
        }
    }

    /// 判断某个 MCP 服务器是否已连接
    private func isServerConnected(_ id: String) -> Bool {
        appState.mcpBridge.connectionStates[id]?.state == .connected
    }

    /// 清除所有持久化数据:会话(含消息)/ 任务 / Agent 配置
    /// 注:内存中的 sessionManager / taskManager / agentManager 列表需重启后清空
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
    }
}

/// 添加 MCP 服务器的 Sheet
/// 通过 onAdd 闭包把新服务器回传给父视图(父视图负责加入列表并连接)
private struct AddMcpServerSheet: View {
    /// 新服务器添加回调
    let onAdd: (McpServer) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var name: String = ""
    @State private var endpoint: String = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("名称", text: $name)
                TextField("Endpoint", text: $endpoint)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
            }
            .navigationTitle("添加 MCP 服务器")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("添加") {
                        // 构造 McpServer(transportUrl 为必填字段)
                        let server = McpServer(
                            id: UUID().uuidString,
                            name: name,
                            transportUrl: endpoint
                        )
                        onAdd(server)
                        dismiss()
                    }
                    .disabled(name.isEmpty || endpoint.isEmpty)
                }
            }
        }
    }
}
