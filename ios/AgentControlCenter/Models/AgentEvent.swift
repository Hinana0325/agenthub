import Foundation

// MARK: - Event Model
// 对应 protocol/schemas/event-schema.json

/// Agent 实时事件。判别联合，通过 type 字段区分。
enum AgentEvent {
    case connected(serverUrl: String, agentType: AgentType)
    case disconnected(reason: String = "")
    case messageReceived(content: String, isDelta: Bool = false)
    case error(message: String)
    case reconnecting
    case streamComplete
}

/// Agent 连接状态
struct AgentConnectionState: Equatable {
    var isConnected: Bool = false
    var serverUrl: String = ""
    var agentType: AgentType = .hermes
    var latency: Int = 0
}

// MARK: - Error Codes
// 对应 protocol/schemas/error-codes.json

/// 统一错误码注册表。37 个错误码，10 个类别。
enum AppErrorCode: Int, Codable {
    // Transport (1xxx)
    case transportConnectFailed = 1001
    case transportAuthFailed = 1002
    case transportTimeout = 1003
    case transportDisconnected = 1004
    case transportReconnectFailed = 1005

    // Protocol (2xxx)
    case protocolInvalidMessage = 2001
    case protocolUnknownType = 2002
    case protocolParseError = 2003
    case protocolVersionMismatch = 2004

    // Agent (3xxx)
    case agentNotFound = 3001
    case agentOffline = 3002
    case agentNoCapability = 3003
    case agentConfigMissing = 3004
    case agentResponseEmpty = 3005

    // Session (4xxx)
    case sessionNotFound = 4001
    case sessionCreateFailed = 4002

    // Task (5xxx)
    case taskNotFound = 5001
    case taskAlreadyRunning = 5002
    case taskCancelled = 5003
    case taskTimeout = 5004

    // Workflow (6xxx)
    case workflowInvalidDag = 6001
    case workflowNodeFailed = 6002
    case workflowCycleDetected = 6003
    case workflowTimeout = 6004

    // Plugin (7xxx)
    case pluginNotFound = 7001
    case pluginDisabled = 7002
    case pluginExecutionFailed = 7003

    // MCP (8xxx)
    case mcpServerUnreachable = 8001
    case mcpToolNotFound = 8002
    case mcpToolExecutionError = 8003
    case mcpInitializationFailed = 8004

    // File (9xxx)
    case fileTooLarge = 9001
    case fileNotFound = 9002
    case fileTransferFailed = 9003

    // Crypto (10xxx)
    case cryptoDecryptFailed = 10001
    case cryptoKeystoreUnavailable = 10002
    case cryptoE2eKeyMismatch = 10003

    var category: String {
        switch rawValue {
        case 1000...1999: return "Transport"
        case 2000...2999: return "Protocol"
        case 3000...3999: return "Agent"
        case 4000...4999: return "Session"
        case 5000...5999: return "Task"
        case 6000...6999: return "Workflow"
        case 7000...7999: return "Plugin"
        case 8000...8999: return "MCP"
        case 9000...9999: return "File"
        case 10000...10999: return "Crypto"
        default: return "Unknown"
        }
    }
}

/// 应用错误，携带错误码和描述
struct AppError: Error, LocalizedError {
    let code: AppErrorCode
    let message: String

    var errorDescription: String? { message }
}
