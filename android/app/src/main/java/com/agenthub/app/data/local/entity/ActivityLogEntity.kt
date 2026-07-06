package com.agenthub.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_log")
data class ActivityLogEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val description: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
