package com.agentcontrolcenter.app.data.model

data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.Sent,
    val metadata: Map<String, String> = emptyMap(),
    val attachmentType: String? = null, // "image", "file"
    val attachmentData: String? = null, // base64 or path
    val attachmentName: String? = null, // display name
    val reaction: String = "", // emoji reaction, e.g. "👍", "❤️"
    val replyToId: String? = null
)

enum class MessageRole { User, Assistant, System, Tool }

enum class MessageStatus { Sending, Sent, Received, Failed }
