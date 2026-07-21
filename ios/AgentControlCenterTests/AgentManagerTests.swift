import XCTest
@testable import AgentControlCenter

// MARK: - AgentManager 单元测试
// 验证 Agent 注册/注销、能力索引、活跃 Agent、状态更新、查询过滤
@MainActor
final class AgentManagerTests: XCTestCase {

    // CI-fix: Swift 6 下 `XCTestCase.setUp()` 在父类中是 nonisolated 声明，
    // override 不能收紧 isolation，MainActor.assumeIsolated 闭包是 @Sendable
    // 不能捕获非 Sendable 的 self。把 manager 标 `nonisolated(unsafe)` 让
    // nonisolated 的 setUp/tearDown 能直接访问；改用 `setUp() async throws`
    // + `MainActor.run` 调用 MainActor 隔离的 `AgentManager()` 初始化器。
    nonisolated(unsafe) private var manager: AgentManager!

    override func setUp() async throws {
        try await super.setUp()
        manager = await MainActor.run { AgentManager() }
    }

    override func tearDown() async throws {
        manager = nil
        try await super.tearDown()
    }

    // MARK: - 注册

    func testRegisterAppendsAgent() {
        let agent = makeAgent(id: "a1", capabilities: [.chat])
        manager.register(agent)
        XCTAssertEqual(manager.agents.count, 1)
        XCTAssertEqual(manager.agents.first?.id, "a1")
    }

    func testRegisterExistingIdUpdates() {
        let original = makeAgent(id: "a1", name: "原名称", capabilities: [.chat])
        manager.register(original)

        let updated = makeAgent(id: "a1", name: "新名称", capabilities: [.chat, .task])
        manager.register(updated)

        XCTAssertEqual(manager.agents.count, 1)
        XCTAssertEqual(manager.agents.first?.name, "新名称")
        XCTAssertEqual(manager.agents.first?.capabilities, [.chat, .task])
    }

    // MARK: - 能力索引

    func testCapabilityIndexBuildsOnRegister() {
        manager.register(makeAgent(id: "a1", capabilities: [.chat, .task]))
        manager.register(makeAgent(id: "a2", capabilities: [.chat, .mcp]))

        let chatAgents = manager.getAgentsByCapability(.chat)
        let taskAgents = manager.getAgentsByCapability(.task)
        let mcpAgents = manager.getAgentsByCapability(.mcp)
        let workflowAgents = manager.getAgentsByCapability(.workflow)

        XCTAssertEqual(chatAgents.count, 2)
        XCTAssertEqual(taskAgents.count, 1)
        XCTAssertEqual(mcpAgents.count, 1)
        XCTAssertEqual(workflowAgents.count, 0)
    }

    /// 注销 Agent 时应同步从能力索引中清除
    func testCapabilityIndexRemovedOnUnregister() {
        manager.register(makeAgent(id: "a1", capabilities: [.chat, .task]))
        XCTAssertEqual(manager.getAgentsByCapability(.chat).count, 1)

        manager.unregister(agentId: "a1")
        XCTAssertEqual(manager.getAgentsByCapability(.chat).count, 0)
        XCTAssertEqual(manager.getAgentsByCapability(.task).count, 0)
    }

    /// 多个 Agent 共享同一能力时，注销其中一个不应影响其他 Agent 的索引
    func testCapabilityIndexPartialRemoval() {
        manager.register(makeAgent(id: "a1", capabilities: [.chat]))
        manager.register(makeAgent(id: "a2", capabilities: [.chat]))

        manager.unregister(agentId: "a1")
        let remaining = manager.getAgentsByCapability(.chat)
        XCTAssertEqual(remaining.count, 1)
        XCTAssertEqual(remaining.first?.id, "a2")
    }

    // MARK: - 注销

    func testUnregisterRemovesFromList() {
        manager.register(makeAgent(id: "a1"))
        manager.register(makeAgent(id: "a2"))

        manager.unregister(agentId: "a1")
        XCTAssertEqual(manager.agents.count, 1)
        XCTAssertEqual(manager.agents.first?.id, "a2")
    }

    func testUnregisterActiveAgentClearsActive() {
        let agent = makeAgent(id: "a1")
        manager.register(agent)
        manager.setActive(agentId: "a1")
        XCTAssertNotNil(manager.activeAgent)

        manager.unregister(agentId: "a1")
        XCTAssertNil(manager.activeAgent)
    }

    func testUnregisterNonExistentNoOp() {
        manager.register(makeAgent(id: "a1"))
        manager.unregister(agentId: "non-existent")
        XCTAssertEqual(manager.agents.count, 1)
    }

    // MARK: - 活跃 Agent

    func testSetActive() {
        manager.register(makeAgent(id: "a1"))
        manager.register(makeAgent(id: "a2"))

        manager.setActive(agentId: "a2")
        XCTAssertEqual(manager.activeAgent?.id, "a2")
    }

    func testSetActiveNonExistentReturnsNil() {
        manager.register(makeAgent(id: "a1"))
        manager.setActive(agentId: "non-existent")
        XCTAssertNil(manager.activeAgent)
    }

    // MARK: - 状态更新

    func testUpdateStatus() {
        manager.register(makeAgent(id: "a1", status: .offline))
        manager.updateStatus(agentId: "a1", status: .online)

        XCTAssertEqual(manager.agents.first?.status, .online)
    }

    func testUpdateStatusNonExistentNoOp() {
        manager.register(makeAgent(id: "a1"))
        manager.updateStatus(agentId: "non-existent", status: .online)
        XCTAssertEqual(manager.agents.first?.status, .offline)
    }

    // MARK: - 查询

    func testGetAgent() {
        manager.register(makeAgent(id: "a1", name: "Agent One"))

        let found = manager.getAgent("a1")
        XCTAssertEqual(found?.name, "Agent One")

        XCTAssertNil(manager.getAgent("non-existent"))
    }

    /// onlineAgents 应只返回 status == .online 的 Agent
    func testOnlineAgentsFilter() {
        manager.register(makeAgent(id: "a1", status: .online))
        manager.register(makeAgent(id: "a2", status: .offline))
        manager.register(makeAgent(id: "a3", status: .online))

        let online = manager.onlineAgents
        XCTAssertEqual(online.count, 2)
        XCTAssertTrue(online.allSatisfy { $0.status == .online })
    }

    // MARK: - Helpers

    private func makeAgent(
        id: String,
        name: String = "Test Agent",
        status: AgentStatus = .offline,
        capabilities: [AgentCapability] = [.chat]
    ) -> Agent {
        Agent(
            id: id,
            name: name,
            endpoint: "",
            status: status,
            capabilities: capabilities
        )
    }
}
