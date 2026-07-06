package com.agenthub.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.agenthub.app.data.local.dao.ActivityDao
import com.agenthub.app.data.local.dao.AgentConfigDao
import com.agenthub.app.data.local.dao.MessageDao
import com.agenthub.app.data.local.dao.SessionDao
import com.agenthub.app.data.local.entity.ActivityLogEntity
import com.agenthub.app.data.local.entity.AgentConfigEntity
import com.agenthub.app.data.local.entity.MessageEntity
import com.agenthub.app.data.local.entity.SessionEntity

@Database(
    entities = [SessionEntity::class, MessageEntity::class, AgentConfigEntity::class, ActivityLogEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun agentConfigDao(): AgentConfigDao
    abstract fun activityDao(): ActivityDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agenthub.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
