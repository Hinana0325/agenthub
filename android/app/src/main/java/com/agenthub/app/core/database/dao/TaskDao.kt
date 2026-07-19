package com.agenthub.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agenthub.app.core.database.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * Phase 4.2: Task DAO。
 *
 * [TaskManager] 通过本 DAO 持久化任务状态到 Room，
 * App 重启后任务历史不丢失。
 */
@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE agentId = :agentId ORDER BY createdAt DESC")
    fun getTasksForAgent(agentId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status IN ('Pending', 'Running') ORDER BY createdAt DESC")
    fun getActiveTasks(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Query("UPDATE tasks SET status = :status, result = :result, error = :error, completedAt = :completedAt WHERE id = :taskId")
    suspend fun updateTaskStatus(
        taskId: String,
        status: String,
        result: String?,
        error: String?,
        completedAt: Long?
    )

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)
}
