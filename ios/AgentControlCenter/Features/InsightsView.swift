import SwiftUI

// MARK: - InsightsView

/// 数据洞察页面，对应 Android InsightsScreen。
/// 从 sessionManager 和 dataController 实时计算统计指标，展示使用情况概览。
struct InsightsView: View {
    @Environment(AppState.self) private var appState

    // 修复 PERF: 原实现 allMessages / agentUsageFrequency / fetchTasks 都是计算属性，
    // 每次 body 重绘（任何 @Observable 状态变化都触发）都会全量 fetch。
    // 10 个会话 × 100 条消息 = 4 个计算属性 × 1000 条 = 4000 次 SwiftData 查询/render。
    // 改为 .task 中预计算到 @State 缓存，仅在视图出现时计算一次。
    @State private var allMessages: [Message] = []
    @State private var allTasks: [AgentTask] = []
    @State private var hasLoaded: Bool = false

    var body: some View {
        Group {
            if !hasLoaded {
                ProgressView("加载中…")
            } else if appState.sessionManager.sessions.isEmpty && allTasks.isEmpty {
                emptyView
            } else {
                ScrollView {
                    VStack(spacing: 24) {
                        // 顶部概览卡片网格
                        overviewGrid

                        // 消息分布（按角色类型统计）
                        messageDistributionSection

                        // Agent 使用频率
                        agentUsageSection

                        // 活跃时段分析
                        activeTimeSection
                    }
                    .padding(16)
                }
            }
        }
        .navigationTitle("数据洞察")
        .task {
            // 预计算所有指标到 @State 缓存，避免计算属性每次 body 重绘都全量 fetch
            allMessages = appState.sessionManager.sessions.flatMap { session in
                appState.dataController.fetchMessages(sessionId: session.id)
            }
            allTasks = appState.dataController.fetchTasks()
            hasLoaded = true
        }
    }

    // MARK: - 空状态

    /// 无数据时的空状态提示
    private var emptyView: some View {
        ContentUnavailableView {
            Label("暂无使用数据", systemImage: "chart.bar.xaxis")
        } description: {
            Text("开始与 Agent 对话后，这里将展示使用统计与数据洞察。")
        }
    }

    // MARK: - 计算属性（基于 @State 缓存，不再触发 fetch）

    /// 总会话数
    private var totalSessions: Int {
        appState.sessionManager.sessions.count
    }

    /// 总消息数
    private var totalMessages: Int {
        allMessages.count
    }

    /// 活跃 Agent 数（在线状态的 Agent）
    private var activeAgentCount: Int {
        appState.agentManager.onlineAgents.count
    }

    /// 按消息角色统计的数量
    private var messageCountByRole: [MessageRole: Int] {
        var counts: [MessageRole: Int] = [:]
        for msg in allMessages {
            counts[msg.role, default: 0] += 1
        }
        return counts
    }

    /// Agent 使用频率（基于任务的 agentId 统计）
    private var agentUsageFrequency: [(name: String, count: Int)] {
        var agentCounts: [String: Int] = [:]
        for task in allTasks {
            let agentName = appState.agentManager.getAgent(task.agentId)?.name ?? task.agentId
            agentCounts[agentName, default: 0] += 1
        }
        return agentCounts
            .map { (name: $0.key, count: $0.value) }
            .sorted { $0.count > $1.count }
    }

    /// 平均响应时间估算（基于会话更新间隔的简单启发式）
    private var averageResponseTime: String {
        guard allMessages.count >= 2 else { return "—" }

        // 找出相邻的 user -> assistant 消息对，计算时间差
        var intervals: [TimeInterval] = []
        for i in 0..<(allMessages.count - 1) {
            if allMessages[i].role == .user && allMessages[i + 1].role == .assistant {
                let interval = TimeInterval(allMessages[i + 1].timestamp - allMessages[i].timestamp) / 1000.0
                if interval > 0 && interval < 300 { // 过滤异常值
                    intervals.append(interval)
                }
            }
        }

        guard !intervals.isEmpty else { return "—" }

        let avg = intervals.reduce(0, +) / Double(intervals.count)
        if avg < 1 {
            return String(format: "%.0f 毫秒", avg * 1000)
        } else {
            return String(format: "%.1f 秒", avg)
        }
    }

    /// 最活跃时段（简单统计：按小时聚合消息时间戳）
    private var peakHourRange: String {
        guard allMessages.count >= 5 else { return "数据不足" }

        // 统计每个小时的消息数
        var hourCounts: [Int: Int] = [:]
        for msg in allMessages {
            let date = Date(timeIntervalSince1970: TimeInterval(msg.timestamp) / 1000)
            let hour = Calendar.current.component(.hour, from: date)
            hourCounts[hour, default: 0] += 1
        }

        // 找到消息数最多的连续两小时窗口
        let maxHour = hourCounts.max(by: { $0.value < $1.value })?.key ?? 12
        let nextHour = (maxHour + 1) % 24

        return String(format: "%02d:00 - %02d:00", maxHour, nextHour)
    }

    // MARK: - 概览卡片网格

    /// 顶部四格概览卡片
    private var overviewGrid: some View {
        LazyVGrid(columns: [
            GridItem(.flexible(), spacing: 12),
            GridItem(.flexible(), spacing: 12)
        ], spacing: 12) {
            statCard(
                title: "总会话数",
                value: "\(totalSessions)",
                icon: "bubble.left.and.bubble.right",
                color: .blue
            )
            statCard(
                title: "总消息数",
                value: "\(totalMessages)",
                icon: "text.bubble",
                color: .green
            )
            statCard(
                title: "活跃 Agent",
                value: "\(activeAgentCount)",
                icon: "person.2.fill",
                color: .orange
            )
            statCard(
                title: "平均响应",
                value: averageResponseTime,
                icon: "clock.fill",
                color: .purple
            )
        }
    }

    /// 单个统计卡片
    private func statCard(title: String, value: String, icon: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: icon)
                    .foregroundStyle(color)
                    .font(.title3)
                Spacer()
            }

            Text(value)
                .font(.title)
                .fontWeight(.bold)
                .foregroundStyle(.primary)

            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - 消息分布

    /// 消息按角色类型的分布统计
    private var messageDistributionSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("消息分布", icon: "chart.pie")

            let roles: [(MessageRole, String, Color)] = [
                (.user, "用户消息", .blue),
                (.assistant, "助手回复", .green),
                (.system, "系统消息", .gray),
                (.tool, "工具调用", .orange)
            ]

            let maxCount = messageCountByRole.values.max() ?? 1

            ForEach(roles, id: \.0) { role, name, color in
                let count = messageCountByRole[role, default: 0]

                HStack(spacing: 12) {
                    // 角色名称
                    Text(name)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(width: 72, alignment: .leading)

                    // 横向条形图
                    GeometryReader { geo in
                        let barWidth = maxCount > 0 ? CGFloat(count) / CGFloat(maxCount) * geo.size.width : 0
                        RoundedRectangle(cornerRadius: 4)
                            .fill(color)
                            .frame(width: barWidth, height: 20)
                    }
                    .frame(height: 20)

                    // 数量
                    Text("\(count)")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundStyle(.primary)
                        .frame(width: 36, alignment: .trailing)
                }
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Agent 使用频率

    /// 按 Agent 统计的消息/任务使用频率
    private var agentUsageSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("Agent 使用频率", icon: "person.text.rectangle")

            if agentUsageFrequency.isEmpty {
                Text("暂无任务数据")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                let maxUsage = agentUsageFrequency.first?.count ?? 1

                ForEach(Array(agentUsageFrequency.enumerated()), id: \.offset) { _, item in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                                .font(.subheadline)
                                .fontWeight(.medium)
                            Text("\(item.count) 次调用")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        // 简易条形指示器
                        HStack(spacing: 2) {
                            ForEach(0..<min(item.count, 10), id: \.self) { _ in
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(AppTheme.primaryColor.opacity(0.6))
                                    .frame(width: 4, height: 16)
                            }
                        }
                    }
                    .padding(.vertical, 4)

                    if item.name != agentUsageFrequency.last?.name {
                        Divider()
                    }
                }
            }
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - 活跃时段

    /// 最活跃时段信息展示
    private var activeTimeSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("活跃时段", icon: "clock.badge.checkmark")

            HStack {
                Image(systemName: "sun.max.fill")
                    .font(.title2)
                    .foregroundStyle(.orange)

                VStack(alignment: .leading, spacing: 4) {
                    Text("最活跃时段")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(peakHourRange)
                        .font(.title3)
                        .fontWeight(.semibold)
                }

                Spacer()

                Image(systemName: "chart.line.uptrend.xyaxis")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
            .padding(16)
            .background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        }
        .padding(16)
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - 通用组件

    /// 区块标题
    private func sectionHeader(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .font(.headline)
            .foregroundStyle(.primary)
    }
}