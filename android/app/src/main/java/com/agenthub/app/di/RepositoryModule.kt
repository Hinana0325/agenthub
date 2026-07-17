package com.agenthub.app.di

import com.agenthub.app.data.local.dao.ActivityDao
import com.agenthub.app.data.local.dao.AgentConfigDao
import com.agenthub.app.data.local.dao.MessageDao
import com.agenthub.app.data.local.dao.SessionDao
import com.agenthub.app.data.repository.ChatRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideChatRepository(
        sessionDao: SessionDao,
        messageDao: MessageDao,
        agentConfigDao: AgentConfigDao,
        activityDao: ActivityDao
    ): ChatRepository = ChatRepository(sessionDao, messageDao, agentConfigDao, activityDao)
}
