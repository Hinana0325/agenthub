import Foundation
import Observation

// MARK: - TaskManager
// 对应 Android TaskManager (Phase 4.2)

/// 任务管理器 — 管理下发给 Agent 的异步任务。
///
/// 与 Chat 的区别：
/// - Chat 是同步的请求-响应流
/// - Task 是异步的、可追踪的、可调度的执行单元
///
/// 例如：
/// - "让 OpenCode 重构这个文件" → Task
/// - "让 OpenAI 生成图片" → Task
/// - "让 Hermes 执行工作流" → Task
///
/// Phase 4.2 说明：
/// - Android 版通过 Room TaskDao 持久化任务到数据库
/// - iOS 版当前使用内存存储，后续可通过 SwiftData / CoreData 补齐持久化
/// - 数据模型复用 Models/Task.swift 中的 AgentTask / TaskType / TaskStatus
///
/// `@MainActor` 隔离保证 `tasks` 等响应式状态的读写均在主线程进行，
/// 避免 SwiftUI 视图读取时发生数据竞争。
@MainActor
@Observable
final class TaskManager {

    /// 所有任务列表
    private(set) var tasks: [AgentTask] = []

    // MARK: - 任务提交

    /// 提交新任务
    /// - Parameters:
    ///   - agentId: 目标 Agent ID
    ///   - type: 任务类型
    ///   - input: 任务输入内容
    ///   - sessionId: 关联的会话 ID（可选）
    /// - Returns: 新创建的任务
    @discardableResult
    func submitTask(
        agentId: String,
        type: TaskType,
        input: String,
        sessionId: String? = nil
    ) -> AgentTask {
        let task = AgentTask(
            id: "task_\(UUID().uuidString)",
            agentId: agentId,
            sessionId: sessionId,
            type: type,
            input: input
        )
        tasks.append(task)
        return task
    }

    // MARK: - 状态更新

    /// 更新任务状态
    /// - Parameters:
    ///   - taskId: 任务 ID
    ///   - status: 新状态
    ///   - result: 任务结果（可选，终态时填充）
    ///   - error: 错误信息（可选，失败时填充）
    func updateStatus(
        taskId: String,
        status: TaskStatus,
        result: String? = nil,
        error: String? = nil
    ) {
        guard let index = tasks.firstIndex(where: { $0.id == taskId }) else { return }
        var task = tasks[index]
        task.status = status
        // 仅在提供新值时更新 result 和 error，保留已有值
        if let result = result { task.result = result }
        if let error = error { task.error = error }
        // 终态（完成/失败/取消）时记录完成时间
        if status.isTerminal {
            task.completedAt = Int64(Date().timeIntervalSince1970 * 1000)
        }
        tasks[index] = task
    }

    // MARK: - 查询

    /// 获取指定 Agent 的所有任务
    /// - Parameter agentId: Agent ID
    /// - Returns: 该 Agent 的任务列表
    func getTasksForAgent(_ agentId: String) -> [AgentTask] {
        tasks.filter { $0.agentId == agentId }
    }

    /// 当前活跃任务（待处理或运行中）
    var activeTasks: [AgentTask] {
        tasks.filter { $0.status == .pending || $0.status == .running }
    }

    // MARK: - 任务控制

    /// 取消任务
    /// - Parameter taskId: 任务 ID
    func cancelTask(_ taskId: String) {
        updateStatus(taskId: taskId, status: .cancelled)
    }

    /// 删除任务
    /// - Parameter taskId: 任务 ID
    func deleteTask(_ taskId: String) {
        tasks.removeAll { $0.id == taskId }
    }
}
