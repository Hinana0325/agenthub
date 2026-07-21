import Foundation

// MARK: - Message Model
// 对应 protocol/schemas/message-schema.json

/// 消息角色。注意大小写差异：HTTP API 用小写，WebSocket 用枚举名首字母大写。
enum MessageRole: String, Codable {
    case user = "User"
    case assistant = "Assistant"
    case system = "System"
    case tool = "Tool"

    /// HTTP API 中的小写形式
    var apiValue: String { rawValue.lowercased() }
}

/// 消息投递状态
enum MessageStatus: String, Codable {
    case sending = "Sending"
    case sent = "Sent"
    case received = "Received"
    case failed = "Failed"
}

/// 附件类型
enum AttachmentType: String, Codable {
    case image = "image"
    case file = "file"
}

/// 聊天消息模型
struct Message: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var sessionId: String
    var role: MessageRole
    var content: String
    var timestamp: Int64
    var status: MessageStatus
    var metadataJson: String = "{}"
    var attachmentType: AttachmentType? = nil
    var attachmentData: String? = nil
    var attachmentName: String? = nil
    var reaction: String = ""
    var replyToId: String? = nil
}
