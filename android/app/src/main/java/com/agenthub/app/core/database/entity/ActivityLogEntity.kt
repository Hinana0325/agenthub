package com.agenthub.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 2.4: 添加 timestamp 索引。
 * getAllActivities 使用 ORDER BY timestamp DESC LIMIT 200，无索引时全表排序。
 */
@Entity(
    tableName = "activity_log",
    indices = [Index("timestamp")]
)
data class ActivityLogEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val description: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
