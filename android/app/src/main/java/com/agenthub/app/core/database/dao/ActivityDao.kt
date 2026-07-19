package com.agenthub.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.agenthub.app.core.database.entity.ActivityLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT 200")
    fun getAllActivities(): Flow<List<ActivityLogEntity>>

    @Insert
    suspend fun insertActivity(activity: ActivityLogEntity)

    @Query("DELETE FROM activity_log")
    suspend fun clearAll()
}
