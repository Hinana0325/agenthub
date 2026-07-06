package com.agenthub.app.data.model

data class ActivityItem(
    val id: String,
    val type: String,
    val title: String,
    val description: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
