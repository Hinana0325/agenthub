package com.agenthub.app.runtime.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 任务管理器 — 管理下发给 Agent 的异步任务。
 *
 * 与 Chat 的区别：
 * - Chat 是同步的请求-响应流
 * - Task 是异步的、可追踪的、可调度的执行单元
 *
 * 例如：
 * - "让 OpenCode 重构这个文件" → Task
 * - "让 Ollama 生成图片" → Task
 * - "让 OpenManus 执行工作流" → Task
 */
@Singleton
class TaskManager @Inject constructor() {

    data class AgentTask(
        val id: String,
        val agentId: String,
        val sessionId: String?,
        val type: TaskType,
        val input: String,
        val status: TaskStatus = TaskStatus.Pending,
        val result: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val completedAt: Long? = null,
        val error: String? = null
    )

    enum class TaskType { CHAT, CODE, WORKFLOW, TOOL_CALL, FILE_OPERATION }
    enum class TaskStatus { Pending, Running, Completed, Failed, Cancelled }

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val tasks: StateFlow<List<AgentTask>> = _tasks.asStateFlow()

    fun submitTask(agentId: String, type: TaskType, input: String, sessionId: String? = null): AgentTask {
        val task = AgentTask(
            id = "task_${System.currentTimeMillis()}",
            agentId = agentId,
            sessionId = sessionId,
            type = type,
            input = input
        )
        _tasks.value = _tasks.value + task
        return task
    }

    fun updateTaskStatus(taskId: String, status: TaskStatus, result: String? = null, error: String? = null) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = status,
                    result = result ?: task.result,
                    error = error ?: task.error,
                    completedAt = if (status == TaskStatus.Completed || status == TaskStatus.Failed) System.currentTimeMillis() else task.completedAt
                )
            } else task
        }
    }

    fun getTasksForAgent(agentId: String): List<AgentTask> {
        return _tasks.value.filter { it.agentId == agentId }
    }

    fun getActiveTasks(): List<AgentTask> {
        return _tasks.value.filter { it.status == TaskStatus.Pending || it.status == TaskStatus.Running }
    }

    fun cancelTask(taskId: String) {
        updateTaskStatus(taskId, TaskStatus.Cancelled)
    }
}
