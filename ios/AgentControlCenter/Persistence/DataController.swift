import Foundation
import SwiftData
import Observation

// MARK: - DataController
// 对应 Android com.agentcontrolcenter.app.data.repository.ChatRepository + AppDatabase
// 通过 SwiftData ModelContainer 提供持久化能力，封装 CRUD 操作。

/// 数据控制器 — 管理 SwiftData `ModelContainer` 并提供领域模型的 CRUD 操作。
///
/// 职责：
/// - 创建并持有 `ModelContainer`（注册全部 @Model 实体）
/// - 提供 Session / Message / AgentConfig / Task 的增删查接口
/// - 在 AgentConfig 写入/读取时自动加密/解密 apiKey（委托给实体转换层）
///
/// 与 Android 端的差异：
/// - Android 通过 Room `@Dao` + `ChatRepository` 分层，iOS 在此统一封装
/// - Android 使用 `OnConflictStrategy.REPLACE` 实现 upsert，iOS 使用「先查后更/插」显式 upsert
/// - Android 在 IO 线程执行 Keystore 操作，iOS 在主 ModelContext 同步执行
///   （SwiftData mainContext 绑定主线程，Keychain AES-GCM 操作足够快）
@MainActor
@Observable
final class DataController {

    /// SwiftData 容器，注册全部持久化实体类型
    ///
    /// 容器创建失败时降级为仅内存容器（`isStoredInMemoryOnly: true`），
    /// 避免迁移失败导致 App 启动即崩溃（用户至少能看到 UI，再通过日志排查）。
    /// 失败原因会通过 `lastInitError` 暴露给上层用于诊断与上报。
    let container: ModelContainer

    /// 容器初始化失败的原因（仅在降级到内存容器时非空）
    private(set) var lastInitError: String?

    /// 创建数据控制器并初始化 ModelContainer。
    ///
    /// 优先尝试使用磁盘持久化容器（含 `SchemaMigrationPlan` 路径，
    /// 当前为 v1，未来可在 `AppSchemaMigrationPlan` 中追加 v1→v2 阶段）；
    /// 失败时降级为内存容器，保证 App 可用性。
    init() {
        let schema = Schema([
            SessionEntity.self,
            MessageEntity.self,
            AgentConfigEntity.self,
            TaskEntity.self,
            PluginEntity.self,
            ActivityLogEntity.self
        ])
        // 当前为 v1，无历史 schema 需要迁移；预留 MigrationPlan 接口
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)
        do {
            container = try ModelContainer(for: schema, migrationPlan: AppSchemaMigrationPlan.self, configurations: config)
        } catch {
            lastInitError = "ModelContainer init failed: \(error). Falling back to in-memory store."
            // 降级：内存容器（无持久化，但 App 不崩）
            let memConfig = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
            // 二次失败只能放弃 — 此时用 do/catch 兜底为内存 Schema 空容器
            do {
                container = try ModelContainer(for: schema, configurations: memConfig)
            } catch {
                // 极端情况：连内存容器都建不起来。构造一个完全空的内存容器（空 Schema 不会失败）。
                // 使用空 Schema 而非原 Schema：原 Schema 已被证明无法构造容器，
                // 再传一次只会再次失败。空 Schema 没有任何实体，构造必定成功。
                lastInitError = "In-memory fallback also failed: \(error)"
                let emptySchema = Schema([])
                do {
                    container = try ModelContainer(
                        for: emptySchema,
                        configurations: ModelConfiguration(schema: emptySchema, isStoredInMemoryOnly: true)
                    )
                } catch {
                    // 真正的极端情况：连空 Schema 容器都建不起来——SwiftData 框架级故障。
                    // 此时 container（非可选）无任何可赋值路径，使用 fatalError 提供错误信息供诊断。
                    // 这比 try! 更安全：try! 会无声崩溃，fatalError 至少在崩溃报告中留下原因。
                    fatalError("ModelContainer unavailable even with empty schema: \(error)")
                }
            }
        }
    }

    /// 主上下文（绑定主线程，UI 操作使用此上下文）
    var context: ModelContext { container.mainContext }

    // MARK: - Session CRUD

    /// 保存或更新会话（upsert）。
    ///
    /// 若已存在相同 `id` 的实体，逐字段更新；否则插入新实体。
    /// - Parameter session: 领域模型
    func saveSession(_ session: Session) {
        let targetId = session.id
        let descriptor = FetchDescriptor<SessionEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let existing = try? context.fetch(descriptor).first {
            existing.title = session.title
            existing.createdAt = session.createdAt
            existing.updatedAt = session.updatedAt
            existing.isPinned = session.isPinned
            existing.messageCount = session.messageCount
            existing.summary = session.summary
        } else {
            context.insert(SessionEntity(from: session))
        }
        save()
    }

    /// 获取所有会话，按 `updatedAt` 降序排列（最近更新的在前）。
    /// - Returns: 领域模型列表
    func fetchSessions() -> [Session] {
        let descriptor = FetchDescriptor<SessionEntity>(
            sortBy: [SortDescriptor(\.updatedAt, order: .reverse)]
        )
        let entities = (try? context.fetch(descriptor)) ?? []
        return entities.map { $0.toSession() }
    }

    /// 删除指定会话。
    /// - Parameter id: 会话 ID
    func deleteSession(_ id: String) {
        let targetId = id
        let descriptor = FetchDescriptor<SessionEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let entity = try? context.fetch(descriptor).first {
            context.delete(entity)
            save()
        }
    }

    // MARK: - Message CRUD

    /// 保存或更新消息（upsert）。
    ///
    /// 若已存在相同 `id` 的实体，逐字段更新；否则插入新实体。
    /// 枚举字段以 `rawValue` 字符串形式持久化。
    /// - Parameter message: 领域模型
    func saveMessage(_ message: Message) {
        let targetId = message.id
        let descriptor = FetchDescriptor<MessageEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let existing = try? context.fetch(descriptor).first {
            existing.sessionId = message.sessionId
            existing.role = message.role.rawValue
            existing.content = message.content
            existing.timestamp = message.timestamp
            existing.status = message.status.rawValue
            existing.metadataJson = message.metadataJson
            existing.attachmentType = message.attachmentType?.rawValue
            existing.attachmentData = message.attachmentData
            existing.attachmentName = message.attachmentName
            existing.reaction = message.reaction
            existing.replyToId = message.replyToId
        } else {
            context.insert(MessageEntity(from: message))
        }
        save()
    }

    /// 获取指定会话的全部消息，按 `timestamp` 升序排列（最早发送的在前）。
    /// - Parameter sessionId: 会话 ID
    /// - Returns: 领域模型列表
    func fetchMessages(sessionId: String) -> [Message] {
        let targetId = sessionId
        let descriptor = FetchDescriptor<MessageEntity>(
            predicate: #Predicate { $0.sessionId == targetId },
            sortBy: [SortDescriptor(\.timestamp, order: .forward)]
        )
        let entities = (try? context.fetch(descriptor)) ?? []
        return entities.map { $0.toMessage() }
    }

    /// 删除指定消息。
    /// - Parameter id: 消息 ID
    func deleteMessage(id: String) {
        let targetId = id
        let descriptor = FetchDescriptor<MessageEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let entity = try? context.fetch(descriptor).first {
            context.delete(entity)
            save()
        }
    }

    /// 删除指定会话的全部消息。
    /// - Parameter sessionId: 会话 ID
    func deleteMessages(sessionId: String) {
        let targetId = sessionId
        let descriptor = FetchDescriptor<MessageEntity>(
            predicate: #Predicate { $0.sessionId == targetId }
        )
        guard let entities = try? context.fetch(descriptor) else { return }
        for entity in entities {
            context.delete(entity)
        }
        save()
    }

    // MARK: - AgentConfig CRUD

    /// 保存或更新 Agent 配置（upsert）。
    ///
    /// apiKey 在写入时自动加密：
    /// - 新增路径：通过 `AgentConfigEntity(from:)` 转换时调用 `KeychainManager.encrypt`
    /// - 更新路径：直接调用 `KeychainManager.encrypt` 覆盖 apiKey
    ///
    /// `KeychainManager.encrypt` 内部已处理空串与已加密（`AKS:` 前缀）场景，
    /// 避免双重加密，与 Android `AgentConfig.toEntity()` 行为一致。
    ///
    /// - Parameter config: 领域模型（apiKey 为明文）
    func saveAgentConfig(_ config: AgentConfig) {
        let targetId = config.id
        let descriptor = FetchDescriptor<AgentConfigEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let existing = try? context.fetch(descriptor).first {
            existing.name = config.name
            existing.type = config.type.rawValue
            existing.serverUrl = config.serverUrl
            existing.apiKey = KeychainManager.encrypt(config.apiKey) ?? config.apiKey
            existing.model = config.model
            existing.systemPrompt = config.systemPrompt
            existing.temperature = config.temperature
            existing.maxTokens = config.maxTokens
        } else {
            context.insert(AgentConfigEntity(from: config))
        }
        save()
    }

    /// 获取全部 Agent 配置，按 `name` 升序排列。
    ///
    /// apiKey 在读取时自动解密：通过 `AgentConfigEntity.toAgentConfig()` 转换时调用
    /// `KeychainManager.decryptOrRaw`，无 `AKS:` 前缀的旧版明文原样返回（向后兼容）。
    ///
    /// - Returns: 领域模型列表（apiKey 为明文）
    func fetchAgentConfigs() -> [AgentConfig] {
        let descriptor = FetchDescriptor<AgentConfigEntity>(
            sortBy: [SortDescriptor(\.name, order: .forward)]
        )
        let entities = (try? context.fetch(descriptor)) ?? []
        return entities.map { $0.toAgentConfig() }
    }

    /// 删除指定 Agent 配置。
    /// - Parameter id: 配置 ID
    func deleteAgentConfig(_ id: String) {
        let targetId = id
        let descriptor = FetchDescriptor<AgentConfigEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let entity = try? context.fetch(descriptor).first {
            context.delete(entity)
            save()
        }
    }

    // MARK: - Task CRUD

    /// 保存或更新任务（upsert）。
    ///
    /// 若已存在相同 `id` 的实体，逐字段更新；否则插入新实体。
    /// 枚举字段以 `rawValue` 字符串形式持久化。
    /// - Parameter task: 领域模型
    func saveTask(_ task: AgentTask) {
        let targetId = task.id
        let descriptor = FetchDescriptor<TaskEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let existing = try? context.fetch(descriptor).first {
            existing.agentId = task.agentId
            existing.sessionId = task.sessionId
            existing.type = task.type.rawValue
            existing.input = task.input
            existing.status = task.status.rawValue
            existing.result = task.result
            existing.createdAt = task.createdAt
            existing.completedAt = task.completedAt
            existing.error = task.error
        } else {
            context.insert(TaskEntity(from: task))
        }
        save()
    }

    /// 获取全部任务，按 `createdAt` 降序排列（最新创建的在前）。
    /// - Returns: 领域模型列表
    func fetchTasks() -> [AgentTask] {
        let descriptor = FetchDescriptor<TaskEntity>(
            sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
        )
        let entities = (try? context.fetch(descriptor)) ?? []
        return entities.map { $0.toTask() }
    }

    /// 删除指定任务。
    /// - Parameter id: 任务 ID
    func deleteTask(_ id: String) {
        let targetId = id
        let descriptor = FetchDescriptor<TaskEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let entity = try? context.fetch(descriptor).first {
            context.delete(entity)
            save()
        }
    }

    // MARK: - ActivityLog CRUD
    // 对应 Android ActivityDao：getAllActivities (LIMIT 200 DESC) / insertActivity / clearAll

    /// 记录一条活动日志。
    ///
    /// 与 Android `ActivityDao.insertActivity(...)` 对齐。
    /// id 使用 UUID 保证唯一，timestamp 取当前毫秒时间戳（调用方可覆盖）。
    /// - Parameters:
    ///   - type: 活动类型（如 "message_sent" / "task_created"）
    ///   - title: 活动标题
    ///   - descriptionText: 活动详情（默认空串）
    ///   - timestamp: 毫秒时间戳，默认取当前时间
    func logActivity(
        type: String,
        title: String,
        descriptionText: String = "",
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        let entity = ActivityLogEntity(
            id: UUID().uuidString,
            type: type,
            title: title,
            descriptionText: descriptionText,
            timestamp: timestamp
        )
        context.insert(entity)
        save()
    }

    /// 获取近期活动日志，按 `timestamp` 降序排列，最多 200 条。
    ///
    /// 与 Android `ActivityDao.getAllActivities()` 的
    /// `ORDER BY timestamp DESC LIMIT 200` 行为一致。
    /// - Returns: 活动日志实体列表（已排序）
    func fetchActivities(limit: Int = 200) -> [ActivityLogEntity] {
        var descriptor = FetchDescriptor<ActivityLogEntity>(
            sortBy: [SortDescriptor(\.timestamp, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        return (try? context.fetch(descriptor)) ?? []
    }

    /// 清空全部活动日志。
    ///
    /// 与 Android `ActivityDao.clearAll()` 的 `DELETE FROM activity_log` 对齐。
    func clearActivities() {
        let descriptor = FetchDescriptor<ActivityLogEntity>()
        if let entities = try? context.fetch(descriptor) {
            for entity in entities {
                context.delete(entity)
            }
            save()
        }
    }

    // MARK: - Private

    /// 提交当前上下文的变更到持久化存储。
    ///
    /// 失败时忽略错误（与 Room 自动事务语义对齐）。SwiftData 的 `save()`
    /// 在 mainContext 上调用时通常不会抛出，使用 `try?` 仅作兜底保护。
    private func save() {
        try? context.save()
    }
}
