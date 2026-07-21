import Foundation
import SwiftData
import Observation
import os

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

    /// 最近一次持久化错误（fetch/save/delete 失败时写入，UI 可读取展示）
    ///
    /// C8 修复：原 16 处 `try?` 静默吞掉 SwiftData 错误，UI 表现为"无数据"
    /// 但无法区分"真的无数据"与"加载失败"。改为 do-catch 后写入此属性，
    /// SettingsView 等界面可监听并提示用户。
    private(set) var lastError: String?

    /// C8 修复：统一日志器，便于 Console.app 筛选持久化层错误
    private static let logger = Logger(subsystem: "com.agentcontrolcenter.app.ios", category: "DataController")

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
        if let existing = safeFetchFirst(descriptor, context: "saveSession.fetch") {
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
        let entities = safeFetch(descriptor, context: "fetchSessions", fallback: [])
        return entities.map { $0.toSession() }
    }

    /// 删除指定会话。
    /// - Parameter id: 会话 ID
    func deleteSession(_ id: String) {
        let targetId = id
        let descriptor = FetchDescriptor<SessionEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let entity = safeFetchFirst(descriptor, context: "deleteSession.fetch") {
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
        if let existing = safeFetchFirst(descriptor, context: "saveMessage.fetch") {
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
        let entities = safeFetch(descriptor, context: "fetchMessages", fallback: [])
        return entities.map { $0.toMessage() }
    }

    /// 删除指定消息。
    /// - Parameter id: 消息 ID
    func deleteMessage(id: String) {
        let targetId = id
        let descriptor = FetchDescriptor<MessageEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let entity = safeFetchFirst(descriptor, context: "deleteMessage.fetch") {
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
        let entities = safeFetch(descriptor, context: "deleteMessages.fetch", fallback: [])
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
        // F16 修复：原 `KeychainManager.encrypt(config.apiKey) ?? config.apiKey` 在加密失败时
        // 回退明文落库，让加密机制形同虚设。改为：非空 apiKey 加密失败时存空串占位并暴露 lastError。
        let encryptedApiKey: String
        if config.apiKey.isEmpty {
            encryptedApiKey = ""
        } else if let encrypted = KeychainManager.encrypt(config.apiKey) {
            encryptedApiKey = encrypted
        } else {
            encryptedApiKey = ""
            let msg = "saveAgentConfig: apiKey 加密失败，已存空串占位（原始 apiKey 未持久化，请重新输入）"
            Self.logger.error("\(msg, privacy: .public)")
            lastError = msg
        }
        if let existing = safeFetchFirst(descriptor, context: "saveAgentConfig.fetch") {
            existing.name = config.name
            existing.type = config.type.rawValue
            existing.serverUrl = config.serverUrl
            existing.apiKey = encryptedApiKey
            existing.model = config.model
            existing.systemPrompt = config.systemPrompt
            existing.temperature = config.temperature
            existing.maxTokens = config.maxTokens
        } else {
            // 插入路径：用已加密的 apiKey 构造临时 config，避免 entity init 再次加密（已加密串带 AKS: 前缀会被 encrypt 原样返回）
            var configForInsert = config
            configForInsert.apiKey = encryptedApiKey
            context.insert(AgentConfigEntity(from: configForInsert))
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
        let entities = safeFetch(descriptor, context: "fetchAgentConfigs", fallback: [])
        return entities.map { $0.toAgentConfig() }
    }

    /// 删除指定 Agent 配置。
    /// - Parameter id: 配置 ID
    func deleteAgentConfig(_ id: String) {
        let targetId = id
        let descriptor = FetchDescriptor<AgentConfigEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let entity = safeFetchFirst(descriptor, context: "deleteAgentConfig.fetch") {
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
        if let existing = safeFetchFirst(descriptor, context: "saveTask.fetch") {
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
        let entities = safeFetch(descriptor, context: "fetchTasks", fallback: [])
        return entities.map { $0.toTask() }
    }

    /// 删除指定任务。
    /// - Parameter id: 任务 ID
    func deleteTask(_ id: String) {
        let targetId = id
        let descriptor = FetchDescriptor<TaskEntity>(
            predicate: #Predicate { $0.id == targetId }
        )
        if let entity = safeFetchFirst(descriptor, context: "deleteTask.fetch") {
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
        return safeFetch(descriptor, context: "fetchActivities", fallback: [])
    }

    /// 清空全部活动日志。
    ///
    /// 与 Android `ActivityDao.clearAll()` 的 `DELETE FROM activity_log` 对齐。
    func clearActivities() {
        let descriptor = FetchDescriptor<ActivityLogEntity>()
        let entities = safeFetch(descriptor, context: "clearActivities.fetch", fallback: [])
        for entity in entities {
            context.delete(entity)
        }
        save()
    }

    // MARK: - Private

    /// 提交当前上下文的变更到持久化存储。
    ///
    /// C8 修复：原 `try? context.save()` 静默吞掉错误，数据丢失但 UI 无感知。
    /// 改为 do-catch 并通过 logger.error + lastError 暴露给上层。
    /// save() 失败时 mainContext 仍保留未提交的变更，下次 save() 会重试。
    private func save() {
        do {
            try context.save()
            // 成功时不清空 lastError（保留历史错误供 UI 一次性展示）
        } catch {
            let msg = "save() failed: \(error.localizedDescription)"
            Self.logger.error("\(msg, privacy: .public)")
            lastError = msg
        }
    }

    /// 通用 fetch 包装：捕获错误、记录日志、写入 lastError、返回 nil 或空数组。
    ///
    /// C8 修复：把 16 处 `try? context.fetch(...)` 统一收口到此处，
    /// 避免 logger 调用散落各处。`fallback` 控制失败时返回 nil 还是空数组。
    private func safeFetch<T>(_ descriptor: FetchDescriptor<T>, context: String, fallback: [T]) -> [T] {
        do {
            // CI-fix: 参数 `context: String` 与类属性 `var context: ModelContext` 同名遮蔽。
            // 日志标签用参数 `context`，fetch 操作用 `self.context` 显式引用 ModelContext。
            return try self.context.fetch(descriptor)
        } catch {
            let msg = "\(context) failed: \(error.localizedDescription)"
            Self.logger.error("\(msg, privacy: .public)")
            lastError = msg
            return fallback
        }
    }

    /// 通用 fetch 包装（单值版本）：返回首条匹配或 nil。
    private func safeFetchFirst<T>(_ descriptor: FetchDescriptor<T>, context: String) -> T? {
        safeFetch(descriptor, context: context, fallback: []).first
    }
}
