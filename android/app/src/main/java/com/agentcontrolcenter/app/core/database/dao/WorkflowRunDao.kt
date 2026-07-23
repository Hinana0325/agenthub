package com.agentcontrolcenter.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agentcontrolcenter.app.core.database.entity.WorkflowRunEntity
import kotlinx.coroutines.flow.Flow

/**
 * v4.9.0: 工作流执行历史 DAO。
 */
@Dao
interface WorkflowRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: WorkflowRunEntity)

    @Query("UPDATE workflow_runs SET status = :status, completedAt = :completedAt, output = :output, error = :error, logsJson = :logsJson, failedNodeIdsJson = :failedNodeIdsJson WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        completedAt: Long?,
        output: String,
        error: String?,
        logsJson: String,
        failedNodeIdsJson: String
    )

    @Query("SELECT * FROM workflow_runs ORDER BY startedAt DESC LIMIT :limit")
    fun getRecentRunsFlow(limit: Int = 50): Flow<List<WorkflowRunEntity>>

    @Query("SELECT * FROM workflow_runs WHERE workflowId = :workflowId ORDER BY startedAt DESC")
    fun getRunsForWorkflowFlow(workflowId: String): Flow<List<WorkflowRunEntity>>

    @Query("SELECT * FROM workflow_runs WHERE id = :id")
    suspend fun getRunById(id: String): WorkflowRunEntity?

    @Query("DELETE FROM workflow_runs WHERE startedAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int

    @Query("SELECT COUNT(*) FROM workflow_runs")
    suspend fun getRunCount(): Long
}
