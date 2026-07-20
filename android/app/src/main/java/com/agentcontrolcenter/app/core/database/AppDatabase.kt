package com.agentcontrolcenter.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agentcontrolcenter.app.core.database.dao.ActivityDao
import com.agentcontrolcenter.app.core.database.dao.AgentConfigDao
import com.agentcontrolcenter.app.core.database.dao.MessageDao
import com.agentcontrolcenter.app.core.database.dao.PluginDao
import com.agentcontrolcenter.app.core.database.dao.SessionDao
import com.agentcontrolcenter.app.core.database.dao.TaskDao
import com.agentcontrolcenter.app.core.database.entity.ActivityLogEntity
import com.agentcontrolcenter.app.core.database.entity.AgentConfigEntity
import com.agentcontrolcenter.app.core.database.entity.MessageEntity
import com.agentcontrolcenter.app.core.database.entity.PluginEntity
import com.agentcontrolcenter.app.core.database.entity.SessionEntity
import com.agentcontrolcenter.app.core.database.entity.TaskEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        AgentConfigEntity::class,
        ActivityLogEntity::class,
        PluginEntity::class,
        TaskEntity::class
    ],
    version = 8,
    // 启用 schema 导出以便迁移测试。
    // TODO: 需在 app/build.gradle 的 ksp / kapt 配置中添加
    //   ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    //   并将 schemas 目录纳入版本控制，否则编译期会发出警告。
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun agentConfigDao(): AgentConfigDao
    abstract fun activityDao(): ActivityDao
    abstract fun pluginDao(): PluginDao
    abstract fun taskDao(): TaskDao

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

        /**
         * Phase 2.4: 为 activity_log.timestamp / messages.timestamp / messages.role
         * 添加单列索引，避免分页查询和角色过滤时全表扫描。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_log_timestamp ON activity_log (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_role ON messages (role)")
            }
        }

        /**
         * Phase 4.2: 新增 tasks 表，支持 TaskManager 持久化。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        agentId TEXT NOT NULL,
                        sessionId TEXT,
                        type TEXT NOT NULL,
                        input TEXT NOT NULL,
                        status TEXT NOT NULL,
                        result TEXT,
                        createdAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        error TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_agentId ON tasks (agentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status ON tasks (status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_createdAt ON tasks (createdAt)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agentcontrolcenter.db"
                )
                    // 不使用 fallbackToDestructiveMigration()：缺少迁移时会抛
                    // IllegalStateException，优先于静默丢失用户数据。
                    // 当前已提供 MIGRATION_4_5 / MIGRATION_5_6 / MIGRATION_6_7 / MIGRATION_7_8；后续新增版本时
                    // 必须显式补齐对应的 Migration。
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
