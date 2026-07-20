package com.agentcontrolcenter.app.runtime.task

import com.agentcontrolcenter.app.core.database.dao.TaskDao
import com.agentcontrolcenter.app.core.database.entity.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
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
 *
 * Phase 4.2: 通过 [taskDao] 持久化任务到 Room。
 * App 重启后任务历史不丢失。内存中的 [_tasks] StateFlow 仍保留用于
 * 即时 UI 反馈，但真实数据源是数据库。
 */
@Singleton
class TaskManager @Inject constructor(
    private val taskDao: TaskDao
) {

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

    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val tasks: StateFlow<List<AgentTask>> = _tasks.asStateFlow()

    init {
        // 订阅数据库变更，同步到内存 StateFlow
        supervisorScope.launch {
            taskDao.getAllTasks().collect { entities ->
                _tasks.value = entities.map { it.toModel() }
            }
        }
    }

    fun submitTask(agentId: String, type: TaskType, input: String, sessionId: String? = null): AgentTask {
        val task = AgentTask(
            id = "task_${UUID.randomUUID()}",
            agentId = agentId,
            sessionId = sessionId,
            type = type,
            input = input
        )
        // 持久化到数据库
        supervisorScope.launch {
            taskDao.upsertTask(task.toEntity())
        }
        // 同时更新内存（数据库 Flow 回调也会更新，但这里先更新保证即时反馈）
        _tasks.update { it + task }
        return task
    }

    fun updateTaskStatus(taskId: String, status: TaskStatus, result: String? = null, error: String? = null) {
        val completedAt = if (status == TaskStatus.Completed || status == TaskStatus.Failed || status == TaskStatus.Cancelled) {
            System.currentTimeMillis()
        } else null

        // 持久化到数据库
        supervisorScope.launch {
            taskDao.updateTaskStatus(
                taskId = taskId,
                status = status.name,
                result = result,
                error = error,
                completedAt = completedAt
            )
        }
        // 同时更新内存
        _tasks.update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(
                        status = status,
                        result = result ?: task.result,
                        error = error ?: task.error,
                        completedAt = completedAt
                    )
                } else task
            }
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

    /** 删除任务（从数据库和内存中移除）。 */
    fun deleteTask(taskId: String) {
        supervisorScope.launch {
            taskDao.deleteTask(taskId)
        }
        _tasks.update { list -> list.filter { it.id != taskId } }
    }

    // ── Entity ↔ Model 转换 ──

    private fun AgentTask.toEntity() = TaskEntity(
        id = id,
        agentId = agentId,
        sessionId = sessionId,
        type = type.name,
        input = input,
        status = status.name,
        result = result,
        createdAt = createdAt,
        completedAt = completedAt,
        error = error
    )

    private fun TaskEntity.toModel() = AgentTask(
        id = id,
        agentId = agentId,
        sessionId = sessionId,
        type = try { TaskType.valueOf(type) } catch (_: Exception) { TaskType.CHAT },
        input = input,
        status = try { TaskStatus.valueOf(status) } catch (_: Exception) { TaskStatus.Pending },
        result = result,
        createdAt = createdAt,
        completedAt = completedAt,
        error = error
    )
}
