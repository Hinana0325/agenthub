package com.agenthub.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index(value = ["sessionId", "timestamp"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val status: String,
    val metadataJson: String = "{}",
    val attachmentType: String? = null,
    val attachmentData: String? = null,
    val attachmentName: String? = null,
    val reaction: String = ""
)
