import SwiftUI

// MARK: - MarketplaceView
// 对应 Android AgentMarketScreen — 市场页面
//
// 功能概览：
// 1. 搜索栏：按名称/描述/作者/能力过滤
// 2. 分类筛选：FilterChip 横向滚动（全部 / 效率 / 开发 / 写作 / 助手 / 创意 / 数据分析 / 自动化）
// 3. Agent 列表：LazyVGrid 两列卡片
// 4. 卡片内容：图标 / 名称 / 作者 / 评分 / 下载量 / 安装按钮
// 5. 详情 Sheet：完整描述 / 功能列表 / 安装按钮
// 6. 安装进度状态：加载中 / 已安装 / 安装按钮
//
// 安装流程：调用 `MarketplaceClient.install(agent:)` 拿到 AgentConfig，
// 再通过 `DataController.saveAgentConfig` 持久化、`AgentManager.register` 注册运行时实例。

/// 市场视图 — 浏览、搜索、安装市场 Agent
struct MarketplaceView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// 搜索关键字
    @State private var searchQuery: String = ""
    /// 当前选中的分类
    @State private var selectedCategory: MarketplaceCategory = .all
    /// 正在查看详情的 Agent
    @State private var detailAgent: MarketplaceAgent?
    /// 安装中的 Agent ID（用于按钮状态）
    @State private var installingId: String?
    /// 按 agent.id 索引的安装任务
    // 修复: 原 install 是 fire-and-forget Task，未存储引用。用户快速点击两个 Agent
    // 安装按钮时 installingId 被覆盖，第一个 Task 完成后把 installingId 置 nil，
    // 第二个的 spinner 提前消失。改为按 id 索引存储 Task，支持并发安装且互不干扰。
    @State private var installTasks: [String: Task<Void, Never>] = [:]
    /// 安装结果提示
    @State private var resultMessage: String?
    @State private var showingResultAlert: Bool = false
    /// v4.9.0: 已收藏的 Agent ID 集合（驱动卡片书签状态 + 收藏筛选）。
    /// 对齐 Android `MarketplaceFavoriteRepository.favoriteIdsFlow`。
    @State private var favoriteIds: Set<String> = []
    /// v4.9.0: 是否仅展示已收藏 Agent（收藏筛选开关）。
    /// 对齐 Android `AgentMarketScreen` 的 `favoritesOnly` 状态。
    @State private var favoritesOnly: Bool = false

    // MARK: - 加载/错误态(v5.0 P0)
    /// 首屏骨架屏开关：true 时渲染 MarketplaceCardSkeletonRow 占位
    @State private var isLoading: Bool = true
    /// 加载错误信息：非 nil 时覆盖列表渲染 ErrorStateView
    @State private var errorMessage: String? = nil

    /// 网格列定义：随 sizeClass 动态列数（iPad regular 3 列，iPhone compact 2 列）
    private var gridColumns: [GridItem] {
        let columns = sizeClass == .regular ? 3 : 2
        return Array(repeating: GridItem(.flexible(), spacing: AppTheme.Spacing.md), count: columns)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: AppTheme.Spacing.md) {
                    // 搜索栏
                    searchBar

                    // 分类筛选 Chip 行
                    categoryChips

                    // 结果计数
                    resultCount

                    // Agent 网格
                    if isLoading {
                        // v5.0 P0: 首屏骨架屏占位
                        skeletonGrid
                    } else if appState.marketplaceClient.isLoading {
                        loadingView
                    } else if filteredAgents.isEmpty {
                        emptyView
                    } else {
                        agentGrid
                    }
                }
                .padding(.horizontal, AppTheme.Spacing.lg)
                .padding(.vertical, AppTheme.Spacing.md)
            }
            .background(AppTheme.backgroundColor)
            // v5.0 P0: 加载错误时覆盖列表展示 ErrorStateView + onRetry 重载
            .overlay {
                if let errorMessage {
                    ErrorStateView(
                        icon: "storefront",
                        title: "加载失败",
                        message: errorMessage,
                        onRetry: { reloadMarketplace() }
                    )
                    .background(AppTheme.backgroundColor)
                }
            }
            .navigationTitle("市场")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        Task { await reload() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .accessibilityLabel("刷新")
                    }
                }
            }
            .alert("提示", isPresented: $showingResultAlert) {
                Button("确定", role: .cancel) {}
            } message: {
                Text(resultMessage ?? "")
            }
            .sheet(item: $detailAgent) { agent in
                MarketplaceAgentDetailSheet(
                    agent: agent,
                    isInstalled: appState.marketplaceClient.isInstalled(agent.id),
                    isInstalling: installingId == agent.id,
                    onInstall: { install(agent) }
                )
            }
        }
        .task {
            if isLoading {
                await loadMarketplaceInitial()
            }
            // 同步已安装状态：扫描本地 AgentConfig
            syncInstalledState()
            // v4.9.0: 加载收藏 ID 集合，驱动卡片书签图标状态
            await loadFavorites()
        }
    }

    // MARK: - 子视图

    /// 搜索栏
    private var searchBar: some View {
        HStack(spacing: AppTheme.Spacing.sm) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField("搜索 Agent、作者或能力", text: $searchQuery)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                // 修复: 移除空的 .onChange(of: searchQuery) — filteredAgents 是计算属性
                // 直接读 searchQuery，输入变化时 SwiftUI 自动重算，不需要 onChange。
            if !searchQuery.isEmpty {
                Button {
                    searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .accessibilityLabel("清除搜索")
            }
        }
        .padding(.horizontal, AppTheme.Spacing.md)
        .padding(.vertical, AppTheme.Spacing.sm)
        .background(AppTheme.secondaryBackground)
        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    /// 分类筛选 Chip 横向滚动
    private var categoryChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: AppTheme.Spacing.sm) {
                ForEach(MarketplaceCategory.allCases) { category in
                    FilterChip(
                        title: category.displayName,
                        isSelected: selectedCategory == category,
                        systemImage: category.systemImage
                    ) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            selectedCategory = category
                        }
                    }
                }
                // v4.9.0: 收藏筛选 Chip — 仅展示已收藏 Agent。
                // 对齐 Android `AgentMarketScreen` 的 `favoritesOnly` FilterChip。
                FilterChip(
                    title: "收藏",
                    isSelected: favoritesOnly,
                    systemImage: favoritesOnly ? "bookmark.fill" : "bookmark"
                ) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        favoritesOnly.toggle()
                    }
                }
            }
            .padding(.horizontal, AppTheme.Spacing.xs)
        }
    }

    /// 结果计数
    private var resultCount: some View {
        HStack {
            Text("共 \(filteredAgents.count) 个结果")
                .font(AppTheme.Typography.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
    }

    /// Agent 网格（动态列数）
    private var agentGrid: some View {
        LazyVGrid(columns: gridColumns, spacing: AppTheme.Spacing.md) {
            ForEach(filteredAgents) { agent in
                MarketplaceAgentCard(
                    agent: agent,
                    isInstalled: appState.marketplaceClient.isInstalled(agent.id),
                    isInstalling: installingId == agent.id,
                    isFavorite: favoriteIds.contains(agent.id),
                    onTap: { detailAgent = agent },
                    onInstall: { install(agent) },
                    onToggleFavorite: { toggleFavorite(agent) }
                )
            }
        }
    }

    /// v5.0 P0: 首屏骨架屏网格（对齐 agentGrid 的动态列数卡片布局）
    private var skeletonGrid: some View {
        LazyVGrid(columns: gridColumns, spacing: AppTheme.Spacing.md) {
            SkeletonList(repeat: 4) { MarketplaceCardSkeletonRow() }
        }
    }

    /// 加载中视图
    private var loadingView: some View {
        VStack(spacing: AppTheme.Spacing.md) {
            ProgressView()
            Text("正在加载市场…")
                .font(AppTheme.Typography.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, AppTheme.Spacing.xxl)
    }

    /// 空状态视图
    // v4.9.0: 区分收藏筛选空状态与普通筛选空状态。
    // 对齐 Android `AgentMarketScreen`：favoritesOnly 时展示 BookmarkBorder 图标
    // 与"暂无收藏"文案；否则展示 Storefront 图标与"暂无匹配"文案。
    private var emptyView: some View {
        VStack(spacing: AppTheme.Spacing.md) {
            Image(systemName: favoritesOnly ? "bookmark" : "storefront")
                .font(.system(size: 56))
                .foregroundStyle(.tertiary)
            Text(favoritesOnly ? "暂无收藏的 Agent" : "暂无匹配的 Agent")
                .font(AppTheme.Typography.headline)
                .foregroundStyle(.secondary)
            Text(favoritesOnly ? "点击卡片上的书签图标即可收藏" : "尝试更换关键字或切换分类")
                .font(AppTheme.Typography.subheadline)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, AppTheme.Spacing.xxl)
    }

    // MARK: - 过滤后的 Agent 列表

    /// 根据搜索关键字和分类过滤后的 Agent 列表
    private var filteredAgents: [MarketplaceAgent] {
        var result = appState.marketplaceClient.agents

        // 分类过滤
        if selectedCategory != .all {
            result = result.filter { $0.category == selectedCategory.rawValue }
        }

        // v4.9.0: 收藏筛选 — 仅展示已收藏 Agent。
        // 对齐 Android `AgentMarketScreen` 的 `matchesFavorite` 条件。
        if favoritesOnly {
            result = result.filter { favoriteIds.contains($0.id) }
        }

        // 搜索过滤
        let keyword = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !keyword.isEmpty else { return result }
        return result.filter { agent in
            agent.name.lowercased().contains(keyword) ||
            agent.description.lowercased().contains(keyword) ||
            agent.author.lowercased().contains(keyword) ||
            agent.capabilities.joined(separator: " ").lowercased().contains(keyword)
        }
    }

    // MARK: - 操作

    /// 重新加载市场数据
    private func reload() async {
        await appState.marketplaceClient.loadAgents()
    }

    // MARK: - 加载/错误态(v5.0 P0)

    /// 首屏加载：展示骨架屏后从 marketplaceClient 拉取市场数据。
    /// `loadAgents` 当前为非抛错 async API；保留 errorMessage 框架便于
    /// 未来切换为可失败加载时无缝接入。
    private func loadMarketplaceInitial() async {
        // 首次进入若未加载过则加载
        if appState.marketplaceClient.agents.isEmpty {
            await appState.marketplaceClient.loadAgents()
        }
        isLoading = false
    }

    /// 错误重试入口：重置状态后重新加载（onRetry 闭包要求 () -> Void）
    private func reloadMarketplace() {
        isLoading = true
        errorMessage = nil
        Task { await loadMarketplaceInitial() }
    }

    /// 同步已安装状态：扫描本地 AgentConfig，标记已存在的 ID
    private func syncInstalledState() {
        let configs = appState.dataController.fetchAgentConfigs()
        for config in configs {
            appState.marketplaceClient.markInstalled(id: config.id)
        }
    }

    // MARK: - 收藏（v4.9.0）

    /// 从持久化存储加载已收藏的 Agent ID 集合。
    /// 对齐 Android `MarketplaceFavoriteRepository.favoriteIdsFlow` 的一次性快照读取。
    private func loadFavorites() async {
        favoriteIds = await appState.dataController.fetchFavoriteIds()
    }

    /// 切换指定 Agent 的收藏状态。
    /// 收藏后立即刷新 `favoriteIds`；若当前处于收藏筛选视图，取消收藏的 Agent 会自动从列表消失。
    /// - Parameter agent: 市场 Agent
    private func toggleFavorite(_ agent: MarketplaceAgent) {
        Task {
            let nowFavorite = await appState.dataController.toggleMarketplaceFavorite(agent: agent)
            if nowFavorite {
                favoriteIds.insert(agent.id)
            } else {
                favoriteIds.remove(agent.id)
            }
        }
    }

    /// 安装市场 Agent 到本地
    /// - Parameter agent: 市场 Agent
    private func install(_ agent: MarketplaceAgent) {
        // 修复: 防止重复点击同一 Agent 发起多次安装
        guard installTasks[agent.id] == nil else { return }
        installingId = agent.id
        installTasks[agent.id] = Task {
            do {
                let config = try await appState.marketplaceClient.install(agent: agent)
                // 持久化
                appState.dataController.saveAgentConfig(config)
                // 注册运行时 Agent（携带能力映射）
                let runtimeAgent = Agent(
                    id: config.id,
                    name: config.name,
                    endpoint: config.serverUrl,
                    capabilities: appState.marketplaceClient.capabilityMap(for: agent.capabilities),
                    config: config
                )
                appState.agentManager.register(runtimeAgent)
                resultMessage = "已成功安装「\(config.name)」，可在 Agent 页面查看。"
            } catch {
                resultMessage = "安装失败：\(error.localizedDescription)"
            }
            installingId = nil
            installTasks[agent.id] = nil
            showingResultAlert = true
        }
    }
}

// MARK: - FilterChip

/// 筛选 Chip 组件
private struct FilterChip: View {
    let title: String
    let isSelected: Bool
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: AppTheme.Spacing.xs) {
                Image(systemName: systemImage)
                    .font(AppTheme.Typography.caption)
                Text(title)
                    .font(AppTheme.Typography.caption)
            }
            .padding(.horizontal, AppTheme.Spacing.md)
            .padding(.vertical, AppTheme.Spacing.sm)
            .background(
                isSelected ? AppTheme.primaryColor : AppTheme.secondaryBackground
            )
            .foregroundStyle(isSelected ? Color.white : AppTheme.primaryTextColor)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - MarketplaceAgentCard

/// 单个市场 Agent 卡片
///
/// 布局：
/// - 顶部：图标（带渐变背景）+ 官方标识
/// - 中部：名称、作者、评分、下载量
/// - 底部：安装按钮（已安装显示为不可点的"已安装"）
private struct MarketplaceAgentCard: View {
    let agent: MarketplaceAgent
    let isInstalled: Bool
    let isInstalling: Bool
    // v4.9.0: 收藏状态，驱动书签图标
    let isFavorite: Bool
    let onTap: () -> Void
    let onInstall: () -> Void
    // v4.9.0: 切换收藏回调
    let onToggleFavorite: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: AppTheme.Spacing.sm) {
                // 顶部：图标 + 官方标识 + 收藏书签
                // v4.9.0: 收藏书签图标放在右上角，对齐 Android `MarketplaceAgentCard`
                // 的 IconButton(Bookmark/BookmarkBorder)。
                HStack {
                    iconView
                    Spacer()
                    if agent.isOfficial {
                        Label("官方", systemImage: "checkmark.seal.fill")
                            .labelStyle(.iconOnly)
                            .foregroundStyle(AppTheme.primaryColor)
                    }
                    // v4.9.0: 收藏书签按钮
                    Button(action: onToggleFavorite) {
                        Image(systemName: isFavorite ? "bookmark.fill" : "bookmark")
                            .foregroundStyle(isFavorite ? AppTheme.primaryColor : .secondary)
                            .font(AppTheme.Typography.body.weight(.medium))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(isFavorite ? "取消收藏" : "收藏")
                }

                // 名称
                Text(agent.name)
                    .font(AppTheme.Typography.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                // 作者
                Text(agent.author)
                    .font(AppTheme.Typography.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                // 评分 + 下载量
                HStack(spacing: AppTheme.Spacing.sm) {
                    Label(String(format: "%.1f", agent.rating), systemImage: "star.fill")
                        .labelStyle(.titleAndIcon)
                        .font(AppTheme.Typography.caption2)
                        .foregroundStyle(.orange)
                    Label(formattedDownloads, systemImage: "arrow.down.circle")
                        .labelStyle(.titleAndIcon)
                        .font(AppTheme.Typography.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                }

                // 安装按钮
                installButton
            }
            .padding(AppTheme.Spacing.md)
            .background(AppTheme.secondaryBackground)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.lg))
            .shadow(
                color: AppTheme.Shadow.light.color,
                radius: AppTheme.Shadow.light.radius,
                x: AppTheme.Shadow.light.x,
                y: AppTheme.Shadow.light.y
            )
        }
        .buttonStyle(.plain)
        .disabled(isInstalling)
    }

    /// 图标视图（带渐变背景的智能体图标）
    private var iconView: some View {
        ZStack {
            RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md)
                .fill(AppTheme.primaryGradient)
                .frame(width: 40, height: 40)
            Image(systemName: "cpu")
                .foregroundStyle(.white)
                .font(AppTheme.Typography.title3.weight(.semibold))
        }
    }

    /// 安装按钮
    @ViewBuilder
    private var installButton: some View {
        if isInstalled {
            Label("已安装", systemImage: "checkmark")
                .labelStyle(.titleAndIcon)
                .font(AppTheme.Typography.caption)
                .frame(maxWidth: .infinity)
                .padding(.vertical, AppTheme.Spacing.xs)
                .background(AppTheme.tertiaryBackground)
                .foregroundStyle(.secondary)
                .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
        } else if isInstalling {
            HStack(spacing: AppTheme.Spacing.xs) {
                ProgressView()
                    .controlSize(.small)
                Text("安装中…")
                    .font(AppTheme.Typography.caption)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, AppTheme.Spacing.xs)
            .background(AppTheme.tertiaryBackground)
            .foregroundStyle(.secondary)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
        } else {
            Button(action: onInstall) {
                Label("安装", systemImage: "arrow.down.circle")
                    .labelStyle(.titleAndIcon)
                    .font(AppTheme.Typography.caption)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, AppTheme.Spacing.xs)
                    .background(AppTheme.primaryColor)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
            }
            .buttonStyle(.plain)
        }
    }

    /// 格式化下载量
    private var formattedDownloads: String {
        let count = agent.downloadCount
        if count >= 10_000 {
            return String(format: "%.1f万", Double(count) / 10_000)
        } else if count >= 1_000 {
            return String(format: "%.1fk", Double(count) / 1_000)
        } else {
            return "\(count)"
        }
    }
}

// MARK: - MarketplaceAgentDetailSheet

/// Agent 详情 Sheet
///
/// 展示完整描述、能力列表、统计信息与安装按钮
private struct MarketplaceAgentDetailSheet: View {
    let agent: MarketplaceAgent
    let isInstalled: Bool
    let isInstalling: Bool
    let onInstall: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: AppTheme.Spacing.lg) {
                    // 头部：图标 + 名称 + 作者
                    headerView

                    // 统计信息
                    statsView

                    // 完整描述
                    descriptionView

                    // 能力列表
                    capabilitiesView

                    // 元信息
                    metaInfoView

                    // 安装按钮
                    installButton
                }
                .padding(AppTheme.Spacing.lg)
            }
            .background(AppTheme.backgroundColor)
            .navigationTitle("Agent 详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }

    /// 头部视图
    private var headerView: some View {
        HStack(spacing: AppTheme.Spacing.md) {
            ZStack {
                RoundedRectangle(cornerRadius: AppTheme.CornerRadius.lg)
                    .fill(AppTheme.primaryGradient)
                    .frame(width: 64, height: 64)
                Image(systemName: "cpu")
                    .foregroundStyle(.white)
                    .font(AppTheme.Typography.largeTitle.weight(.semibold))
            }
            VStack(alignment: .leading, spacing: AppTheme.Spacing.xs) {
                HStack(spacing: AppTheme.Spacing.xs) {
                    Text(agent.name)
                        .font(AppTheme.Typography.title3.bold())
                    if agent.isOfficial {
                        Image(systemName: "checkmark.seal.fill")
                            .foregroundStyle(AppTheme.primaryColor)
                    }
                }
                Text(agent.author)
                    .font(AppTheme.Typography.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    /// 统计信息（评分 / 下载量 / 版本）
    private var statsView: some View {
        HStack(spacing: AppTheme.Spacing.lg) {
            statItem(icon: "star.fill", value: String(format: "%.1f", agent.rating), label: "评分", color: .orange)
            statItem(icon: "arrow.down.circle", value: "\(agent.downloadCount)", label: "下载", color: .blue)
            statItem(icon: "tag", value: agent.version, label: "版本", color: .gray)
        }
        .padding(AppTheme.Spacing.md)
        .frame(maxWidth: .infinity)
        .background(AppTheme.secondaryBackground)
        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    /// 单个统计项
    private func statItem(icon: String, value: String, label: String, color: Color) -> some View {
        VStack(spacing: AppTheme.Spacing.xs) {
            Image(systemName: icon)
                .foregroundStyle(color)
                .font(AppTheme.Typography.title3)
            Text(value)
                .font(AppTheme.Typography.headline)
                .monospacedDigit()
            Text(label)
                .font(AppTheme.Typography.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    /// 完整描述
    private var descriptionView: some View {
        VStack(alignment: .leading, spacing: AppTheme.Spacing.sm) {
            Text("描述")
                .font(AppTheme.Typography.headline)
            Text(agent.description)
                .font(AppTheme.Typography.body)
                .foregroundStyle(.primary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(AppTheme.Spacing.md)
        .background(AppTheme.secondaryBackground)
        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    /// 能力列表
    private var capabilitiesView: some View {
        VStack(alignment: .leading, spacing: AppTheme.Spacing.sm) {
            Text("能力")
                .font(AppTheme.Typography.headline)
            FlowLayout(spacing: AppTheme.Spacing.xs) {
                ForEach(agent.capabilities, id: \.self) { cap in
                    Text(cap)
                        .font(AppTheme.Typography.caption)
                        .padding(.horizontal, AppTheme.Spacing.sm)
                        .padding(.vertical, AppTheme.Spacing.xs)
                        .background(AppTheme.primaryColor.opacity(0.15))
                        .foregroundStyle(AppTheme.primaryColor)
                        .clipShape(Capsule())
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(AppTheme.Spacing.md)
        .background(AppTheme.secondaryBackground)
        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    /// 元信息（服务器地址 / 分类）
    private var metaInfoView: some View {
        VStack(alignment: .leading, spacing: AppTheme.Spacing.sm) {
            Text("元信息")
                .font(AppTheme.Typography.headline)
            metaRow(label: "服务器", value: agent.serverUrl)
            metaRow(label: "分类", value: categoryDisplayName)
            metaRow(label: "ID", value: agent.id)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(AppTheme.Spacing.md)
        .background(AppTheme.secondaryBackground)
        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    /// 单行元信息
    private func metaRow(label: String, value: String) -> some View {
        HStack(alignment: .top) {
            Text(label)
                .font(AppTheme.Typography.subheadline)
                .foregroundStyle(.secondary)
                .frame(width: 60, alignment: .leading)
            Text(value)
                .font(AppTheme.Typography.subheadline)
                .foregroundStyle(.primary)
                .textSelection(.enabled)
        }
    }

    /// 分类显示名
    private var categoryDisplayName: String {
        MarketplaceCategory(rawValue: agent.category)?.displayName ?? agent.category
    }

    /// 安装按钮
    @ViewBuilder
    private var installButton: some View {
        if isInstalled {
            Label("已安装", systemImage: "checkmark.circle.fill")
                .labelStyle(.titleAndIcon)
                .font(AppTheme.Typography.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, AppTheme.Spacing.md)
                .background(AppTheme.tertiaryBackground)
                .foregroundStyle(.secondary)
                .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
        } else if isInstalling {
            HStack(spacing: AppTheme.Spacing.sm) {
                ProgressView()
                Text("正在安装…")
                    .font(AppTheme.Typography.headline)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, AppTheme.Spacing.md)
            .background(AppTheme.tertiaryBackground)
            .foregroundStyle(.secondary)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
        } else {
            Button(action: onInstall) {
                Label("安装此 Agent", systemImage: "arrow.down.circle.fill")
                    .labelStyle(.titleAndIcon)
                    .font(AppTheme.Typography.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, AppTheme.Spacing.md)
                    .background(AppTheme.primaryColor)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - FlowLayout（简易流式布局，用于能力标签自动换行）

/// 简易流式布局 — 自动换行的水平堆叠
///
/// iOS 16+ 可用，使用 `Layout` 协议实现，避免在 ScrollView 内嵌 `WrapHStack` 的偏差。
private struct FlowLayout: Layout {
    /// 行间距与列间距
    var spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var totalHeight: CGFloat = 0
        var lineWidth: CGFloat = 0
        var lineHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if lineWidth + size.width > maxWidth {
                totalHeight += lineHeight + spacing
                lineWidth = size.width
                lineHeight = size.height
            } else {
                lineWidth += size.width + spacing
                lineHeight = max(lineHeight, size.height)
            }
        }
        totalHeight += lineHeight
        return CGSize(width: maxWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let maxWidth = bounds.width
        var x: CGFloat = bounds.minX
        var y: CGFloat = bounds.minY
        var lineHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.minX + maxWidth {
                x = bounds.minX
                y += lineHeight + spacing
                lineHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}
