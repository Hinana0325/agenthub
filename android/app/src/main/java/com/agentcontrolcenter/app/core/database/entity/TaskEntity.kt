package com.agentcontrolcenter.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 4.2: Task 持久化实体。
 *
 * 索引设计：
 * - [agentId]：[TaskDao.getTasksForAgent] 按 agentId 过滤
 * - [status]：[TaskDao.getActiveTasks] 按 status 过滤 Pending/Running
 * - [createdAt]：按时间排序展示
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index("agentId"),
        Index("status"),
        Index("createdAt")
    ]
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val sessionId: String?,
    val type: String,
    val input: String,
    val status: String,
    val result: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val error: String?
)
