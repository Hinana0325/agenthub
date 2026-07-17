package com.agenthub.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agenthub.app.data.local.dao.ActivityDao
import com.agenthub.app.data.local.dao.AgentConfigDao
import com.agenthub.app.data.local.dao.MessageDao
import com.agenthub.app.data.local.dao.PluginDao
import com.agenthub.app.data.local.dao.SessionDao
import com.agenthub.app.data.local.entity.ActivityLogEntity
import com.agenthub.app.data.local.entity.AgentConfigEntity
import com.agenthub.app.data.local.entity.MessageEntity
import com.agenthub.app.data.local.entity.PluginEntity
import com.agenthub.app.data.local.entity.SessionEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        AgentConfigEntity::class,
        ActivityLogEntity::class,
        PluginEntity::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun agentConfigDao(): AgentConfigDao
    abstract fun activityDao(): ActivityDao
    abstract fun pluginDao(): PluginDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId_timestamp ON messages (sessionId, timestamp)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToId TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agenthub.db"
                ).addMigrations(MIGRATION_4_5, MIGRATION_5_6).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
