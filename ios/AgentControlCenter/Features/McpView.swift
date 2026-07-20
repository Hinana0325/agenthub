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

    var body: some View {
        NavigationStack {
            List {
                // MCP Server 列表
                Section("MCP 服务器") {
                    if servers.isEmpty {
                        Text("暂无 MCP 服务器")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(servers) { server in
                            McpServerRow(server: server)
                        }
                        .onDelete { indexSet in
                            indexSet.forEach { servers.remove(at: $0) }
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
                }
            }
            .sheet(isPresented: $showingAddSheet) {
                AddMcpServerSheet { newServer in
                    servers.append(newServer)
                }
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
                        .autocapitalization(.none)
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
