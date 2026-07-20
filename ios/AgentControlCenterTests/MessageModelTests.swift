import XCTest
@testable import AgentControlCenter

// MARK: - Message 模型单元测试
final class MessageModelTests: XCTestCase {

    // MARK: - MessageRole 测试

    /// 测试 MessageRole 所有 case 的 apiValue
    func testMessageRoleApiValues() {
        // MessageRole 未遵循 CaseIterable，手动验证 4 个 case
        XCTAssertEqual(MessageRole.user.apiValue, "user")
        XCTAssertEqual(MessageRole.assistant.apiValue, "assistant")
        XCTAssertEqual(MessageRole.system.apiValue, "system")
        XCTAssertEqual(MessageRole.tool.apiValue, "tool")
    }

    /// 测试 MessageRole apiValue 全部为小写
    func testMessageRoleApiValueLowercased() {
        let roles: [MessageRole] = [.user, .assistant, .system, .tool]
        for role in roles {
            XCTAssertEqual(role.apiValue, role.apiValue.lowercased(),
                           "MessageRole.\(role.rawValue).apiValue 应为小写")
        }
    }

    /// 测试 MessageRole rawValue
    func testMessageRoleRawValues() {
        XCTAssertEqual(MessageRole.user.rawValue, "User")
        XCTAssertEqual(MessageRole.assistant.rawValue, "Assistant")
        XCTAssertEqual(MessageRole.system.rawValue, "System")
        XCTAssertEqual(MessageRole.tool.rawValue, "Tool")
    }

    /// 测试 MessageRole 数量（通过 Codable 解码验证）
    func testMessageRoleCount() {
        // MessageRole 未遵循 CaseIterable，验证已知 4 个 case 均可解码
        let expectedCount = 4
        var decodedCount = 0
        let rawValues = ["User", "Assistant", "System", "Tool"]
        for raw in rawValues {
            if let _ = MessageRole(rawValue: raw) {
                decodedCount += 1
            }
        }
        XCTAssertEqual(decodedCount, expectedCount, "MessageRole 应有 4 个可解码的 case")
    }

    // MARK: - Message 创建测试

    /// 测试 Message 创建（含可选字段 nil）
    func testMessageCreationWithOptionalNil() {
        let message = Message(
            id: "msg-1",
            sessionId: "session-1",
            role: .user,
            content: "你好",
            timestamp: 1700000000000,
            status: .sent
        )
        XCTAssertEqual(message.id, "msg-1")
        XCTAssertEqual(message.sessionId, "session-1")
        XCTAssertEqual(message.role, .user)
        XCTAssertEqual(message.content, "你好")
        XCTAssertEqual(message.timestamp, 1700000000000)
        XCTAssertEqual(message.status, .sent)
        // 可选字段默认值
        XCTAssertEqual(message.metadataJson, "{}")
        XCTAssertNil(message.attachmentType)
        XCTAssertNil(message.attachmentData)
        XCTAssertNil(message.attachmentName)
        XCTAssertEqual(message.reaction, "")
        XCTAssertNil(message.replyToId)
    }

    /// 测试 Message 创建（含所有字段）
    func testMessageCreationWithAllFields() {
        let message = Message(
            id: "msg-2",
            sessionId: "session-2",
            role: .assistant,
            content: "回复内容",
            timestamp: 1700000001000,
            status: .received,
            metadataJson: "{\"key\": \"value\"}",
            attachmentType: .image,
            attachmentData: "base64data",
            attachmentName: "photo.png",
            reaction: "👍",
            replyToId: "msg-1"
        )
        XCTAssertEqual(message.id, "msg-2")
        XCTAssertEqual(message.role, .assistant)
        XCTAssertEqual(message.content, "回复内容")
        XCTAssertEqual(message.metadataJson, "{\"key\": \"value\"}")
        XCTAssertEqual(message.attachmentType, .image)
        XCTAssertEqual(message.attachmentData, "base64data")
        XCTAssertEqual(message.attachmentName, "photo.png")
        XCTAssertEqual(message.reaction, "👍")
        XCTAssertEqual(message.replyToId, "msg-1")
    }

    // MARK: - Message Codable 测试

    /// 测试 Message Codable 编解码
    func testMessageCodable() {
        let message = Message(
            id: "msg-enc",
            sessionId: "s-enc",
            role: .system,
            content: "系统提示内容",
            timestamp: 1700000002000,
            status: .sending,
            metadataJson: "{\"source\": \"test\"}",
            attachmentType: .file,
            attachmentData: "filedata",
            attachmentName: "doc.pdf"
        )

        let data = try? JSONEncoder().encode(message)
        XCTAssertNotNil(data, "Message 编码不应返回 nil")

        let decoded = try? JSONDecoder().decode(Message.self, from: data!)
        XCTAssertNotNil(decoded, "Message 解码不应返回 nil")
        XCTAssertEqual(decoded, message, "编码后解码的 Message 应与原始值相等")
    }

    /// 测试 Message Codable 编解码（可选字段为 nil）
    func testMessageCodableWithNilOptionals() {
        let message = Message(
            id: "msg-nils",
            sessionId: "s-nils",
            role: .tool,
            content: "工具结果",
            timestamp: 9999,
            status: .failed
        )

        let data = try? JSONEncoder().encode(message)
        XCTAssertNotNil(data)

        let decoded = try? JSONDecoder().decode(Message.self, from: data!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded, message)
        XCTAssertNil(decoded?.attachmentType)
        XCTAssertNil(decoded?.attachmentData)
        XCTAssertNil(decoded?.attachmentName)
        XCTAssertNil(decoded?.replyToId)
    }

    // MARK: - MessageStatus 测试

    /// 测试 MessageStatus 所有 case 的 displayName（rawValue）
    func testMessageStatusDisplayName() {
        let allStatuses: [MessageStatus] = [.sending, .sent, .received, .failed]
        for status in allStatuses {
            XCTAssertFalse(status.rawValue.isEmpty,
                           "MessageStatus 的 rawValue 不应为空")
        }
        XCTAssertEqual(MessageStatus.sending.rawValue, "Sending")
        XCTAssertEqual(MessageStatus.sent.rawValue, "Sent")
        XCTAssertEqual(MessageStatus.received.rawValue, "Received")
        XCTAssertEqual(MessageStatus.failed.rawValue, "Failed")
    }
}