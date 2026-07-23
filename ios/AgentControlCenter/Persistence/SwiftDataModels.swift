import Foundation
import SwiftData

// MARK: - SwiftData 持久化实体
// 对应 protocol/schemas/ 下的 JSON Schema 契约与 Android Room 实体
// (com.agentcontrolcenter.app.core.database.entity.*)

// MARK: - Schema 版本与迁移计划
//
// 当前最新为 v2。通过 `VersionedSchema` 显式标记版本号，通过
// `SchemaMigrationPlan` 声明 v1 → v2 的迁移阶段。
// v2 新增 2 个实体：`WorkflowRunEntity`（工作流执行历史）、
// `MarketplaceFavoriteEntity`（Marketplace 收藏）。均为新增表，无破坏性
// 变更，可由 SwiftData 自动执行轻量级迁移（`.lightweight`）。

/// 应用持久化 schema 的版本 1。
///
/// 仅包含 v1 发布时的 6 个实体。v2 新增 `WorkflowRunEntity` /
/// `MarketplaceFavoriteEntity`，在 `AppSchemaV2` 中声明。
enum AppSchemaV1: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(1, 0, 0) }

    static var models: [any PersistentModel.Type] {
        [
            SessionEntity.self,
            MessageEntity.self,
            AgentConfigEntity.self,
            TaskEntity.self,
            PluginEntity.self,
            ActivityLogEntity.self
        ]
    }
}

/// 应用持久化 schema 的版本 2（v4.9.0）。
///
/// 在 v1 的 6 个实体基础上新增：
/// - `WorkflowRunEntity`：工作流执行历史记录，对应 protocol
///   `WorkflowRunRecord` 契约与 Android `WorkflowRunEntity`。
/// - `MarketplaceFavoriteEntity`：Marketplace 收藏，对应 Android
///   `MarketplaceFavoriteEntity`。
///
/// v1 → v2 为纯增量迁移（新增表），由 `AppSchemaMigrationPlan` 声明
/// `.lightweight` 阶段，SwiftData 自动执行。
enum AppSchemaV2: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(2, 0, 0) }

    static var models: [any PersistentModel.Type] {
        [
            SessionEntity.self,
            MessageEntity.self,
            AgentConfigEntity.self,
            TaskEntity.self,
            PluginEntity.self,
            ActivityLogEntity.self,
            WorkflowRunEntity.self,
            MarketplaceFavoriteEntity.self
        ]
    }
}

/// 应用 schema 迁移计划。
///
/// v4.9.0：v1 → v2 为纯增量迁移（新增 `workflow_runs` /
/// `marketplace_favorites` 两张表，无既有字段变更），声明 `.lightweight`
/// 阶段由 SwiftData 自动迁移。
enum AppSchemaMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] {
        [AppSchemaV1.self, AppSchemaV2.self]
    }

    static var stages: [MigrationStage] {
        [.lightweight(fromVersion: AppSchemaV1.self, toVersion: AppSchemaV2.self)]
    }
}

/// Session 实体 — 对应 `protocol/schemas/session-schema.json` 与 Android `SessionEntity`。
///
/// SwiftData 持久化模型，字段与 `Session` 领域模型一一对应。
/// `id` 标记为唯一属性，用于 upsert 语义。
///
/// `messages` 关系使用 `.cascade` 删除规则：删除 Session 时自动删除其下全部 Message，
/// 避免出现孤儿消息（Android Room 通过外键约束 + `ON DELETE CASCADE` 实现同等语义）。
@Model
final class SessionEntity {
    /// 会话唯一 ID（对应 Session.id）
    @Attribute(.unique) var id: String
    /// 会话标题
    var title: String
    /// 创建时间（毫秒时间戳）
    var createdAt: Int64
    /// 最后更新时间（毫秒时间戳），用于列表排序
    var updatedAt: Int64
    /// 是否置顶
    var isPinned: Bool = false
    /// 消息计数（冗余字段，便于会话列表展示）
    var messageCount: Int = 0
    /// 会话摘要
    var summary: String = ""
    /// 会话下的全部消息（一对多关系，删除会话时级联删除消息）
    ///
    /// CI-fix: `@Relationship` 第一个参数为 `Schema.Attribute.Option` 不对，
    /// `Schema.Relationship.Option` 没有 `.cascade` 成员。正确写法是用
    /// `deleteRule:` 关键字参数显式指定删除规则。
    @Relationship(deleteRule: .cascade, inverse: \MessageEntity.session)
    var messages: [MessageEntity] = []

    /// 创建会话实体
    /// - Parameters:
    ///   - id: 会话唯一 ID
    ///   - title: 会话标题
    ///   - createdAt: 创建时间（毫秒）
    ///   - updatedAt: 更新时间（毫秒）
    init(id: String, title: String, createdAt: Int64, updatedAt: Int64) {
        self.id = id
        self.title = title
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

/// Message 实体 — 对应 `protocol/schemas/message-schema.json` 与 Android `MessageEntity`。
///
/// 通过 `session`（SwiftData 关系）与 `sessionId`（冗余外键）双重关联 SessionEntity：
/// - `session` 用于级联删除（`.cascade` rule 在 SessionEntity 侧声明）；
/// - `sessionId` 用于 predicate 过滤，保持与 Android Room 的查询模式一致。
@Model
final class MessageEntity {
    /// 消息唯一 ID
    @Attribute(.unique) var id: String
    /// 所属会话的关系引用（可选，与 `SessionEntity.messages` 构成双向关系）
    var session: SessionEntity?
    /// 所属会话 ID（冗余外键，用于 predicate 过滤）
    var sessionId: String
    /// 消息角色（MessageRole.rawValue：User / Assistant / System / Tool）
    var role: String
    /// 消息文本内容
    var content: String
    /// 发送时间（毫秒时间戳）
    var timestamp: Int64
    /// 投递状态（MessageStatus.rawValue：Sending / Sent / Received / Failed）
    var status: String
    /// 元数据 JSON 字符串
    var metadataJson: String = "{}"
    /// 附件类型（AttachmentType.rawValue：image / file），无附件时为 nil
    var attachmentType: String? = nil
    /// 附件数据（Base64 或 URL）
    var attachmentData: String? = nil
    /// 附件文件名
    var attachmentName: String? = nil
    /// 表情回应
    var reaction: String = ""
    /// 回复的目标消息 ID
    var replyToId: String? = nil

    /// 创建消息实体
    /// - Parameters:
    ///   - id: 消息唯一 ID
    ///   - sessionId: 所属会话 ID
    ///   - role: 消息角色 rawValue
    ///   - content: 消息文本内容
    ///   - timestamp: 发送时间（毫秒）
    ///   - status: 投递状态 rawValue
    init(id: String, sessionId: String, role: String, content: String, timestamp: Int64, status: String) {
        self.id = id
        self.sessionId = sessionId
        self.role = role
        self.content = content
        self.timestamp = timestamp
        self.status = status
    }
}

/// AgentConfig 实体 — 对应 `protocol/schemas/agent-schema.json` 的 AgentConfig 与 Android `AgentConfigEntity`。
///
/// `apiKey` 字段在持久化时使用 `AKS:` 前缀格式加密存储（由 `KeychainManager` 处理），
/// 读取时通过 `KeychainManager.decryptOrRaw` 解密或原样返回（向后兼容旧版明文）。
@Model
final class AgentConfigEntity {
    /// 配置唯一 ID（默认 "default"）
    @Attribute(.unique) var id: String
    /// Agent 名称
    var name: String
    /// Agent 类型（AgentType.rawValue：Hermes / OpenCode / OpenClaw / OpenAI / XiaomiMiMo / LocalModel）
    var type: String
    /// 服务端地址
    var serverUrl: String
    /// API Key（加密存储，格式：`AKS:` + Base64(IV ‖ ciphertext)）
    var apiKey: String
    /// 模型标识
    var model: String
    /// 系统提示词
    var systemPrompt: String
    /// 采样温度
    var temperature: Float
    /// 最大 token 数
    var maxTokens: Int
    /// 通信协议类型（AgentProtocol.rawValue：WebSocket / HttpSSE / MCP / Local），默认 WebSocket
    var protocolType: String = "WebSocket"

    /// 创建 Agent 配置实体
    /// - Parameter protocolType: 通信协议类型 rawValue，默认 "webSocket"（向后兼容已有调用方）
    init(id: String, name: String, type: String, serverUrl: String, apiKey: String, model: String, systemPrompt: String, temperature: Float, maxTokens: Int, protocolType: String = "webSocket") {
        self.id = id
        self.name = name
        self.type = type
        self.serverUrl = serverUrl
        self.apiKey = apiKey
        self.model = model
        self.systemPrompt = systemPrompt
        self.temperature = temperature
        self.maxTokens = maxTokens
        self.protocolType = protocolType
    }
}

/// Task 实体 — 对应 `protocol/schemas/task-schema.json` 与 Android `TaskEntity`。
@Model
final class TaskEntity {
    /// 任务唯一 ID
    @Attribute(.unique) var id: String
    /// 执行任务的 Agent ID
    var agentId: String
    /// 关联会话 ID（可选）
    var sessionId: String?
    /// 任务类型（TaskType.rawValue：CHAT / CODE / WORKFLOW / TOOL_CALL / FILE_OPERATION）
    var type: String
    /// 任务输入内容
    var input: String
    /// 任务状态（TaskStatus.rawValue：Pending / Running / Completed / Failed / Cancelled）
    var status: String
    /// 任务结果（终态时填充）
    var result: String?
    /// 创建时间（毫秒时间戳）
    var createdAt: Int64
    /// 完成时间（毫秒时间戳，终态时填充）
    var completedAt: Int64?
    /// 错误信息（失败时填充）
    var error: String?

    /// 创建任务实体
    init(id: String, agentId: String, sessionId: String?, type: String, input: String, status: String, result: String?, createdAt: Int64, completedAt: Int64?, error: String?) {
        self.id = id
        self.agentId = agentId
        self.sessionId = sessionId
        self.type = type
        self.input = input
        self.status = status
        self.result = result
        self.createdAt = createdAt
        self.completedAt = completedAt
        self.error = error
    }
}

/// Plugin 实体 — 对应 `protocol/schemas/plugin-schema.json` 与 Android `PluginEntity`。
///
/// 动作以 `actionType`（http / broadcast / workflow / none）+ `actionConfig`（JSON）两列存储，
/// 对应 `PluginAction` 判别联合。
@Model
final class PluginEntity {
    /// 插件唯一 ID
    @Attribute(.unique) var id: String
    /// 插件名称
    var name: String
    /// 插件描述
    ///
    /// CI-fix: 原属性名 `description` 与 SwiftData `@Model` 宏合成的
    /// `CustomStringConvertible.description` 冲突，编译报
    /// "A stored property cannot be named 'description'"。重命名为 `descriptionText`，
    /// 通过 `@Attribute(originalName: "description")` 保留 DB 列名以与 Android 端
    /// schema 对齐。`originalName:` 是 SwiftData `@Attribute` 宏的正式参数，
    /// 用于声明持久化层使用的列名（v1 schema 也生效），早期尝试的
    /// `@Attribute(name:)` 不存在该参数标签，编译报 "extraneous argument label"。
    @Attribute(originalName: "description") var descriptionText: String
    /// 图标标识
    var icon: String
    /// 是否启用
    var isEnabled: Bool = true
    /// 权限列表 JSON 字符串
    var permissionsJson: String = "[]"
    /// 插件版本
    var version: String = "1.0.0"
    /// 动作类型（PluginActionType.rawValue：http / broadcast / workflow / none）
    var actionType: String = "none"
    /// 动作配置 JSON 字符串
    var actionConfig: String = ""

    /// 创建插件实体
    init(id: String, name: String, description: String, icon: String) {
        self.id = id
        self.name = name
        self.descriptionText = description
        self.icon = icon
    }
}

/// ActivityLog 实体 — 对应 Android `ActivityLogEntity`（`activity_log` 表）。
///
/// 记录用户操作行为日志（如发送消息、创建任务、执行工作流等），
/// 用于 ActivityView 展示近期活动时间线。与 Android 端字段一一对应：
/// - `id`：主键（与 Android `@PrimaryKey id: String` 对齐）
/// - `type`：活动类型分类（如 "message_sent" / "task_created"）
/// - `title`：活动标题
/// - `description`：活动详情（默认空串）
/// - `timestamp`：毫秒时间戳，用于排序
///
/// SwiftData 通过 `@Attribute(.unique)` 保证 id 唯一；
/// 查询时按 `timestamp DESC` 排序并取前 200 条（对应 Android
/// `ActivityDao.getAllActivities()` 的 `ORDER BY timestamp DESC LIMIT 200`）。
@Model
final class ActivityLogEntity {
    /// 活动日志唯一 ID
    @Attribute(.unique) var id: String
    /// 活动类型（如 "message_sent" / "task_created" / "workflow_executed"）
    var type: String
    /// 活动标题
    var title: String
    /// 活动详情
    var descriptionText: String
    /// 毫秒时间戳
    var timestamp: Int64

    /// 创建活动日志实体
    /// - Parameters:
    ///   - id: 唯一 ID
    ///   - type: 活动类型
    ///   - title: 活动标题
    ///   - descriptionText: 活动详情（默认空串）
    ///   - timestamp: 毫秒时间戳
    init(id: String, type: String, title: String, descriptionText: String = "", timestamp: Int64) {
        self.id = id
        self.type = type
        self.title = title
        self.descriptionText = descriptionText
        self.timestamp = timestamp
    }
}

/// WorkflowRun 实体 — 对应 `protocol/schemas/workflow-schema.json` 中的
/// `WorkflowRunRecord` 契约与 Android `WorkflowRunEntity`（`workflow_runs` 表）。
///
/// 每次 `WorkflowEngine.execute()` 生成一条记录并持久化，供用户回看执行历史。
/// 与 `WorkflowExecutionState`（运行时内存快照）不同，本实体是落库的历史实体。
/// 双端字段一致以保证历史数据格式统一。
///
/// 状态枚举（`status`）：`RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED`。
/// `failedNodeIdsJson` / `logsJson` 以 JSON 字符串序列化数组，避免引入额外
/// TypeConverter（与 Android 端 `failedNodeIdsJson` / `logsJson` 字段对齐）。
@Model
final class WorkflowRunEntity {
    /// 执行记录唯一标识（UUID）
    @Attribute(.unique) var id: String
    /// 关联的工作流 ID（工作流定义本身可能被删除，此字段用于历史归属）
    var workflowId: String
    /// 工作流名称快照（冗余存储，防止工作流被删后历史无意义）
    var workflowName: String
    /// 执行输入文本
    var input: String = ""
    /// 执行最终输出（OUTPUT 节点的输出，失败时可能为空）
    var output: String = ""
    /// 执行开始时间戳（Unix 毫秒）
    var startedAt: Int64
    /// 执行结束时间戳（Unix 毫秒，执行中为 nil）
    var completedAt: Int64? = nil
    /// 执行状态（RUNNING / COMPLETED / FAILED / CANCELLED）
    var status: String
    /// 失败节点 ID 列表的 JSON 字符串（正常完成时为 "[]"）
    var failedNodeIdsJson: String = "[]"
    /// 错误信息（status=FAILED 时设置）
    var error: String? = nil
    /// 执行日志快照的 JSON 字符串（按时间顺序的字符串列表）
    var logsJson: String = "[]"

    /// 创建工作流执行记录实体
    /// - Parameters:
    ///   - id: 执行记录唯一 ID
    ///   - workflowId: 关联的工作流 ID
    ///   - workflowName: 工作流名称快照
    ///   - input: 执行输入文本
    ///   - startedAt: 开始时间戳（毫秒）
    ///   - status: 初始状态（通常为 "RUNNING"）
    init(
        id: String,
        workflowId: String,
        workflowName: String,
        input: String = "",
        output: String = "",
        startedAt: Int64,
        completedAt: Int64? = nil,
        status: String,
        failedNodeIdsJson: String = "[]",
        error: String? = nil,
        logsJson: String = "[]"
    ) {
        self.id = id
        self.workflowId = workflowId
        self.workflowName = workflowName
        self.input = input
        self.output = output
        self.startedAt = startedAt
        self.completedAt = completedAt
        self.status = status
        self.failedNodeIdsJson = failedNodeIdsJson
        self.error = error
        self.logsJson = logsJson
    }
}

/// MarketplaceFavorite 实体 — 对应 Android `MarketplaceFavoriteEntity`
/// （`marketplace_favorites` 表）。
///
/// 存储 `MarketplaceAgent` 的快照，便于从收藏列表直接安装，无需再次请求
/// Marketplace API。`agentId` 为主键（唯一），重复收藏覆盖旧记录以刷新元数据。
///
/// 字段映射说明（iOS `MarketplaceAgent` 与 Android `MarketplaceAgent` 差异）：
/// - `type`：Android 存 `agent.type.name`（AgentType 枚举名）。iOS
///   `MarketplaceAgent` 无 AgentType 字段，改存 `agent.category`（市场分类），
///   作为同等的分类信息来源（详见 `DataController.toggleMarketplaceFavorite`）。
/// - `tagsJson`：Android 存 `agent.tags`。iOS `MarketplaceAgent` 无 tags，
///   改存 `agent.capabilities` 的 JSON（最接近的"标签/能力"概念）。
///
/// `descriptionText` 复用 `PluginEntity` 模式：SwiftData `@Model` 不能用
/// `description` 作属性名（与合成的 `CustomStringConvertible.description` 冲突），
/// 通过 `@Attribute(originalName: "description")` 保留 DB 列名为 "description"，
/// 与 Android 列名对齐。
@Model
final class MarketplaceFavoriteEntity {
    /// Agent 唯一标识（主键）
    @Attribute(.unique) var agentId: String
    /// Agent 显示名称
    var name: String
    /// Agent 描述（列名保持 "description" 以与 Android 对齐）
    @Attribute(originalName: "description") var descriptionText: String
    /// 分类信息（iOS 存 `MarketplaceAgent.category`）
    var type: String
    /// 服务器地址
    var serverUrl: String
    /// 作者
    var author: String
    /// 能力标签的 JSON 字符串（iOS 存 `MarketplaceAgent.capabilities` 序列化）
    var tagsJson: String = "[]"
    /// 收藏时间戳（Unix 毫秒）
    var addedAt: Int64

    /// 创建收藏实体
    /// - Parameters:
    ///   - agentId: Agent 唯一 ID
    ///   - name: Agent 名称
    ///   - descriptionText: Agent 描述
    ///   - type: 分类信息
    ///   - serverUrl: 服务器地址
    ///   - author: 作者
    ///   - tagsJson: 能力标签 JSON
    ///   - addedAt: 收藏时间戳（毫秒）
    init(
        agentId: String,
        name: String,
        descriptionText: String = "",
        type: String,
        serverUrl: String,
        author: String,
        tagsJson: String = "[]",
        addedAt: Int64
    ) {
        self.agentId = agentId
        self.name = name
        self.descriptionText = descriptionText
        self.type = type
        self.serverUrl = serverUrl
        self.author = author
        self.tagsJson = tagsJson
        self.addedAt = addedAt
    }
}

// MARK: - Session <-> SessionEntity 转换

extension SessionEntity {
    /// 从领域模型 `Session` 构造实体（用于新增）。
    /// - Parameter session: 领域模型
    convenience init(from session: Session) {
        self.init(
            id: session.id,
            title: session.title,
            createdAt: session.createdAt,
            updatedAt: session.updatedAt
        )
        self.isPinned = session.isPinned
        self.messageCount = session.messageCount
        self.summary = session.summary
    }

    /// 转换为领域模型 `Session`。
    func toSession() -> Session {
        Session(
            id: id,
            title: title,
            createdAt: createdAt,
            updatedAt: updatedAt,
            isPinned: isPinned,
            messageCount: messageCount,
            summary: summary
        )
    }
}

extension Session {
    /// 从持久化实体构造领域模型（便捷构造器）。
    /// - Parameter entity: SwiftData 实体
    init(from entity: SessionEntity) {
        self.init(
            id: entity.id,
            title: entity.title,
            createdAt: entity.createdAt,
            updatedAt: entity.updatedAt,
            isPinned: entity.isPinned,
            messageCount: entity.messageCount,
            summary: entity.summary
        )
    }
}

// MARK: - Message <-> MessageEntity 转换

extension MessageEntity {
    /// 从领域模型 `Message` 构造实体。
    /// 枚举字段（role / status / attachmentType）以 `rawValue` 字符串形式存储。
    /// - Parameter message: 领域模型
    convenience init(from message: Message) {
        self.init(
            id: message.id,
            sessionId: message.sessionId,
            role: message.role.rawValue,
            content: message.content,
            timestamp: message.timestamp,
            status: message.status.rawValue
        )
        self.metadataJson = message.metadataJson
        self.attachmentType = message.attachmentType?.rawValue
        self.attachmentData = message.attachmentData
        self.attachmentName = message.attachmentName
        self.reaction = message.reaction
        self.replyToId = message.replyToId
    }

    /// 转换为领域模型 `Message`。
    ///
    /// 字符串字段通过 `init(rawValue:)` 还原为枚举，解析失败时回退到默认值
    /// （role → .system，status → .sent），与 Android `MessageEntity.toModel()` 行为一致。
    func toMessage() -> Message {
        Message(
            id: id,
            sessionId: sessionId,
            role: MessageRole(rawValue: role) ?? .system,
            content: content,
            timestamp: timestamp,
            status: MessageStatus(rawValue: status) ?? .sent,
            metadataJson: metadataJson,
            attachmentType: attachmentType.flatMap { AttachmentType(rawValue: $0) },
            attachmentData: attachmentData,
            attachmentName: attachmentName,
            reaction: reaction,
            replyToId: replyToId
        )
    }
}

extension Message {
    /// 从持久化实体构造领域模型（便捷构造器）。
    /// - Parameter entity: SwiftData 实体
    init(from entity: MessageEntity) {
        self.init(
            id: entity.id,
            sessionId: entity.sessionId,
            role: MessageRole(rawValue: entity.role) ?? .system,
            content: entity.content,
            timestamp: entity.timestamp,
            status: MessageStatus(rawValue: entity.status) ?? .sent,
            metadataJson: entity.metadataJson,
            attachmentType: entity.attachmentType.flatMap { AttachmentType(rawValue: $0) },
            attachmentData: entity.attachmentData,
            attachmentName: entity.attachmentName,
            reaction: entity.reaction,
            replyToId: entity.replyToId
        )
    }
}

// MARK: - AgentTask <-> TaskEntity 转换

extension TaskEntity {
    /// 从领域模型 `AgentTask` 构造实体。
    /// 枚举字段（type / status）以 `rawValue` 字符串形式存储。
    /// - Parameter task: 领域模型
    convenience init(from task: AgentTask) {
        self.init(
            id: task.id,
            agentId: task.agentId,
            sessionId: task.sessionId,
            type: task.type.rawValue,
            input: task.input,
            status: task.status.rawValue,
            result: task.result,
            createdAt: task.createdAt,
            completedAt: task.completedAt,
            error: task.error
        )
    }

    /// 转换为领域模型 `AgentTask`。
    ///
    /// 字符串字段通过 `init(rawValue:)` 还原为枚举，解析失败时回退到默认值
    /// （type → .chat，status → .pending）。
    func toTask() -> AgentTask {
        AgentTask(
            id: id,
            agentId: agentId,
            sessionId: sessionId,
            type: TaskType(rawValue: type) ?? .chat,
            input: input,
            status: TaskStatus(rawValue: status) ?? .pending,
            result: result,
            createdAt: createdAt,
            completedAt: completedAt,
            error: error
        )
    }
}

extension AgentTask {
    /// 从持久化实体构造领域模型（便捷构造器）。
    /// - Parameter entity: SwiftData 实体
    init(from entity: TaskEntity) {
        self.init(
            id: entity.id,
            agentId: entity.agentId,
            sessionId: entity.sessionId,
            type: TaskType(rawValue: entity.type) ?? .chat,
            input: entity.input,
            status: TaskStatus(rawValue: entity.status) ?? .pending,
            result: entity.result,
            createdAt: entity.createdAt,
            completedAt: entity.completedAt,
            error: entity.error
        )
    }
}

// MARK: - AgentConfig <-> AgentConfigEntity 转换

extension AgentConfigEntity {
    /// 从领域模型 `AgentConfig` 构造实体。
    ///
    /// `apiKey` 通过 `KeychainManager.encrypt` 加密为 `AKS:` 前缀格式后存储：
    /// - 空字符串原样返回
    /// - 已加密（带 `AKS:` 前缀）原样返回，避免双重加密
    /// - 明文加密为 `AKS:` + Base64(IV ‖ ciphertext)
    /// - 加密失败 → 存空串占位（F16 修复：禁止明文落库）
    ///
    /// 与 Android `AgentConfig.toEntity()` 行为一致。
    /// - Parameter config: 领域模型（apiKey 为明文）
    convenience init(from config: AgentConfig) {
        self.init(
            id: config.id,
            name: config.name,
            type: config.type.rawValue,
            serverUrl: config.serverUrl,
            // F16 修复：原 `?? config.apiKey` 在加密失败时回退明文落库，
            // 让加密机制形同虚设。改为回退空串占位，由调用方（DataController.saveAgentConfig）
            // 检测失败并通过 lastError 暴露给 UI。
            apiKey: config.apiKey.isEmpty ? "" : (KeychainManager.encrypt(config.apiKey) ?? ""),
            model: config.model,
            systemPrompt: config.systemPrompt,
            temperature: config.temperature,
            maxTokens: config.maxTokens,
            // 持久化通信协议类型 rawValue
            protocolType: config.protocolType.rawValue
        )
    }

    /// 转换为领域模型 `AgentConfig`。
    ///
    /// `apiKey` 通过 `KeychainManager.decryptOrRaw` 还原：
    /// - 空值原样返回
    /// - 无 `AKS:` 前缀 → 视为旧版明文，原样返回（向后兼容）
    /// - `AKS:` 前缀但解密失败 → 返回空串（避免损坏密文当明文展示）
    ///
    /// 与 Android `AgentConfigEntity.toModel()` 行为一致。
    func toAgentConfig() -> AgentConfig {
        AgentConfig(
            id: id,
            name: name,
            type: AgentType(rawValue: type) ?? .hermes,
            serverUrl: serverUrl,
            apiKey: KeychainManager.decryptOrRaw(apiKey),
            model: model,
            systemPrompt: systemPrompt,
            temperature: temperature,
            maxTokens: maxTokens,
            // 还原通信协议类型，解析失败时回退到默认 .webSocket
            protocolType: AgentProtocol(rawValue: protocolType) ?? .webSocket
        )
    }
}

extension AgentConfig {
    /// 从持久化实体构造领域模型（便捷构造器）。
    /// - Parameter entity: SwiftData 实体
    init(from entity: AgentConfigEntity) {
        self.init(
            id: entity.id,
            name: entity.name,
            type: AgentType(rawValue: entity.type) ?? .hermes,
            serverUrl: entity.serverUrl,
            apiKey: KeychainManager.decryptOrRaw(entity.apiKey),
            model: entity.model,
            systemPrompt: entity.systemPrompt,
            temperature: entity.temperature,
            maxTokens: entity.maxTokens,
            // 还原通信协议类型，解析失败时回退到默认 .webSocket
            protocolType: AgentProtocol(rawValue: entity.protocolType) ?? .webSocket
        )
    }
}
