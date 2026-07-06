package com.agenthub.app.data.model

data class ChatBackup(
    val version: String = "2.2.0",
    val exportedAt: Long = System.currentTimeMillis(),
    val sessions: List<Session> = emptyList(),
    val messages: List<Message> = emptyList()
)
