import Foundation

// MARK: - Task Model
// 对应 protocol/schemas/task-schema.json

/// 任务类型
enum TaskType: String, Codable {
    case chat = "CHAT"
    case code = "CODE"
    case workflow = "WORKFLOW"
    case toolCall = "TOOL_CALL"
    case fileOperation = "FILE_OPERATION"
}

/// 任务状态
enum TaskStatus: String, Codable {
    case pending = "Pending"
    case running = "Running"
    case completed = "Completed"
    case failed = "Failed"
    case cancelled = "Cancelled"

    var isTerminal: Bool {
        self == .completed || self == .failed || self == .cancelled
    }
}

/// 异步任务。与 Chat 的区别：Task 是异步的、可追踪的、可调度的执行单元。
struct AgentTask: Codable, Identifiable, Equatable {
    var id: String
    var agentId: String
    var sessionId: String? = nil
    var type: TaskType
    var input: String
    var status: TaskStatus = .pending
    var result: String? = nil
    var createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var completedAt: Int64? = nil
    var error: String? = nil
}
