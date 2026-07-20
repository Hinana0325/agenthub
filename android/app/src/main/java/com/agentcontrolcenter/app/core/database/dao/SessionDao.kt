package com.agentcontrolcenter.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.agentcontrolcenter.app.core.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Delete
    suspend fun deleteSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSessionById(id: String)

    @Query("UPDATE sessions SET isPinned = :isPinned WHERE id = :id")
    suspend fun togglePin(id: String, isPinned: Boolean)

    @Query("UPDATE sessions SET messageCount = messageCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementMessageCount(id: String, updatedAt: Long)

    @Query("UPDATE sessions SET messageCount = messageCount - 1 WHERE id = :id AND messageCount > 0")
    suspend fun decrementMessageCount(id: String)

    @Query("UPDATE sessions SET messageCount = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun resetMessageCount(id: String, updatedAt: Long)

    @Query("SELECT * FROM sessions ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSessionsOnce(): List<SessionEntity>

    // ── Data Insights queries ──

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun getSessionCount(): Long

    @Query("SELECT * FROM sessions ORDER BY createdAt ASC")
    suspend fun getAllSessionsForInsights(): List<SessionEntity>
}
