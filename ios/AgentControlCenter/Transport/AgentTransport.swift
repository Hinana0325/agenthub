import Foundation

// MARK: - AgentTransport Protocol
// 对应 Android AgentTransport interface
// 协议契约: protocol/schemas/event-schema.json + transport/

/// Agent 传输层协议。所有传输实现（HTTP/SSE、WebSocket、MCP）遵循此接口。
///
/// 事件流通过 AsyncStream 暴露，调用方用 `for await event in transport.events` 消费。
/// 连接状态通过 @Observable 属性暴露，便于 SwiftUI 直接绑定。
protocol AgentTransport: AnyObject, Sendable {
    /// 事件流
    var events: AsyncStream<AgentEvent> { get }

    /// 连接状态
    var connectionState: AgentConnectionState { get }

    /// 连接到 Agent
    func connect(config: AgentConfig, e2eKey: String?) async

    /// 发送消息
    func sendMessage(sessionId: String, content: String) async throws

    /// 断开连接
    func disconnect()

    /// 释放资源
    func shutdown()

    /// 清除某个会话的客户端侧历史
    func clearHistory(sessionId: String) async

    /// 清除所有客户端侧历史
    func clearAllHistory() async

    /// 运行时热更新 E2E 密钥，无需断开重连。
    ///
    /// 与 Android `AgentTransport.updateE2eKey(_:)` 对齐：
    /// - 仅更新 transport 内部缓存的 e2eKey 字段
    /// - 后续 sendMessage 加密 / handleMessage 解密立即使用新值
    /// - 传 nil 表示禁用 E2E 加密（与关闭开关等效）
    ///
    /// 默认空实现保证向后兼容：未实现此方法的 transport（如 mock）不会编译失败。
    /// 真实 transport（WebSocketTransport / OpenAIHTTPTransport）需各自实现。
    func updateE2eKey(_ key: String?)
}

// MARK: - Default Implementation

extension AgentTransport {
    func clearHistory(sessionId: String) async {}
    func clearAllHistory() async {}
    /// 默认空实现：未支持热更新密钥的 transport 忽略调用，
    /// 保持向后兼容（如 mock transport / 测试桩）。
    func updateE2eKey(_ key: String?) {}
}

// MARK: - Transport Factory
// 对应 Android TransportFactory

/// 传输层工厂协议。提取该协议便于在单元测试中注入 mock 传输，
/// 避免真实发起网络请求或建立 WebSocket 连接。
protocol TransportFactorying: Sendable {
    /// 根据 Agent 类型创建对应的 Transport 实例
    func create(_ agentType: AgentType) -> AgentTransport
}

/// 传输层工厂。根据 AgentType 创建对应的 Transport 实例。
///
/// 默认通过静态方法 `TransportFactory.create(_:)` 调用，内部转发到 `provider`，
/// 单元测试可在 `setUp()` 中替换 `TransportFactory.provider` 注入 mock 工厂。
struct TransportFactory: TransportFactorying, Sendable {

    /// 单例。`provider` 默认指向该实例。
    static let shared = TransportFactory()

    private init() {}

    /// 可注入的工厂提供者。生产环境使用 `TransportFactory.shared`，
    /// 测试中可在 `setUp()` 替换为 mock 工厂，并在 `tearDown()` 恢复。
    //
    // M-17 修复：原 `nonisolated(unsafe) static var provider` 在并发场景下无同步保护，
    // 多 actor 同时读写存在数据竞争。改为 `@MainActor` 隔离：生产代码（ChatView、
    // WorkflowEngine 等）均在 MainActor 上调用 TransportFactory.create，访问安全；
    // 测试 setUp/tearDown 通过 `await MainActor.run { ... }` 修改 provider。
    // 同步修改 TransportFactoryTests / WorkflowEngineTests 的 tearDown 用 MainActor.run。
    @MainActor static var provider: TransportFactorying = TransportFactory.shared

    /// 静态工厂方法（保持向后兼容的调用方式）。
    /// 内部转发到 `provider`，使测试可以通过替换 `provider` 注入 mock。
    // M-17 修复：create(_:) 也标 @MainActor，与 provider 隔离对齐
    @MainActor
    static func create(_ agentType: AgentType) -> AgentTransport {
        provider.create(agentType)
    }

    /// 实例方法（协议要求）
    func create(_ agentType: AgentType) -> AgentTransport {
        switch agentType {
        case .hermes, .openClaw, .openCode:
            return WebSocketTransport()
        case .openAI, .xiaomiMiMo, .localModel:
            return OpenAIHTTPTransport()  // 本地模型走 OpenAI 兼容 API
        }
    }
}
