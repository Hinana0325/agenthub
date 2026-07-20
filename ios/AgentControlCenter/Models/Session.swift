import Foundation

// MARK: - Session Model
// 对应 protocol/schemas/session-schema.json

/// 会话模型。一个 Session 代表一次连续对话，包含多条 Message。
struct Session: Codable, Identifiable, Equatable {
    var id: String
    var title: String
    var createdAt: Int64
    var updatedAt: Int64
    var isPinned: Bool = false
    var messageCount: Int = 0
    var summary: String = ""
}
