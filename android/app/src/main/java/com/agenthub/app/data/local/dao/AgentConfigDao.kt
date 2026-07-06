package com.agenthub.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agenthub.app.data.local.entity.AgentConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentConfigDao {
    @Query("SELECT * FROM agent_configs")
    fun getAllConfigs(): Flow<List<AgentConfigEntity>>

    @Query("SELECT * FROM agent_configs")
    suspend fun getAllConfigsOnce(): List<AgentConfigEntity>

    @Query("SELECT * FROM agent_configs WHERE id = :id")
    suspend fun getConfigById(id: String): AgentConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: AgentConfigEntity)

    @Query("DELETE FROM agent_configs WHERE id = :id")
    suspend fun deleteConfig(id: String)
}
