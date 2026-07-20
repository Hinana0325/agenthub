import Foundation
import SwiftData

// MARK: - SwiftData 持久化实体
// 对应 protocol/schemas/ 下的 JSON Schema 契约与 Android Room 实体
// (com.agenthub.app.core.database.entity.*)

/// Session 实体 — 对应 `protocol/schemas/session-schema.json` 与 Android `SessionEntity`。
///
/// SwiftData 持久化模型，字段与 `Session` 领域模型一一对应。
/// `id` 标记为唯一属性，用于 upsert 语义。
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
/// 通过 `sessionId` 与 `SessionEntity` 关联（冗余外键，便于按会话查询）。
/// `session` 关系属性为可选的 SwiftData to-one 关系；DataController 使用 `sessionId`
/// 进行关联与过滤，以保持与 Android Room 的查询模式一致。
@Model
final class MessageEntity {
    /// 消息唯一 ID
    @Attribute(.unique) var id: String
    /// 所属会话的关系引用（可选，SwiftData 自动推断 to-one 关系）
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

    /// 创建 Agent 配置实体
    init(id: String, name: String, type: String, serverUrl: String, apiKey: String, model: String, systemPrompt: String, temperature: Float, maxTokens: Int) {
        self.id = id
        self.name = name
        self.type = type
        self.serverUrl = serverUrl
        self.apiKey = apiKey
        self.model = model
        self.systemPrompt = systemPrompt
        self.temperature = temperature
        self.maxTokens = maxTokens
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
    var description: String
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
        self.description = description
        self.icon = icon
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
    ///
    /// 与 Android `AgentConfig.toEntity()` 行为一致。
    /// - Parameter config: 领域模型（apiKey 为明文）
    convenience init(from config: AgentConfig) {
        self.init(
            id: config.id,
            name: config.name,
            type: config.type.rawValue,
            serverUrl: config.serverUrl,
            apiKey: KeychainManager.encrypt(config.apiKey),
            model: config.model,
            systemPrompt: config.systemPrompt,
            temperature: config.temperature,
            maxTokens: config.maxTokens
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
            maxTokens: maxTokens
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
            maxTokens: entity.maxTokens
        )
    }
}
