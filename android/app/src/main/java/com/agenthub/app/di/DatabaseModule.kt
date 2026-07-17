package com.agenthub.app.di

import android.content.Context
import com.agenthub.app.data.local.AppDatabase
import com.agenthub.app.data.local.dao.ActivityDao
import com.agenthub.app.data.local.dao.AgentConfigDao
import com.agenthub.app.data.local.dao.MessageDao
import com.agenthub.app.data.local.dao.SessionDao
import com.agenthub.app.data.settings.SettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideAgentConfigDao(db: AppDatabase): AgentConfigDao = db.agentConfigDao()

    @Provides
    fun provideActivityDao(db: AppDatabase): ActivityDao = db.activityDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore =
        SettingsDataStore(context)
}
