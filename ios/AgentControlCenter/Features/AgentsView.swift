import SwiftUI

/// Agent 管理视图
/// 展示所有 Agent 及其状态,点击设为活跃 Agent;支持通过 Sheet 添加新 Agent
struct AgentsView: View {
    // 全局应用状态
    @Environment(AppState.self) private var appState
    // 控制"添加 Agent"Sheet 显示
    @State private var showingAddSheet = false

    var body: some View {
        NavigationStack {
            List {
                // 当前活跃 Agent 分组
                if let active = appState.agentManager.activeAgent {
                    Section("当前活跃 Agent") {
                        AgentRow(agent: active, isActive: true)
                    }
                }

                // 所有 Agent 分组
                Section("所有 Agent") {
                    ForEach(appState.agentManager.agents) { agent in
                        Button {
                            // 点击设为活跃 Agent(通过 agentId 设置)
                            appState.agentManager.setActive(agentId: agent.id)
                        } label: {
                            AgentRow(
                                agent: agent,
                                isActive: agent.id == appState.agentManager.activeAgent?.id
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Agent")
            .toolbar {
                // 工具栏:添加 Agent
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showingAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("添加 Agent")
                }
            }
            .sheet(isPresented: $showingAddSheet) {
                AddAgentSheet()
            }
            // 空状态占位
            .overlay {
                if appState.agentManager.agents.isEmpty {
                    ContentUnavailableView(
                        "暂无 Agent",
                        systemImage: "cpu",
                        description: Text("点击右上角加号添加新 Agent")
                    )
                }
            }
        }
    }
}

/// 单个 Agent 行视图:状态点 + 名称 + 类型 + 状态 + 能力标签
private struct AgentRow: View {
    let agent: Agent
    let isActive: Bool

    var body: some View {
        HStack(spacing: 12) {
            // 状态圆点(在线绿/离线灰/连接中黄/错误红)
            Circle()
                .fill(AppTheme.statusColors[agent.status] ?? .gray)
                .frame(width: 10, height: 10)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                // 名称 + 活跃标识
                HStack(spacing: 4) {
                    Text(agent.name)
                        .font(.headline)
                    if isActive {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                            .font(.caption)
                    }
                }

                // 类型(来自 config.type)+ 状态文本
                HStack(spacing: 8) {
                    Text(agent.config?.type.displayName ?? "-")
                        .font(.caption)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(.blue.opacity(0.15), in: Capsule())
                        .foregroundStyle(.blue)

                    Text(agent.status.rawValue)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                // 能力标签(最多展示 3 个,显示枚举 rawValue)
                if !agent.capabilities.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(agent.capabilities.prefix(3), id: \.self) { cap in
                            Text(cap.rawValue)
                                .font(.caption2)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(.gray.opacity(0.15), in: Capsule())
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }
}

/// 添加 Agent 的 Sheet:包含名称 / 类型 / 服务器地址 / API Key / 模型
private struct AddAgentSheet: View {
    // 全局应用状态
    @Environment(AppState.self) private var appState
    // 用于关闭 Sheet
    @Environment(\.dismiss) private var dismiss

    // 表单字段
    @State private var name: String = ""
    @State private var type: AgentType = .openAI
    @State private var serverUrl: String = ""
    @State private var apiKey: String = ""
    @State private var model: String = "gpt-4"

    var body: some View {
        NavigationStack {
            Form {
                // 基本信息分组
                Section("基本信息") {
                    TextField("名称", text: $name)
                    Picker("类型", selection: $type) {
                        ForEach(AgentType.allCases, id: \.self) { t in
                            Text(t.displayName).tag(t)
                        }
                    }
                }

                // 连接配置分组
                Section("连接") {
                    TextField("服务器地址", text: $serverUrl)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    SecureField("API Key", text: $apiKey)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("模型", text: $model)
                }
            }
            .navigationTitle("添加 Agent")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // 取消按钮
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                // 保存按钮
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(name.isEmpty)
                }
            }
        }
    }

    /// 保存 Agent:构造配置(唯一 id)-> 持久化 -> 注册到 agentManager
    private func save() {
        var config = AgentConfig(
            name: name,
            type: type,
            serverUrl: serverUrl,
            apiKey: apiKey,
            model: model
        )
        // 为新 Agent 配置分配唯一 id,避免与默认配置冲突
        config.id = UUID().uuidString

        // 构造运行时 Agent 实例(id 与配置一致,便于关联)
        let agent = Agent(
            id: config.id,
            name: config.name,
            endpoint: config.serverUrl,
            config: config
        )
        // 持久化配置到 dataController(apiKey 由实体层加密)
        appState.dataController.saveAgentConfig(config)
        // 注册到 agentManager 以便调度
        appState.agentManager.register(agent)
        dismiss()
    }
}
