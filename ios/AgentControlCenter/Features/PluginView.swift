import SwiftUI

// MARK: - PluginView

/// 插件管理页面，对应 Android PluginScreen。
/// 展示已安装插件列表，支持启用/禁用切换和添加新插件。
struct PluginView: View {
    @Environment(AppState.self) private var appState

    /// 本地插件列表(DataController 暂无 Plugin CRUD,使用 UserDefaults + JSON 编码持久化)
    @State private var plugins: [Plugin] = []

    /// UserDefaults 持久化键
    private static let storageKey = "plugins"

    /// 是否显示添加插件 Sheet
    @State private var showAddSheet: Bool = false

    var body: some View {
        Group {
            if plugins.isEmpty {
                emptyView
            } else {
                pluginList
            }
        }
        .navigationTitle("插件")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showAddSheet = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("添加插件")
            }
        }
        .sheet(isPresented: $showAddSheet) {
            addPluginSheet
        }
        // SW-M2: 使用 .task 替代 .onAppear — 由 SwiftUI 管理任务生命周期，
        // 视图销毁时自动取消，避免页面快速切换时的孤儿任务
        .task {
            loadPlugins()
        }
    }

    // MARK: - 空状态

    /// 无插件时的空状态提示
    private var emptyView: some View {
        ContentUnavailableView {
            Label("暂无插件", systemImage: "puzzlepiece")
        } description: {
            Text("插件可以扩展 Agent 的能力，如调用外部 API、发送广播或触发工作流。")
        } actions: {
            Button("添加插件") {
                showAddSheet = true
            }
            .buttonStyle(.borderedProminent)
        }
    }

    // MARK: - 插件列表

    /// 插件列表视图
    private var pluginList: some View {
        List {
            ForEach($plugins) { $plugin in
                pluginRow(plugin: $plugin)
            }
            .onDelete { indexSet in
                // 用 remove(atOffsets:) 安全删除多个 index，避免手动循环导致越界
                plugins.remove(atOffsets: indexSet)
                // 删除后立即持久化
                savePlugins()
            }
        }
        .listStyle(.insetGrouped)
        .refreshable {
            // 修复: 原实现只 sleep 1 秒不重新加载，用户下拉刷新看到 spinner 转一秒
            // 但列表不变（假刷新）。改为真正调用 loadPlugins() 重新加载插件列表。
            loadPlugins()
        }
    }

    /// 单个插件行
    private func pluginRow(plugin: Binding<Plugin>) -> some View {
        HStack(spacing: 12) {
            // 插件图标
            Image(systemName: plugin.wrappedValue.icon.isEmpty ? "puzzlepiece.fill" : plugin.wrappedValue.icon)
                .font(.title3)
                .foregroundStyle(AppTheme.primaryColor)
                .frame(width: 40, height: 40)
                .background(AppTheme.primaryColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))

            // 插件信息
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(plugin.wrappedValue.name)
                        .font(.subheadline)
                        .fontWeight(.medium)

                    // 类型标签
                    if let action = plugin.wrappedValue.action {
                        actionTypeBadge(action.type)
                    }

                    // 版本号
                    Text("v\(plugin.wrappedValue.version)")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }

                if !plugin.wrappedValue.description.isEmpty {
                    Text(plugin.wrappedValue.description)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }

            Spacer()

            // 启用/禁用 Toggle
            Toggle("", isOn: plugin.isEnabled)
                .labelsHidden()
                .tint(AppTheme.primaryColor)
                // VoiceOver 朗读插件名 + "开关"，否则只能听到无意义的"开关"
                .accessibilityLabel(plugin.wrappedValue.name)
                .accessibilityValue(plugin.wrappedValue.isEnabled ? "已启用" : "已停用")
                // 切换启用状态后立即持久化
                .onChange(of: plugin.wrappedValue.isEnabled) { _, _ in
                    savePlugins()
                }
        }
        .padding(.vertical, 4)
    }

    /// 动作类型标签
    private func actionTypeBadge(_ type: PluginActionType) -> some View {
        let (text, color) = actionTypeDisplay(type)
        return Text(text)
            .font(.caption2)
            .fontWeight(.medium)
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.12), in: Capsule())
    }

    /// 动作类型的显示名称和颜色
    private func actionTypeDisplay(_ type: PluginActionType) -> (String, Color) {
        switch type {
        case .http:      return ("HTTP", .blue)
        case .broadcast: return ("广播", .orange)
        case .workflow:  return ("工作流", .purple)
        case .none:      return ("无动作", .gray)
        }
    }

    // MARK: - 添加插件 Sheet

    /// 添加新插件的弹窗
    private var addPluginSheet: some View {
        NavigationStack {
            AddPluginForm { newPlugin in
                plugins.append(newPlugin)
                // 添加后立即持久化,确保 App 重启后插件不丢失
                savePlugins()
                showAddSheet = false
            }
            .navigationTitle("添加插件")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showAddSheet = false }
                }
            }
        }
        .presentationDetents([.medium])
    }

    // MARK: - 持久化

    /// 从 UserDefaults 加载已保存的插件列表(JSON 解码)
    private func loadPlugins() {
        if let data = UserDefaults.standard.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode([Plugin].self, from: data) {
            plugins = decoded
        }
    }

    /// 将当前插件列表编码为 JSON 并写入 UserDefaults
    private func savePlugins() {
        if let data = try? JSONEncoder().encode(plugins) {
            UserDefaults.standard.set(data, forKey: Self.storageKey)
        }
    }
}

// MARK: - AddPluginForm

/// 添加插件表单
private struct AddPluginForm: View {
    /// 插件名称
    @State private var name: String = ""
    /// 插件描述
    @State private var description: String = ""
    /// 动作类型
    @State private var actionType: PluginActionType = .http
    /// 确认回调
    var onConfirm: (Plugin) -> Void

    /// 是否可以提交
    private var canSubmit: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Form {
            Section {
                TextField("插件名称", text: $name)
                TextField("插件描述（可选）", text: $description, axis: .vertical)
                    .lineLimit(2...4)
            }

            Section {
                Picker("动作类型", selection: $actionType) {
                    Text("HTTP 调用").tag(PluginActionType.http)
                    Text("广播").tag(PluginActionType.broadcast)
                    Text("工作流").tag(PluginActionType.workflow)
                }
                .pickerStyle(.segmented)
            } header: {
                Text("类型")
            } footer: {
                Text(actionTypeDescription)
                    .font(.caption)
            }
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("添加") {
                    // CI-fix: `Plugin.action` 字段类型为 `PluginAction?`（见
                    // Models/Plugin.swift:81），允许为 nil。原代码 `let action: PluginAction`
                    // 为非可选，无法在 `.none` 分支赋 nil。改为 `PluginAction?` 即可。
                    let action: PluginAction?
                    switch actionType {
                    case .http:
                        action = .httpCall(.init(url: "https://example.com/api"))
                    case .broadcast:
                        action = .broadcast(.init(action: "custom.action"))
                    case .workflow:
                        action = .workflow(.init(promptTemplate: "{query}"))
                    case .none:
                        action = nil
                    }

                    let plugin = Plugin(
                        id: UUID().uuidString,
                        name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                        description: description.trimmingCharacters(in: .whitespacesAndNewlines),
                        icon: "puzzlepiece.fill",
                        isEnabled: true,
                        action: action
                    )
                    onConfirm(plugin)
                }
                .disabled(!canSubmit)
            }
        }
    }

    /// 动作类型说明文字
    private var actionTypeDescription: String {
        switch actionType {
        case .http:
            return "通过 HTTP 请求调用外部 API，支持自定义 URL、请求头和请求体。"
        case .broadcast:
            return "向应用内发送广播通知，可用于触发其他组件响应。"
        case .workflow:
            return "生成提示词模板交由 Agent 执行，适用于流程化任务。"
        case .none:
            return ""
        }
    }
}