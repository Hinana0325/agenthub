import XCTest
@testable import AgentControlCenter

// MARK: - TransportFactory 单元测试
// 验证工厂路由 + provider 可注入（Sprint 2-4 引入的能力）
@MainActor
final class TransportFactoryTests: XCTestCase {

    /// 测试结束后恢复默认 provider，避免污染其他测试
    // M-17 修复：provider 改为 @MainActor 隔离，tearDown 需改为 async throws 并通过
    // MainActor.run 访问；super.tearDown() 默认 no-op 跳过避免发送 self 跨 actor。
    override func tearDown() async throws {
        await MainActor.run {
            TransportFactory.provider = TransportFactory.shared
        }
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
    /// （含 OpenWebUI，与 OpenAI/XiaomiMiMo/LocalModel 共用 OpenAI 兼容 HTTP+SSE 传输）
    func testHTTPAgentTypesRoute() {
        for type in [AgentType.openAI, .xiaomiMiMo, .localModel, .openWebUI] {
            let transport = TransportFactory.create(type)
            XCTAssertNotNil(transport, "AgentType=\(type.rawValue) 应能创建 Transport")
            XCTAssertTrue(transport is OpenAIHTTPTransport,
                          "AgentType=\(type.rawValue) 应路由到 OpenAIHTTPTransport")
            transport.shutdown()
        }
    }

    /// ComfyUI 应路由到 ComfyUITransport（HTTP 工作流提交 + 轮询，与 SSE 聊天范式不同）
    func testComfyUIRoutesToComfyUITransport() {
        let transport = TransportFactory.create(.comfyUI)
        XCTAssertNotNil(transport, "AgentType=comfyUI 应能创建 Transport")
        XCTAssertTrue(transport is ComfyUITransport,
                      "AgentType=comfyUI 应路由到 ComfyUITransport")
        transport.shutdown()
    }

    /// 验证 WebSocket / HTTP / ComfyUI 三类路由互不相同
    func testTransportRoutingIsDistinct() {
        let wsTypes: [AgentType] = [.hermes, .openClaw, .openCode]
        let httpTypes: [AgentType] = [.openAI, .xiaomiMiMo, .localModel, .openWebUI]

        for type in wsTypes {
            let transport = TransportFactory.create(type)
            XCTAssertTrue(transport is WebSocketTransport,
                          "AgentType=\(type.rawValue) 应路由到 WebSocketTransport")
            transport.shutdown()
        }
        for type in httpTypes {
            let transport = TransportFactory.create(type)
            XCTAssertTrue(transport is OpenAIHTTPTransport,
                          "AgentType=\(type.rawValue) 应路由到 OpenAIHTTPTransport")
            transport.shutdown()
        }
        let comfyTransport = TransportFactory.create(.comfyUI)
        XCTAssertTrue(comfyTransport is ComfyUITransport,
                      "AgentType=comfyUI 应路由到 ComfyUITransport")
        comfyTransport.shutdown()
    }

    /// 所有 AgentType 都应能创建 Transport（覆盖全部 8 个 case）
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
// CI-fix: 添加 Equatable 协议（基于引用相等），让 XCTAssertEqual 比较可用。
private final class MockTransportFactory: TransportFactorying, @unchecked Sendable, Equatable {
    private(set) var createCallCount = 0
    private(set) var lastAgentType: AgentType?

    func create(_ agentType: AgentType) -> AgentTransport {
        createCallCount += 1
        lastAgentType = agentType
        return MockTransport()
    }

    static func == (lhs: MockTransportFactory, rhs: MockTransportFactory) -> Bool {
        lhs === rhs
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
