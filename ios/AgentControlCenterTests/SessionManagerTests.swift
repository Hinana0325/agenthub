import XCTest
@testable import AgentControlCenter

// MARK: - SessionManager 单元测试
// 验证会话生命周期、消息计数增减、置顶、排序逻辑
@MainActor
final class SessionManagerTests: XCTestCase {

    // CI-fix: Swift 6 下 `XCTestCase.setUp()` 在父类中是 nonisolated 声明，
    // override 不能收紧 isolation，MainActor.assumeIsolated 闭包是 @Sendable
    // 不能捕获非 Sendable 的 self。把 manager 标 `nonisolated(unsafe)` 让
    // nonisolated 的 setUp/tearDown 能直接访问；改用 `setUp() async throws`
    // + `MainActor.run` 调用 MainActor 隔离的 `SessionManager()` 初始化器。
    nonisolated(unsafe) private var manager: SessionManager!

    override func setUp() async throws {
        // CI-fix: 不调用 `try await super.setUp()` — XCTest 的 async 版本在 Swift 6 下
        // 是 @MainActor 隔离，从 nonisolated 子类 override 调用会发送非 Sendable 的 self
        // 跨 actor 边界。父类默认实现是 no-op，跳过即可。
        manager = await MainActor.run { SessionManager() }
    }

    override func tearDown() async throws {
        manager = nil
    }

    // MARK: - 创建会话

    func testCreateSessionInsertsAtHead() {
        let s1 = manager.createSession(title: "对话1")
        let s2 = manager.createSession(title: "对话2")

        XCTAssertEqual(manager.sessions.count, 2)
        // 新会话插入到列表头部
        XCTAssertEqual(manager.sessions.first?.id, s2.id)
        XCTAssertEqual(manager.sessions.last?.id, s1.id)
    }

    func testCreateSessionDefaultTitle() {
        let session = manager.createSession()
        XCTAssertEqual(session.title, "新对话")
    }

    func testCreateSessionTimestamps() {
        let before = Int64(Date().timeIntervalSince1970 * 1000)
        let session = manager.createSession()
        let after = Int64(Date().timeIntervalSince1970 * 1000)

        XCTAssertGreaterThanOrEqual(session.createdAt, before)
        XCTAssertLessThanOrEqual(session.createdAt, after)
        XCTAssertEqual(session.createdAt, session.updatedAt)
    }

    // MARK: - 删除会话

    func testDeleteSessionRemovesFromList() {
        let s1 = manager.createSession(title: "对话1")
        let s2 = manager.createSession(title: "对话2")

        manager.deleteSession(s1.id)

        XCTAssertEqual(manager.sessions.count, 1)
        XCTAssertEqual(manager.sessions.first?.id, s2.id)
    }

    func testDeleteActiveSessionClearsActive() {
        let session = manager.createSession()
        manager.setActive(sessionId: session.id)
        XCTAssertNotNil(manager.activeSession)

        manager.deleteSession(session.id)
        XCTAssertNil(manager.activeSession)
    }

    func testDeleteNonExistentSessionNoOp() {
        manager.createSession()
        manager.deleteSession("non-existent-id")
        XCTAssertEqual(manager.sessions.count, 1)
    }

    // MARK: - 设置活跃会话

    func testSetActiveById() {
        let s1 = manager.createSession(title: "对话1")
        let s2 = manager.createSession(title: "对话2")

        manager.setActive(sessionId: s1.id)
        XCTAssertEqual(manager.activeSession?.id, s1.id)

        manager.setActive(sessionId: s2.id)
        XCTAssertEqual(manager.activeSession?.id, s2.id)
    }

    func testSetActiveNonExistentReturnsNil() {
        manager.createSession()
        manager.setActive(sessionId: "non-existent-id")
        XCTAssertNil(manager.activeSession)
    }

    // MARK: - 标题更新

    func testUpdateTitle() {
        let session = manager.createSession(title: "原标题")
        let originalUpdatedAt = session.updatedAt

        // 等待 1ms 确保 updatedAt 会变化
        Thread.sleep(forTimeInterval: 0.005)

        manager.updateTitle(session.id, title: "新标题")

        let updated = manager.sessions.first { $0.id == session.id }
        XCTAssertEqual(updated?.title, "新标题")
        XCTAssertGreaterThan(updated?.updatedAt ?? 0, originalUpdatedAt)
    }

    func testUpdateTitleNonExistentNoOp() {
        manager.createSession()
        manager.updateTitle("non-existent", title: "新标题")
        XCTAssertEqual(manager.sessions.first?.title, "新对话")
    }

    // MARK: - 消息计数

    func testIncrementMessageCount() {
        let session = manager.createSession()
        XCTAssertEqual(session.messageCount, 0)

        manager.incrementMessageCount(session.id)

        let updated = manager.sessions.first { $0.id == session.id }
        XCTAssertEqual(updated?.messageCount, 1)
    }

    func testIncrementMessageCountMultiple() {
        let session = manager.createSession()
        for _ in 0..<5 {
            manager.incrementMessageCount(session.id)
        }
        let updated = manager.sessions.first { $0.id == session.id }
        XCTAssertEqual(updated?.messageCount, 5)
    }

    func testDecrementMessageCount() {
        let session = manager.createSession()
        manager.incrementMessageCount(session.id)
        manager.incrementMessageCount(session.id)
        manager.incrementMessageCount(session.id)

        manager.decrementMessageCount(session.id)

        let updated = manager.sessions.first { $0.id == session.id }
        XCTAssertEqual(updated?.messageCount, 2)
    }

    /// 消息计数为 0 时再递减应被 floor 到 0，不应变成负数
    /// 该用例对应 P1-8 修复：删除消息时 messageCount 同步递减
    func testDecrementMessageCountFloorAtZero() {
        let session = manager.createSession()
        // messageCount 初始为 0
        manager.decrementMessageCount(session.id)

        let updated = manager.sessions.first { $0.id == session.id }
        XCTAssertEqual(updated?.messageCount, 0, "递减后不应为负数")
    }

    func testIncrementNonExistentSessionNoOp() {
        manager.createSession()
        manager.incrementMessageCount("non-existent")
        XCTAssertEqual(manager.sessions.first?.messageCount, 0)
    }

    // MARK: - 置顶

    func testTogglePin() {
        let session = manager.createSession()
        XCTAssertFalse(session.isPinned)

        manager.togglePin(session.id)
        let pinned = manager.sessions.first { $0.id == session.id }
        XCTAssertTrue(pinned?.isPinned ?? false)

        manager.togglePin(session.id)
        let unpinned = manager.sessions.first { $0.id == session.id }
        XCTAssertFalse(unpinned?.isPinned ?? true)
    }

    // MARK: - 排序

    /// 置顶的会话应排在非置顶之前
    func testSortedSessionsPinnedFirst() {
        let s1 = manager.createSession(title: "s1")
        let s2 = manager.createSession(title: "s2")
        let s3 = manager.createSession(title: "s3")

        // s2 置顶
        manager.togglePin(s2.id)

        let sorted = manager.sortedSessions
        XCTAssertEqual(sorted.first?.id, s2.id, "置顶会话应排第一")
        // 其余两个非置顶按 updatedAt 降序（创建时 s3 最晚）
        XCTAssertEqual(sorted[1].id, s3.id)
        XCTAssertEqual(sorted[2].id, s1.id)
    }

    /// 非置顶会话按 updatedAt 降序
    func testSortedSessionsByUpdatedAtDesc() {
        let s1 = manager.createSession(title: "s1")
        let s2 = manager.createSession(title: "s2")
        let s3 = manager.createSession(title: "s3")

        // 修改 s1 的 updatedAt 为最新
        Thread.sleep(forTimeInterval: 0.01)
        manager.updateTitle(s1.id, title: "s1-updated")

        let sorted = manager.sortedSessions
        // s1 updatedAt 最新，应排第一
        XCTAssertEqual(sorted.first?.id, s1.id)
        // s3 在 s2 之后创建
        XCTAssertEqual(sorted[1].id, s3.id)
        XCTAssertEqual(sorted[2].id, s2.id)
        _ = s1; _ = s2; _ = s3
    }

    /// 空列表排序应返回空
    func testSortedSessionsEmpty() {
        XCTAssertEqual(manager.sortedSessions.count, 0)
    }
}
