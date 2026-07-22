import XCTest
@testable import AgentControlCenter

// MARK: - Agent 模型单元测试
final class AgentModelTests: XCTestCase {

    // MARK: - AgentType 测试

    /// 测试 AgentType.allCases 数量为 8
    func testAgentTypeAllCasesCount() {
        XCTAssertEqual(AgentType.allCases.count, 8, "AgentType 应包含 8 个枚举值")
    }

    /// 测试 AgentType 每个枚举都有 displayName
    func testAgentTypeDisplayName() {
        for agentType in AgentType.allCases {
            XCTAssertFalse(agentType.displayName.isEmpty,
                           "AgentType.\(agentType.rawValue) 的 displayName 不应为空")
            // displayName 应等于 rawValue
            XCTAssertEqual(agentType.displayName, agentType.rawValue,
                           "AgentType.\(agentType.rawValue) 的 displayName 应与 rawValue 一致")
        }
    }

    /// 测试 AgentType 的 rawValue 正确性
    func testAgentTypeRawValues() {
        XCTAssertEqual(AgentType.hermes.rawValue, "Hermes")
        XCTAssertEqual(AgentType.openCode.rawValue, "OpenCode")
        XCTAssertEqual(AgentType.openClaw.rawValue, "OpenClaw")
        XCTAssertEqual(AgentType.openAI.rawValue, "OpenAI")
        XCTAssertEqual(AgentType.xiaomiMiMo.rawValue, "XiaomiMiMo")
        XCTAssertEqual(AgentType.localModel.rawValue, "LocalModel")
        XCTAssertEqual(AgentType.comfyUI.rawValue, "ComfyUI")
        XCTAssertEqual(AgentType.openWebUI.rawValue, "OpenWebUI")
    }

    // MARK: - AgentConfig 测试

    /// 测试 AgentConfig 默认值
    func testAgentConfigDefaultValues() {
        let config = AgentConfig()
        XCTAssertEqual(config.id, "default")
        XCTAssertEqual(config.name, "Default Agent")
        XCTAssertEqual(config.type, .hermes)
        XCTAssertEqual(config.serverUrl, "")
        XCTAssertEqual(config.apiKey, "")
        XCTAssertEqual(config.model, "")
        XCTAssertEqual(config.systemPrompt, "")
        XCTAssertEqual(config.temperature, 0.7)
        XCTAssertEqual(config.maxTokens, 4096)
    }

    /// 测试 AgentConfig 自定义创建
    func testAgentConfigCustomInit() {
        let config = AgentConfig(
            id: "config-1",
            name: "测试 Agent",
            type: .openAI,
            serverUrl: "https://api.openai.com",
            apiKey: "sk-test",
            model: "gpt-4",
            systemPrompt: "你是一个助手",
            temperature: 0.5,
            maxTokens: 8192
        )
        XCTAssertEqual(config.id, "config-1")
        XCTAssertEqual(config.name, "测试 Agent")
        XCTAssertEqual(config.type, .openAI)
        XCTAssertEqual(config.serverUrl, "https://api.openai.com")
        XCTAssertEqual(config.apiKey, "sk-test")
        XCTAssertEqual(config.model, "gpt-4")
        XCTAssertEqual(config.systemPrompt, "你是一个助手")
        XCTAssertEqual(config.temperature, 0.5)
        XCTAssertEqual(config.maxTokens, 8192)
    }

    // MARK: - Agent 测试

    /// 测试 Agent 创建和基本属性
    func testAgentCreation() {
        let agent = Agent(
            id: "agent-1",
            name: "Hermes Agent",
            endpoint: "ws://localhost:8080",
            status: .online,
            capabilities: [.chat, .mcp],
            protocolType: .webSocket,
            config: nil
        )
        XCTAssertEqual(agent.id, "agent-1")
        XCTAssertEqual(agent.name, "Hermes Agent")
        XCTAssertEqual(agent.endpoint, "ws://localhost:8080")
        XCTAssertEqual(agent.status, .online)
        XCTAssertEqual(agent.capabilities, [.chat, .mcp])
        XCTAssertEqual(agent.protocolType, .webSocket)
        XCTAssertNil(agent.config)
    }

    /// 测试 Agent 默认值
    func testAgentDefaultValues() {
        let agent = Agent(id: "a1", name: "Test")
        XCTAssertEqual(agent.endpoint, "")
        XCTAssertEqual(agent.status, .offline)
        XCTAssertEqual(agent.capabilities, [.chat])
        XCTAssertEqual(agent.protocolType, .webSocket)
        XCTAssertNil(agent.config)
    }

    // MARK: - AgentCapability 测试

    /// 测试 AgentCapability.allCases 数量为 9
    func testAgentCapabilityAllCasesCount() {
        XCTAssertEqual(AgentCapability.allCases.count, 9,
                       "AgentCapability 应包含 9 个枚举值")
    }

    /// 测试 AgentCapability 所有 rawValue
    func testAgentCapabilityRawValues() {
        XCTAssertEqual(AgentCapability.chat.rawValue, "CHAT")
        XCTAssertEqual(AgentCapability.task.rawValue, "TASK")
        XCTAssertEqual(AgentCapability.workflow.rawValue, "WORKFLOW")
        XCTAssertEqual(AgentCapability.mcp.rawValue, "MCP")
        XCTAssertEqual(AgentCapability.filesystem.rawValue, "FILESYSTEM")
        XCTAssertEqual(AgentCapability.terminal.rawValue, "TERMINAL")
        XCTAssertEqual(AgentCapability.voice.rawValue, "VOICE")
        XCTAssertEqual(AgentCapability.imageGen.rawValue, "IMAGE_GEN")
        XCTAssertEqual(AgentCapability.codeExecution.rawValue, "CODE_EXECUTION")
    }

    // MARK: - AgentStatus 测试

    /// 测试 AgentStatus 所有 case 都有 rawValue（作为 displayName 使用）
    func testAgentStatusDisplayName() {
        // AgentStatus 的 rawValue 即为 displayName（模型中未定义 displayName 属性，用 rawValue 代替）
        let allStatuses: [AgentStatus] = [.online, .offline, .connecting, .error]
        for status in allStatuses {
            XCTAssertFalse(status.rawValue.isEmpty,
                           "AgentStatus.\(status.rawValue) 的 rawValue 不应为空")
        }
        XCTAssertEqual(AgentStatus.online.rawValue, "Online")
        XCTAssertEqual(AgentStatus.offline.rawValue, "Offline")
        XCTAssertEqual(AgentStatus.connecting.rawValue, "Connecting")
        XCTAssertEqual(AgentStatus.error.rawValue, "Error")
    }

    // MARK: - Agent Codable 测试

    /// 测试 AgentConfig Codable 编解码
    func testAgentConfigCodable() {
        let config = AgentConfig(
            id: "enc-1",
            name: "编码测试",
            type: .openClaw,
            serverUrl: "https://example.com",
            apiKey: "key-123",
            model: "claude-3",
            systemPrompt: "系统提示",
            temperature: 0.3,
            maxTokens: 2048
        )

        // 编码
        let data = try? JSONEncoder().encode(config)
        XCTAssertNotNil(data, "AgentConfig 编码不应返回 nil")

        // 解码
        let decoded = try? JSONDecoder().decode(AgentConfig.self, from: data!)
        XCTAssertNotNil(decoded, "AgentConfig 解码不应返回 nil")
        XCTAssertEqual(decoded, config, "编码后解码的 AgentConfig 应与原始值相等")
    }

    /// 测试 Agent Codable 编解码（包含 config）
    func testAgentCodable() {
        let config = AgentConfig(
            id: "cfg-1",
            name: "Agent 配置",
            type: .hermes,
            serverUrl: "ws://localhost:9090"
        )
        let agent = Agent(
            id: "a-enc",
            name: "编码Agent",
            endpoint: "ws://test",
            status: .online,
            capabilities: [.chat, .task, .mcp],
            protocolType: .webSocket,
            config: config
        )

        let data = try? JSONEncoder().encode(agent)
        XCTAssertNotNil(data)

        let decoded = try? JSONDecoder().decode(Agent.self, from: data!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded, agent, "编码后解码的 Agent 应与原始值相等")
        XCTAssertEqual(decoded?.config, config)
    }
}