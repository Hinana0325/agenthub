import SwiftUI
import Observation

// MARK: - CommandPaletteView
//
// 类似 Spotlight / Raycast 风格的命令面板。
//
// 功能概览：
// 1. 自定义搜索框（聚焦时自动弹出键盘）
// 2. 命令列表过滤（按关键字匹配 title / subtitle / keywords）
// 3. 键盘导航：↑/↓ 选择、Enter 执行、Esc 关闭
// 4. 最近命令历史（按使用次数倒序，最多 5 条）
// 5. 分组展示（导航 / 操作 / Agent / 工具）
//
// 触发方式：ContentView 在根视图添加 .keyboardShortcut("K", modifiers: .command) 的隐藏按钮
// 或在工具栏添加快捷入口，调用方控制 `isPresented` 绑定。

/// 命令面板视图 — Spotlight 风格的全局命令入口
struct CommandPaletteView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    /// 当前选中的命令索引
    @State private var selectedIndex: Int = 0

    /// 搜索关键字
    @State private var query: String = ""

    /// 当前可见的过滤后命令列表（含最近使用分组）
    var filteredCommands: [CommandItem] {
        let all = CommandPaletteManager.shared.allCommands()
        let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        guard !keyword.isEmpty else {
            // 空查询时优先展示最近使用的命令 + 默认快捷操作
            let recents = CommandPaletteManager.shared.recentCommands()
            return recents + all.filter { cmd in
                !recents.contains(where: { $0.id == cmd.id }) && cmd.isQuickAction
            }
        }

        return all.filter { cmd in
            cmd.title.lowercased().contains(keyword) ||
            cmd.subtitle.lowercased().contains(keyword) ||
            cmd.keywords.joined(separator: " ").lowercased().contains(keyword)
        }
    }

    /// TextField 焦点状态（用于键盘完成按钮收起键盘）
    @FocusState private var isSearchFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            // MARK: 搜索框
            searchBar

            Divider()

            // MARK: 命令列表
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if filteredCommands.isEmpty {
                            emptyState
                        } else {
                            ForEach(Array(filteredCommands.enumerated()), id: \.element.id) { index, command in
                                CommandRow(
                                    command: command,
                                    isSelected: selectedIndex == index
                                )
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    executeCommand(command, at: index)
                                }
                                .id(command.id)
                                .onHover { hovering in
                                    if hovering {
                                        selectedIndex = index
                                    }
                                }
                            }
                        }
                    }
                    .padding(.vertical, AppTheme.Spacing.sm)
                }
                .onChange(of: selectedIndex) { _, newValue in
                    // 选中项变化时滚动到可见
                    if filteredCommands.indices.contains(newValue) {
                        let cmdId = filteredCommands[newValue].id
                        withAnimation(.easeOut(duration: 0.15)) {
                            proxy.scrollTo(cmdId, anchor: .center)
                        }
                    }
                }
                .onChange(of: query) { _, _ in
                    // 搜索变化时重置选中项
                    selectedIndex = 0
                }
            }
        }
        .frame(maxWidth: 560, minHeight: 360, maxHeight: 560)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.xl))
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.CornerRadius.xl)
                .strokeBorder(AppTheme.borderColor, lineWidth: 0.5)
        )
        .shadow(
            color: AppTheme.Shadow.heavy.color,
            radius: AppTheme.Shadow.heavy.radius,
            x: AppTheme.Shadow.heavy.x,
            y: AppTheme.Shadow.heavy.y
        )
        .onAppear {
            isSearchFocused = true
            selectedIndex = 0
        }
        .onKeyPress(.upArrow) {
            moveSelection(by: -1)
            return .handled
        }
        .onKeyPress(.downArrow) {
            moveSelection(by: 1)
            return .handled
        }
        .onKeyPress(.return) {
            if filteredCommands.indices.contains(selectedIndex) {
                executeCommand(filteredCommands[selectedIndex], at: selectedIndex)
            }
            return .handled
        }
        .onKeyPress(.escape) {
            dismiss()
            return .handled
        }
    }

    // MARK: - 子视图

    /// 搜索框
    private var searchBar: some View {
        HStack(spacing: AppTheme.Spacing.sm) {
            Image(systemName: "magnifyingglass")
                .font(.title3)
                .foregroundStyle(.secondary)

            TextField("搜索命令…", text: $query)
                .font(.body)
                .focused($isSearchFocused)
                .submitLabel(.go)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit {
                    if filteredCommands.indices.contains(selectedIndex) {
                        executeCommand(filteredCommands[selectedIndex], at: selectedIndex)
                    }
                }

            if !query.isEmpty {
                Button {
                    query = ""
                    selectedIndex = 0
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清除")
            }

            // 键盘提示
            Text("⌘K")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .padding(.horizontal, AppTheme.Spacing.xs)
                .padding(.vertical, 2)
                .background(AppTheme.tertiaryBackground)
                .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
        }
        .padding(.horizontal, AppTheme.Spacing.lg)
        .padding(.vertical, AppTheme.Spacing.md)
    }

    /// 空状态
    private var emptyState: some View {
        VStack(spacing: AppTheme.Spacing.sm) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 32))
                .foregroundStyle(.tertiary)
            Text("未找到匹配的命令")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("尝试其他关键字")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, AppTheme.Spacing.xxl)
    }

    // MARK: - 操作

    /// 移动选中项
    /// - Parameter delta: 偏移量（负向上，正向下）
    private func moveSelection(by delta: Int) {
        let count = filteredCommands.count
        guard count > 0 else { return }
        let newIndex = (selectedIndex + delta + count) % count
        selectedIndex = newIndex
    }

    /// 执行命令
    /// - Parameters:
    ///   - command: 命令项
    ///   - index: 索引（用于记录最近使用）
    private func executeCommand(_ command: CommandItem, at index: Int) {
        // 记录最近使用
        CommandPaletteManager.shared.recordUsage(command)

        // 触发动作
        command.action()

        // 关闭面板
        dismiss()
    }
}

// MARK: - CommandRow

/// 单行命令展示
private struct CommandRow: View {
    let command: CommandItem
    let isSelected: Bool

    var body: some View {
        HStack(spacing: AppTheme.Spacing.md) {
            // 图标
            ZStack {
                RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm)
                    .fill(isSelected ? AppTheme.primaryColor.opacity(0.15) : AppTheme.tertiaryBackground)
                    .frame(width: 32, height: 32)
                Image(systemName: command.systemImage)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(isSelected ? AppTheme.primaryColor : AppTheme.secondaryTextColor)
            }

            // 标题 + 副标题
            VStack(alignment: .leading, spacing: 2) {
                Text(command.title)
                    .font(.body)
                    .foregroundStyle(.primary)
                Text(command.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            // 快捷键（如果有）
            if let shortcut = command.shortcut {
                Text(shortcut)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, AppTheme.Spacing.xs)
                    .padding(.vertical, 2)
                    .background(AppTheme.tertiaryBackground)
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
            }

            // 选中标识
            if isSelected {
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, AppTheme.Spacing.lg)
        .padding(.vertical, AppTheme.Spacing.sm)
        .background(isSelected ? AppTheme.primaryColor.opacity(0.08) : Color.clear)
        .animation(.easeOut(duration: 0.1), value: isSelected)
    }
}

// MARK: - CommandItem

/// 命令项
///
/// 表示一个可执行的命令，由 `CommandPaletteManager` 注册并在命令面板中展示。
/// 字段：唯一 ID、图标、标题、副标题、关键字、快捷键提示、是否快捷操作、执行闭包。
struct CommandItem: Identifiable, Hashable {
    /// 唯一 ID
    let id: String
    /// SF Symbol 图标名
    let systemImage: String
    /// 标题
    let title: String
    /// 副标题（描述）
    let subtitle: String
    /// 搜索关键字（除 title/subtitle 外的匹配项）
    let keywords: [String]
    /// 快捷键提示（如 "⌘N"）
    let shortcut: String?
    /// 是否为默认快捷操作（空搜索时优先展示）
    let isQuickAction: Bool
    /// 所属分组
    let category: CommandCategory
    /// 执行闭包
    let action: () -> Void

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }

    static func == (lhs: CommandItem, rhs: CommandItem) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - CommandCategory

/// 命令分组
enum CommandCategory: String, CaseIterable {
    case navigation = "导航"
    case action = "操作"
    case agent = "Agent"
    case tools = "工具"
}

// MARK: - CommandPaletteManager

/// 命令面板管理器
///
/// 负责注册和查询命令、维护最近使用历史。
/// 命令通过 `register(_:)` 注册，`action` 闭包由调用方在运行时绑定具体行为
/// （如切换导航、打开页面等）。
@Observable
final class CommandPaletteManager {

    /// 共享实例（通过 `shared` 访问静态注册的命令集合）
    static let shared = CommandPaletteManager()

    /// 全部已注册命令（不携带运行时闭包的静态副本，用于展示）
    private(set) var registeredCommands: [CommandItem] = []

    /// UserDefaults 键（用于持久化最近使用的命令 ID 列表）
    private let recentsKey = "command_palette_recents"

    /// 初始化时注册默认命令
    private init() {
        registerDefaults()
    }

    /// 从 UserDefaults 读取最近使用的命令 ID 列表
    private var recentIds: [String] {
        let raw = UserDefaults.standard.string(forKey: recentsKey) ?? ""
        return raw.split(separator: ",").map(String.init).filter { !$0.isEmpty }
    }

    /// 写入最近使用的命令 ID 列表到 UserDefaults
    private func setRecentIds(_ ids: [String]) {
        UserDefaults.standard.set(ids.joined(separator: ","), forKey: recentsKey)
    }

    // MARK: - 默认命令注册

    /// 注册默认命令集（静态注册，action 留空，由调用方在运行时覆盖）
    private func registerDefaults() {
        // 导航类
        registeredCommands = [
            CommandItem(
                id: "nav.sessions",
                systemImage: "bubble.left.and.bubble.right",
                title: "前往会话",
                subtitle: "切换到会话列表",
                keywords: ["sessions", "聊天", "对话", "chat"],
                shortcut: "⌘1",
                isQuickAction: true,
                category: .navigation,
                action: {}
            ),
            CommandItem(
                id: "nav.agents",
                systemImage: "cpu",
                title: "前往 Agent",
                subtitle: "管理已安装的 Agent",
                keywords: ["agents", "智能体", "管理"],
                shortcut: "⌘2",
                isQuickAction: true,
                category: .navigation,
                action: {}
            ),
            CommandItem(
                id: "nav.tasks",
                systemImage: "checklist",
                title: "前往任务",
                subtitle: "查看任务列表",
                keywords: ["tasks", "待办", "任务列表"],
                shortcut: "⌘3",
                isQuickAction: true,
                category: .navigation,
                action: {}
            ),
            CommandItem(
                id: "nav.settings",
                systemImage: "gear",
                title: "前往设置",
                subtitle: "打开应用设置",
                keywords: ["settings", "偏好", "配置"],
                shortcut: "⌘,",
                isQuickAction: true,
                category: .navigation,
                action: {}
            ),
            CommandItem(
                id: "nav.marketplace",
                systemImage: "storefront",
                title: "前往市场",
                subtitle: "浏览并安装新 Agent",
                keywords: ["marketplace", "市场", "商店", "安装"],
                shortcut: nil,
                isQuickAction: true,
                category: .navigation,
                action: {}
            ),
            CommandItem(
                id: "nav.voicechat",
                systemImage: "waveform",
                title: "前往语音消息",
                subtitle: "录制并管理语音消息",
                keywords: ["voice", "语音", "录音", "audio"],
                shortcut: nil,
                isQuickAction: false,
                category: .navigation,
                action: {}
            ),

            // 操作类
            CommandItem(
                id: "action.new_session",
                systemImage: "plus.bubble",
                title: "新建会话",
                subtitle: "创建一个新的聊天会话",
                keywords: ["new", "新建", "创建", "create"],
                shortcut: "⌘N",
                isQuickAction: true,
                category: .action,
                action: {}
            ),
            CommandItem(
                id: "action.add_agent",
                systemImage: "plus.circle",
                title: "添加 Agent",
                subtitle: "添加新的 Agent 配置",
                keywords: ["add", "agent", "添加", "新增"],
                shortcut: nil,
                isQuickAction: false,
                category: .action,
                action: {}
            ),
            CommandItem(
                id: "action.toggle_theme",
                systemImage: "circle.lefthalf.filled",
                title: "切换主题",
                subtitle: "在浅色/深色/跟随系统间切换",
                keywords: ["theme", "dark", "light", "主题", "深色", "浅色"],
                shortcut: nil,
                isQuickAction: false,
                category: .action,
                action: {}
            ),

            // Agent 类（动态：基于已注册的 Agent，但为避免循环引用，此处仅注册占位命令）
            CommandItem(
                id: "agent.set_active",
                systemImage: "star.fill",
                title: "切换活跃 Agent",
                subtitle: "从已注册的 Agent 中选择活跃项",
                keywords: ["active", "set", "活跃"],
                shortcut: nil,
                isQuickAction: false,
                category: .agent,
                action: {}
            ),

            // 工具类
            CommandItem(
                id: "tools.command_palette",
                systemImage: "command",
                title: "命令面板",
                subtitle: "打开命令面板（当前）",
                keywords: ["palette", "spotlight", "命令面板"],
                shortcut: "⌘K",
                isQuickAction: false,
                category: .tools,
                action: {}
            ),
            CommandItem(
                id: "tools.refresh_market",
                systemImage: "arrow.clockwise",
                title: "刷新市场",
                subtitle: "重新拉取市场数据",
                keywords: ["refresh", "刷新", "市场"],
                shortcut: nil,
                isQuickAction: false,
                category: .tools,
                action: {}
            ),
            CommandItem(
                id: "tools.export_data",
                systemImage: "square.and.arrow.up",
                title: "导出数据",
                subtitle: "导出会话与 Agent 配置",
                keywords: ["export", "导出", "备份"],
                shortcut: nil,
                isQuickAction: false,
                category: .tools,
                action: {}
            )
        ]
    }

    // MARK: - 注册 / 替换

    /// 注册或替换命令（按 id 匹配）
    /// - Parameter command: 命令项
    func register(_ command: CommandItem) {
        if let index = registeredCommands.firstIndex(where: { $0.id == command.id }) {
            registeredCommands[index] = command
        } else {
            registeredCommands.append(command)
        }
    }

    /// 批量注册命令（覆盖同 id）
    /// - Parameter commands: 命令项列表
    func registerAll(_ commands: [CommandItem]) {
        for command in commands {
            register(command)
        }
    }

    // MARK: - 查询

    /// 全部命令（按分组排序）
    /// - Returns: 命令项列表
    func allCommands() -> [CommandItem] {
        registeredCommands.sorted { lhs, rhs in
            if lhs.category != rhs.category {
                return lhs.category.rawValue < rhs.category.rawValue
            }
            return lhs.title < rhs.title
        }
    }

    /// 按 ID 查找
    /// - Parameter id: 命令 ID
    /// - Returns: 命令项（可能为 nil）
    func command(byId id: String) -> CommandItem? {
        registeredCommands.first { $0.id == id }
    }

    // MARK: - 最近使用历史

    /// 最近的命令列表（最多 5 条，按使用时间倒序）
    /// - Returns: 命令项列表
    func recentCommands() -> [CommandItem] {
        recentIds.prefix(5).compactMap { id in
            registeredCommands.first { $0.id == id }
        }
    }

    /// 记录命令使用
    /// - Parameter command: 命令项
    func recordUsage(_ command: CommandItem) {
        var ids = recentIds.filter { $0 != command.id }
        ids.insert(command.id, at: 0)
        if ids.count > 5 {
            ids = Array(ids.prefix(5))
        }
        setRecentIds(ids)
    }

    /// 清除最近使用历史
    func clearHistory() {
        setRecentIds([])
    }
}
