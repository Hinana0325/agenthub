package com.agentcontrolcenter.app.feature.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcontrolcenter.app.runtime.task.TaskManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 任务过滤维度，对齐 iOS TasksView.TaskFilter：
 * - [All]：全部任务
 * - [Active]：Pending / Running 视为进行中
 * - [Completed]：Completed 视为已完成
 * - [Failed]：Failed / Cancelled 视为失败/取消
 */
enum class TaskFilter(val labelRes: Int) {
    All(com.agentcontrolcenter.app.R.string.tasks_filter_all),
    Active(com.agentcontrolcenter.app.R.string.tasks_filter_active),
    Completed(com.agentcontrolcenter.app.R.string.tasks_filter_completed),
    Failed(com.agentcontrolcenter.app.R.string.tasks_filter_failed)
}

/**
 * UI 状态：当前过滤条件与刷新标志。
 *
 * `filteredTasks` 由 [TaskManager.tasks] 与 [filter] 在 Flow 中组合计算，UI 层只需 collect。
 */
data class TasksUiState(
    val filter: TaskFilter = TaskFilter.All,
    val isRefreshing: Boolean = false
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskManager: TaskManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    /**
     * 组合后的过滤任务列表 StateFlow。
     *
     * 使用 [combine] 将 [TaskManager.tasks] 与 [_uiState] 中的 filter 合并，
     * 在 Flow 中计算过滤结果，UI 层 collect 即可。
     */
    val filteredTasks: StateFlow<List<TaskManager.AgentTask>> =
        combine(taskManager.tasks, _uiState) { tasks, state ->
            when (state.filter) {
                TaskFilter.All -> tasks
                TaskFilter.Active -> tasks.filter {
                    it.status == TaskManager.TaskStatus.Pending ||
                        it.status == TaskManager.TaskStatus.Running
                }
                TaskFilter.Completed -> tasks.filter {
                    it.status == TaskManager.TaskStatus.Completed
                }
                TaskFilter.Failed -> tasks.filter {
                    it.status == TaskManager.TaskStatus.Failed ||
                        it.status == TaskManager.TaskStatus.Cancelled
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    /** 当前过滤条件下可见的任务数量。 */
    val taskCount: StateFlow<Int> = filteredTasks.map { it.size }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = 0
    )

    fun setFilter(filter: TaskFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    /**
     * 下拉刷新：复用 [TaskManager.tasks] 的最新值。
     *
     * TaskManager 已在 init 中订阅数据库 Flow，因此这里只需模拟
     * 一个短暂的刷新动画（与 iOS TasksView.refreshTasks 对齐）。
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            // 让出当前调度，确保下拉刷新动画正常展示
            kotlinx.coroutines.delay(600L)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * 取消任务：仅在 Pending / Running 状态下可用。
     * 委托 [TaskManager.cancelTask]，会同步更新内存与数据库。
     */
    fun cancelTask(taskId: String) {
        taskManager.cancelTask(taskId)
    }

    /**
     * 删除任务：从内存与数据库中移除。
     * 委托 [TaskManager.deleteTask]。
     */
    fun deleteTask(taskId: String) {
        taskManager.deleteTask(taskId)
    }
}
