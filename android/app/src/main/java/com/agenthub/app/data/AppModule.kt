package com.agenthub.app.data

import android.content.Context
import com.agenthub.app.data.local.AppDatabase
import com.agenthub.app.data.repository.ChatRepository

object AppModule {
    private var repository: ChatRepository? = null

    fun getRepository(context: Context): ChatRepository {
        return repository ?: synchronized(this) {
            repository ?: createRepository(context).also { repository = it }
        }
    }

    private fun createRepository(context: Context): ChatRepository {
        val db = AppDatabase.getInstance(context)
        return ChatRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            agentConfigDao = db.agentConfigDao(),
            activityDao = db.activityDao()
        )
    }
}
