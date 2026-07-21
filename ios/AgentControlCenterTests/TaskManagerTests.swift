import XCTest
@testable import AgentControlCenter

// MARK: - TaskManager 单元测试
// 验证任务提交、状态更新、终态时间戳、查询过滤、取消
@MainActor
final class TaskManagerTests: XCTestCase {

    private var manager: TaskManager!

    override func setUp() {
        super.setUp()
        manager = TaskManager()
    }

    override func tearDown() {
        manager = nil
        super.tearDown()
    }

    // MARK: - 提交任务

    func testSubmitTaskAppends() {
        let task = manager.submitTask(agentId: "agent-1", type: .chat, input: "hello")

        XCTAssertEqual(manager.tasks.count, 1)
        XCTAssertEqual(manager.tasks.first?.id, task.id)
        XCTAssertEqual(task.agentId, "agent-1")
        XCTAssertEqual(task.type, .chat)
        XCTAssertEqual(task.input, "hello")
        XCTAssertEqual(task.status, .pending)
        XCTAssertNil(task.sessionId)
        XCTAssertNil(task.result)
        XCTAssertNil(task.error)
        XCTAssertNil(task.completedAt)
    }

    func testSubmitTaskWithSessionId() {
        let task = manager.submitTask(
            agentId: "agent-1",
            type: .workflow,
            input: "do something",
            sessionId: "session-123"
        )
        XCTAssertEqual(task.sessionId, "session-123")
    }

    func testSubmitTaskGeneratesUniqueId() {
        let t1 = manager.submitTask(agentId: "a", type: .chat, input: "x")
        let t2 = manager.submitTask(agentId: "a", type: .chat, input: "x")
        XCTAssertNotEqual(t1.id, t2.id, "每次提交应生成唯一 id")
    }

    // MARK: - 状态更新

    func testUpdateStatusToRunning() {
        let task = manager.submitTask(agentId: "a", type: .chat, input: "x")
        manager.updateStatus(taskId: task.id, status: .running)

        let updated = manager.tasks.first { $0.id == task.id }
        XCTAssertEqual(updated?.status, .running)
        XCTAssertNil(updated?.completedAt, ".running 非终态，不应记录完成时间")
    }

    func testUpdateStatusToCompletedSetsCompletedAt() {
        let task = manager.submitTask(agentId: "a", type: .chat, input: "x")
        let before = Int64(Date().timeIntervalSince1970 * 1000)

        manager.updateStatus(taskId: task.id, status: .completed, result: "done")

        let after = Int64(Date().timeIntervalSince1970 * 1000)
        let updated = manager.tasks.first { $0.id == task.id }
        XCTAssertEqual(updated?.status, .completed)
        XCTAssertEqual(updated?.result, "done")
        XCTAssertNotNil(updated?.completedAt)
        XCTAssertGreaterThanOrEqual(updated?.completedAt ?? 0, before)
        XCTAssertLessThanOrEqual(updated?.completedAt ?? Int64.max, after)
    }

    func testUpdateStatusToFailedSetsCompletedAtAndError() {
        let task = manager.submitTask(agentId: "a", type: .chat, input: "x")
        manager.updateStatus(taskId: task.id, status: .failed, error: "boom")

        let updated = manager.tasks.first { $0.id == task.id }
        XCTAssertEqual(updated?.status, .failed)
        XCTAssertEqual(updated?.error, "boom")
        XCTAssertNotNil(updated?.completedAt)
    }

    func testUpdateStatusCancelledSetsCompletedAt() {
        let task = manager.submitTask(agentId: "a", type: .chat, input: "x")
        manager.updateStatus(taskId: task.id, status: .cancelled)

        let updated = manager.tasks.first { $0.id == task.id }
        XCTAssertEqual(updated?.status, .cancelled)
        XCTAssertNotNil(updated?.completedAt)
    }

    /// 更新 result 和 error 时只在提供新值时覆盖，保留已有值
    func testUpdateStatusPreservesExistingResultAndError() {
        let task = manager.submitTask(agentId: "a", type: .chat, input: "x")
        manager.updateStatus(taskId: task.id, status: .running, result: "partial")

        // 再次更新状态但不传 result — 应保留 "partial"
        manager.updateStatus(taskId: task.id, status: .completed)

        let updated = manager.tasks.first { $0.id == task.id }
        XCTAssertEqual(updated?.result, "partial", "未传 result 应保留原值")
    }

    func testUpdateStatusNonExistentNoOp() {
        manager.submitTask(agentId: "a", type: .chat, input: "x")
        manager.updateStatus(taskId: "non-existent", status: .completed)
        XCTAssertEqual(manager.tasks.first?.status, .pending)
    }

    // MARK: - 查询

    func testGetTasksForAgent() {
        manager.submitTask(agentId: "agent-a", type: .chat, input: "1")
        manager.submitTask(agentId: "agent-b", type: .chat, input: "2")
        manager.submitTask(agentId: "agent-a", type: .code, input: "3")

        let tasksA = manager.getTasksForAgent("agent-a")
        XCTAssertEqual(tasksA.count, 2)
        XCTAssertTrue(tasksA.allSatisfy { $0.agentId == "agent-a" })
    }

    func testGetTasksForAgentEmpty() {
        manager.submitTask(agentId: "agent-a", type: .chat, input: "1")
        let tasks = manager.getTasksForAgent("non-existent")
        XCTAssertEqual(tasks.count, 0)
    }

    /// activeTasks 应仅包含 pending / running 状态的任务
    func testActiveTasksFiltersTerminal() {
        let t1 = manager.submitTask(agentId: "a", type: .chat, input: "1")
        let t2 = manager.submitTask(agentId: "a", type: .chat, input: "2")
        let t3 = manager.submitTask(agentId: "a", type: .chat, input: "3")
        _ = manager.submitTask(agentId: "a", type: .chat, input: "4")

        manager.updateStatus(taskId: t1.id, status: .running)
        manager.updateStatus(taskId: t2.id, status: .completed)
        manager.updateStatus(taskId: t3.id, status: .cancelled)

        let active = manager.activeTasks
        // t2 / t3 已终态，应被过滤；剩 t1(running) 和 t4(pending)
        XCTAssertEqual(active.count, 2)
        XCTAssertTrue(active.allSatisfy { $0.status == .pending || $0.status == .running })
    }

    // MARK: - 取消任务

    func testCancelTask() {
        let task = manager.submitTask(agentId: "a", type: .chat, input: "x")
        manager.cancelTask(task.id)

        let updated = manager.tasks.first { $0.id == task.id }
        XCTAssertEqual(updated?.status, .cancelled)
        XCTAssertNotNil(updated?.completedAt)
    }

    // MARK: - 删除任务

    func testDeleteTask() {
        let t1 = manager.submitTask(agentId: "a", type: .chat, input: "1")
        _ = manager.submitTask(agentId: "a", type: .chat, input: "2")

        manager.deleteTask(t1.id)
        XCTAssertEqual(manager.tasks.count, 1)
        XCTAssertNil(manager.tasks.first { $0.id == t1.id })
    }
}
