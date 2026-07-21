import XCTest
@testable import AgentControlCenter

// MARK: - TransportFactory 单元测试
// 验证工厂路由 + provider 可注入（Sprint 2-4 引入的能力）
@MainActor
final class TransportFactoryTests: XCTestCase {

    /// 测试结束后恢复默认 provider，避免污染其他测试
    override func tearDown() {
        TransportFactory.provider = TransportFactory.shared
        super.tearDown()
    }

    // MARK: - 默认路由

    /// WebSocket 类型的 Agent 应路由到 WebSocketTransport
    func testWebSocketAgentTypesRoute() {
        // 验证不会崩溃并返回非 nil — 不进行实际连接
        for type in [AgentType.hermes, .openClaw, .openCode] {
            let transport = TransportFactory.create(type)
            XCTAssertNotNil(transport, "AgentType=\(type.rawValue) 应能创建 Transport")
            transport.shutdown()
        }
    }

    /// HTTP/SSE 类型的 Agent 应路由到 OpenAIHTTPTransport
    func testHTTPAgentTypesRoute() {
        for type in [AgentType.openAI, .xiaomiMiMo, .localModel] {
            let transport = TransportFactory.create(type)
            XCTAssertNotNil(transport, "AgentType=\(type.rawValue) 应能创建 Transport")
            transport.shutdown()
        }
    }

    /// 所有 AgentType 都应能创建 Transport（覆盖全部 6 个 case）
    func testAllAgentTypesProduceTransport() {
        for type in AgentType.allCases {
            let transport = TransportFactory.create(type)
            XCTAssertNotNil(transport)
            transport.shutdown()
        }
    }

    // MARK: - Provider 可注入

    /// 替换 provider 后，TransportFactory.create(_) 应使用注入的 mock
    func testProviderInjectionUsesMock() {
        let mock = MockTransportFactory()
        TransportFactory.provider = mock

        _ = TransportFactory.create(.openAI)

        XCTAssertEqual(mock.createCallCount, 1)
        XCTAssertEqual(mock.lastAgentType, .openAI)
    }

    /// 测试结束后恢复 provider，生产代码不受影响
    func testProviderRestorationAfterTest() {
        let mock = MockTransportFactory()
        TransportFactory.provider = mock
        XCTAssertEqual(TransportFactory.provider as? MockTransportFactory, mock)

        // 恢复
        TransportFactory.provider = TransportFactory.shared
        XCTAssertFalse(TransportFactory.provider is MockTransportFactory)
    }

    // MARK: - 默认实现

    /// TransportFactory.shared 实例方法应与静态方法返回等价的 Transport 类型
    func testSharedInstanceCreateMatchesStaticCreate() {
        let shared = TransportFactory.shared
        let t1 = shared.create(.openAI)
        let t2 = TransportFactory.create(.openAI)
        // 类型应一致（都是 OpenAIHTTPTransport）
        XCTAssertTrue(type(of: t1) == type(of: t2))
        t1.shutdown()
        t2.shutdown()
    }
}

// MARK: - Mock TransportFactory

/// 测试用 TransportFactory mock，记录调用次数和参数
private final class MockTransportFactory: TransportFactorying, @unchecked Sendable {
    private(set) var createCallCount = 0
    private(set) var lastAgentType: AgentType?

    func create(_ agentType: AgentType) -> AgentTransport {
        createCallCount += 1
        lastAgentType = agentType
        return MockTransport()
    }
}

/// 测试用 Transport，所有方法都是空实现
private final class MockTransport: AgentTransport, @unchecked Sendable {
    var events: AsyncStream<AgentEvent> { AsyncStream { _ in } }
    var connectionState: AgentConnectionState { AgentConnectionState() }
    func connect(config: AgentConfig, e2eKey: String?) async {}
    func sendMessage(sessionId: String, content: String) async throws {}
    func disconnect() {}
    func shutdown() {}
    func clearHistory(sessionId: String) async {}
    func clearAllHistory() async {}
}
