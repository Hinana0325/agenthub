package com.agentcontrolcenter.app.di

import android.content.Context
import com.agentcontrolcenter.app.core.database.AppDatabase
import com.agentcontrolcenter.app.core.database.dao.ActivityDao
import com.agentcontrolcenter.app.core.database.dao.AgentConfigDao
import com.agentcontrolcenter.app.core.database.dao.MarketplaceFavoriteDao
import com.agentcontrolcenter.app.core.database.dao.MessageDao
import com.agentcontrolcenter.app.core.database.dao.PluginDao
import com.agentcontrolcenter.app.core.database.dao.SessionDao
import com.agentcontrolcenter.app.core.database.dao.TaskDao
import com.agentcontrolcenter.app.core.database.dao.WorkflowRunDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.getInstance(context)

    @Provides fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun provideAgentConfigDao(db: AppDatabase): AgentConfigDao = db.agentConfigDao()
    @Provides fun provideActivityDao(db: AppDatabase): ActivityDao = db.activityDao()
    @Provides fun providePluginDao(db: AppDatabase): PluginDao = db.pluginDao()
    @Provides fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()
    @Provides fun provideWorkflowRunDao(db: AppDatabase): WorkflowRunDao = db.workflowRunDao()
    @Provides fun provideMarketplaceFavoriteDao(db: AppDatabase): MarketplaceFavoriteDao = db.marketplaceFavoriteDao()
}
