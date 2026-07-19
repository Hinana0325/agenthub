package com.agenthub.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agenthub.app.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("UPDATE messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateMessage(id: String, content: String, status: String)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(): MessageEntity?

    @Query("SELECT * FROM messages WHERE content LIKE :query ESCAPE '\\' ORDER BY timestamp DESC LIMIT 50")
    suspend fun searchMessages(query: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<MessageEntity>

    @Query("UPDATE messages SET reaction = :reaction WHERE id = :id")
    suspend fun updateReaction(id: String, reaction: String)

    // ── Data Insights queries ──

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Long

    @Query("SELECT * FROM messages WHERE role = 'Assistant' ORDER BY timestamp ASC")
    suspend fun getAllAssistantMessages(): List<MessageEntity>

    @Query("SELECT AVG(LENGTH(content)) FROM messages WHERE role = 'User'")
    suspend fun getAvgUserMessageLength(): Int?

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesForInsights(): List<MessageEntity>
}
