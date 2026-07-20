package com.agentcontrolcenter.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agentcontrolcenter.app.core.database.entity.PluginEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY name ASC")
    fun getAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins")
    suspend fun getAllOnce(): List<PluginEntity>

    @Query("SELECT COUNT(*) FROM plugins")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plugin: PluginEntity)

    @Query("UPDATE plugins SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM plugins WHERE id = :id")
    suspend fun delete(id: String)
}
