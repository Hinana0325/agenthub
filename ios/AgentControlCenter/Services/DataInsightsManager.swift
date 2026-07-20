import Foundation
import Observation

// MARK: - TokenUsage

/// Token 使用统计 — 输入/输出 token 数量。
struct TokenUsage: Codable, Equatable {
    /// 输入 token 数（用户消息 + 系统提示词）
    var inputTokens: Int = 0
    /// 输出 token 数（助手回复）
    var outputTokens: Int = 0
    /// 总 token 数（输入 + 输出）
    var total: Int { inputTokens + outputTokens }

    /// 零值实例
    static let zero = TokenUsage()
}

// MARK: - DailyActivity

/// 每日活动数据 — 某一天的会话数、消息数和 token 使用量。
struct DailyActivity: Identifiable, Codable, Equatable {
    /// 唯一标识（使用日期字符串）
    var id: String { dateString }
    /// 日期字符串（yyyy-MM-dd 格式）
    let dateString: String
    /// 当日消息数
    var messageCount: Int
    /// 当日会话数
    var sessionCount: Int
    /// 当日 token 使用量
    var tokenUsage: TokenUsage

    /// 日期对象（从 dateString 解析）
    var date: Date? {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = .current
        return formatter.date(from: dateString)
    }
}

// MARK: - AgentUsage

/// 单个 Agent 的使用统计。
struct AgentUsage: Identifiable, Codable, Equatable {
    /// 唯一标识（使用 agentId）
    var id: String { agentId }
    /// Agent ID
    let agentId: String
    /// Agent 显示名称
    let agentName: String
    /// 关联任务数
    var taskCount: Int
    /// 关联消息数
    var messageCount: Int
    /// Token 使用量
    var tokenUsage: TokenUsage
}

// MARK: - DataInsightsManager

/// 数据分析管理器 — 从 ChatRepository 和 SessionManager 聚合使用数据。
///
/// 职责：
/// - 聚合消息数、会话数、Token 使用量、每日活动、Agent 使用频率等指标
/// - 提供 `refresh()` 重新计算所有统计
/// - 提供计算属性：最活跃时段、最常用 Agent 等
///
/// 数据来源：
/// - `ChatRepository`：文件持久化的会话与消息（备份/导出数据）
/// - `SessionManager`：内存中的活跃会话列表
/// - `DataController`：SwiftData 中的实时消息数据（当 ChatRepository 无数据时作为回退源）
///
/// Token 估算说明：
/// 当前 `Message` 模型不含 token 计数字段，使用启发式估算：
/// - 中文按 1 字 ≈ 1 token
/// - 英文按 4 字符 ≈ 1 token
/// - 混合内容取两者较大值作为上界估算
@Observable
final class DataInsightsManager {

    // MARK: - 依赖

    /// 聊天数据仓库（文件持久化）
    private let chatRepository: ChatRepository

    /// 会话管理器（内存中的活跃会话）
    private let sessionManager: SessionManager

    /// 数据控制器（SwiftData，用于回退获取实时消息）
    private let dataController: DataController?

    // MARK: - 聚合属性

    /// 总消息数
    var messageCount: Int = 0

    /// 总会话数
    var sessionCount: Int = 0

    /// Token 使用统计
    var tokenUsage: TokenUsage = .zero

    /// 每日活动数据（按日期升序）
    var dailyActivity: [DailyActivity] = []

    /// 各 Agent 使用统计
    var agentUsage: [AgentUsage] = []

    // MARK: - 初始化

    /// 创建数据分析管理器。
    /// - Parameters:
    ///   - chatRepository: 聊天数据仓库
    ///   - sessionManager: 会话管理器
    ///   - dataController: 数据控制器（可选，用于回退获取实时消息）
    init(
        chatRepository: ChatRepository,
        sessionManager: SessionManager,
        dataController: DataController? = nil
    ) {
        self.chatRepository = chatRepository
        self.sessionManager = sessionManager
        self.dataController = dataController
    }

    // MARK: - 刷新

    /// 从 ChatRepository 和 SessionManager 聚合数据，刷新所有统计指标。
    ///
    /// 聚合策略：
    /// 1. 会话数：取 SessionManager 和 ChatRepository 中的并集
    /// 2. 消息数据：优先从 ChatRepository 加载，若为空则从 DataController 获取
    /// 3. Token：按消息角色和内容长度估算
    /// 4. 每日活动：按消息时间戳的日期分组
    /// 5. Agent 使用：按 sessionId 关联的任务和消息统计
    func refresh() {
        // 1. 会话数 — 取并集
        let memorySessions = sessionManager.sessions
        let fileSessions = chatRepository.loadedSessions
        let allSessionIds = Set(memorySessions.map(\.id) + fileSessions.map(\.id))
        sessionCount = allSessionIds.count

        // 2. 收集全部消息
        var allMessages: [Message] = []

        // 优先从 ChatRepository 加载
        let fileMessages = chatRepository.allMessages()
        if !fileMessages.isEmpty {
            allMessages = fileMessages
        } else if let dataController {
            // 回退到 DataController 实时数据
            for session in memorySessions {
                allMessages.append(contentsOf: dataController.fetchMessages(sessionId: session.id))
            }
        }

        messageCount = allMessages.count

        // 3. Token 估算
        tokenUsage = estimateTokenUsage(from: allMessages)

        // 4. 每日活动
        dailyActivity = computeDailyActivity(from: allMessages)

        // 5. Agent 使用
        agentUsage = computeAgentUsage(messages: allMessages)
    }

    // MARK: - 计算属性

    /// 最活跃时段（消息数最多的小时区间）。
    ///
    /// 统计所有消息按小时分布，返回消息数最多的两个小时组成的区间字符串。
    /// 若数据不足 5 条，返回 "数据不足"。
    var mostActiveHourRange: String {
        guard messageCount >= 5 else { return "数据不足" }

        var hourCounts: [Int: Int] = [:]
        for msg in collectedMessagesForAnalysis() {
            let date = Date(timeIntervalSince1970: TimeInterval(msg.timestamp) / 1000)
            let hour = Calendar.current.component(.hour, from: date)
            hourCounts[hour, default: 0] += 1
        }

        guard let maxHour = hourCounts.max(by: { $0.value < $1.value })?.key else {
            return "数据不足"
        }
        let nextHour = (maxHour + 1) % 24
        return String(format: "%02d:00 - %02d:00", maxHour, nextHour)
    }

    /// 最常用的 Agent（按关联任务数和消息数综合排序）。
    var mostUsedAgent: AgentUsage? {
        agentUsage.max(by: { lhs, rhs in
            lhs.taskCount + lhs.messageCount < rhs.taskCount + rhs.messageCount
        })
    }

    /// 平均每会话消息数
    var averageMessagesPerSession: Double {
        guard sessionCount > 0 else { return 0 }
        return Double(messageCount) / Double(sessionCount)
    }

    /// 最近 7 天的消息数
    var messagesInLast7Days: Int {
        let sevenDaysAgo = Date().addingTimeInterval(-7 * 24 * 3600)
        let cutoffTimestamp = Int64(sevenDaysAgo.timeIntervalSince1970 * 1000)
        return collectedMessagesForAnalysis().filter { $0.timestamp >= cutoffTimestamp }.count
    }

    // MARK: - 私有方法

    /// 收集用于分析的消息（优先 ChatRepository，回退 DataController）
    private func collectedMessagesForAnalysis() -> [Message] {
        let fileMessages = chatRepository.allMessages()
        if !fileMessages.isEmpty {
            return fileMessages
        }
        if let dataController {
            return sessionManager.sessions.flatMap {
                dataController.fetchMessages(sessionId: $0.id)
            }
        }
        return []
    }

    /// 估算消息列表的 Token 使用量。
    ///
    /// - 用户/系统消息计入 inputTokens
    /// - 助手消息计入 outputTokens
    /// - 工具消息计入 inputTokens
    private func estimateTokenUsage(from messages: [Message]) -> TokenUsage {
        var input = 0
        var output = 0
        for msg in messages {
            let tokens = estimateTokens(for: msg.content)
            switch msg.role {
            case .user, .system, .tool:
                input += tokens
            case .assistant:
                output += tokens
            }
        }
        return TokenUsage(inputTokens: input, outputTokens: output)
    }

    /// 估算单条文本的 Token 数。
    ///
    /// 启发式规则：
    /// - 中文字符数（1 字 ≈ 1 token）
    /// - 非中文字符数 / 4（4 字符 ≈ 1 token）
    /// - 取两者之和
    private func estimateTokens(for text: String) -> Int {
        var chineseCount = 0
        var otherCount = 0
        for scalar in text.unicodeScalars {
            if scalar.value >= 0x4E00 && scalar.value <= 0x9FFF {
                chineseCount += 1
            } else {
                otherCount += 1
            }
        }
        return chineseCount + otherCount / 4
    }

    /// 按日期分组计算每日活动数据。
    private func computeDailyActivity(from messages: [Message]) -> [DailyActivity] {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = .current

        var dailyMap: [String: DailyActivity] = [:]

        for msg in messages {
            let date = Date(timeIntervalSince1970: TimeInterval(msg.timestamp) / 1000)
            let dateKey = formatter.string(from: date)

            var activity = dailyMap[dateKey] ?? DailyActivity(
                dateString: dateKey,
                messageCount: 0,
                sessionCount: 0,
                tokenUsage: .zero
            )
            activity.messageCount += 1
            let tokens = estimateTokens(for: msg.content)
            switch msg.role {
            case .user, .system, .tool:
                activity.tokenUsage.inputTokens += tokens
            case .assistant:
                activity.tokenUsage.outputTokens += tokens
            }
            dailyMap[dateKey] = activity
        }

        // 统计每日会话数（按会话创建时间归入对应日期）
        let allSessions = sessionManager.sessions + chatRepository.loadedSessions
        var seenSessionIds = Set<String>()
        for session in allSessions {
            if seenSessionIds.contains(session.id) { continue }
            seenSessionIds.insert(session.id)
            let date = Date(timeIntervalSince1970: TimeInterval(session.createdAt) / 1000)
            let dateKey = formatter.string(from: date)
            if var activity = dailyMap[dateKey] {
                activity.sessionCount += 1
                dailyMap[dateKey] = activity
            }
        }

        // 按日期升序排列
        return dailyMap.values.sorted { $0.dateString < $1.dateString }
    }

    /// 计算 Agent 使用统计。
    ///
    /// 基于消息的 sessionId 关联会话，再按 Agent 分组统计。
    /// 由于 Message 模型不直接持有 agentId，这里按会话维度统计：
    /// 每个会话视为一个 Agent 的使用（以会话标题作为 Agent 名称占位）。
    private func computeAgentUsage(messages: [Message]) -> [AgentUsage] {
        // 按 sessionId 分组统计消息
        var sessionMessageCounts: [String: (count: Int, tokens: TokenUsage)] = [:]
        for msg in messages {
            var entry = sessionMessageCounts[msg.sessionId] ?? (0, .zero)
            entry.count += 1
            let tokens = estimateTokens(for: msg.content)
            switch msg.role {
            case .user, .system, .tool:
                entry.tokens.inputTokens += tokens
            case .assistant:
                entry.tokens.outputTokens += tokens
            }
            sessionMessageCounts[msg.sessionId] = entry
        }

        // 从 Task 数据补充 Agent 维度（如果 DataController 可用）
        var agentTaskCounts: [String: Int] = [:]
        var agentNames: [String: String] = [:]
        if let dataController {
            for task in dataController.fetchTasks() {
                agentTaskCounts[task.agentId, default: 0] += 1
                // 尝试获取 Agent 名称
                if agentNames[task.agentId] == nil {
                    agentNames[task.agentId] = task.agentId
                }
            }
        }

        // 构建结果：按会话维度生成 AgentUsage
        let allSessions = sessionManager.sessions + chatRepository.loadedSessions
        var seenIds = Set<String>()
        var results: [AgentUsage] = []

        for session in allSessions {
            if seenIds.contains(session.id) { continue }
            seenIds.insert(session.id)

            let msgData = sessionMessageCounts[session.id] ?? (0, .zero)
            let usage = AgentUsage(
                agentId: session.id,
                agentName: session.title.isEmpty ? "未命名会话" : session.title,
                taskCount: 0,
                messageCount: msgData.count,
                tokenUsage: msgData.tokens
            )
            results.append(usage)
        }

        // 按消息数降序排列
        return results.sorted { $0.messageCount > $1.messageCount }
    }
}
