package com.agentcontrolcenter.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v4.9.0: 工作流执行历史记录实体。
 *
 * 每次 WorkflowEngine.execute() 生成一条记录，持久化存储供用户回看。
 * 对应 protocol/schemas/workflow-schema.json 中的 WorkflowRunRecord 契约。
 *
 * 索引设计：
 * - [workflowId]：按工作流过滤历史记录
 * - [startedAt]：按时间倒序展示
 */
@Entity(
    tableName = "workflow_runs",
    indices = [
        Index("workflowId"),
        Index("startedAt")
    ]
)
data class WorkflowRunEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val workflowName: String,
    val input: String = "",
    val output: String = "",
    val startedAt: Long,
    val completedAt: Long?,
    val status: String, // RUNNING / COMPLETED / FAILED / CANCELLED
    val failedNodeIdsJson: String = "[]", // Set<String> 序列化
    val error: String?,
    val logsJson: String = "[]" // List<String> 序列化
)
