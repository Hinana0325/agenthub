import Foundation

// MARK: - Agent Models
// 对应 protocol/schemas/agent-schema.json

/// Agent 类型枚举，决定 TransportFactory 路由到具体传输实现。
enum AgentType: String, Codable, CaseIterable {
    case hermes = "Hermes"
    case openCode = "OpenCode"
    case openClaw = "OpenClaw"
    case openAI = "OpenAI"
    case xiaomiMiMo = "XiaomiMiMo"
    case localModel = "LocalModel"
    case comfyUI = "ComfyUI"
    case openWebUI = "OpenWebUI"

    var displayName: String { rawValue }
}

/// Agent 通信协议类型
enum AgentProtocol: String, Codable {
    case webSocket = "WebSocket"
    case httpSSE = "HttpSSE"
    case mcp = "MCP"
    case local = "Local"
}

/// Agent 连接状态
enum AgentStatus: String, Codable {
    case online = "Online"
    case offline = "Offline"
    case connecting = "Connecting"
    case error = "Error"
}

/// Agent 能力声明
enum AgentCapability: String, Codable, CaseIterable {
    case chat = "CHAT"
    case task = "TASK"
    case workflow = "WORKFLOW"
    case mcp = "MCP"
    case filesystem = "FILESYSTEM"
    case terminal = "TERMINAL"
    case voice = "VOICE"
    case imageGen = "IMAGE_GEN"
    case codeExecution = "CODE_EXECUTION"
}

/// Agent 连接配置。apiKey 在持久化时由 Keychain 加密，使用 AKS: 前缀格式。
struct AgentConfig: Codable, Identifiable, Equatable, Sendable {
    var id: String = "default"
    var name: String = "Default Agent"
    var type: AgentType = .hermes
    var serverUrl: String = ""
    var apiKey: String = ""
    var model: String = ""
    var systemPrompt: String = ""
    var temperature: Float = 0.7
    var maxTokens: Int = 4096
    /// 通信协议类型（AgentProtocol.rawValue：WebSocket / HttpSSE / MCP / Local）
    var protocolType: AgentProtocol = .webSocket
}

/// Agent 运行时实例
struct Agent: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var name: String
    var endpoint: String = ""
    var status: AgentStatus = .offline
    var capabilities: [AgentCapability] = [.chat]
    var protocolType: AgentProtocol = .webSocket
    var config: AgentConfig? = nil

    enum CodingKeys: String, CodingKey {
        case id, name, endpoint, status, capabilities, config
        case protocolType = "protocol"
    }
}
