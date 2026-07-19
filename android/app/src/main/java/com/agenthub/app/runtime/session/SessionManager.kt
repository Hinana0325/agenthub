package com.agenthub.app.runtime.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话管理器 — 管理 Agent 与用户之间的交互会话。
 *
 * 一个 Agent 可以有多个并发会话，每个会话独立维护上下文。
 * 这是从 "Chat → Agent" 到 "Agent → Chat" 架构转型的关键组件。
 */
@Singleton
class SessionManager @Inject constructor() {

    data class Session(
        val id: String,
        val agentId: String,
        val createdAt: Long = System.currentTimeMillis(),
        val messageCount: Int = 0,
        val isActive: Boolean = true
    )

    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessions: StateFlow<Map<String, Session>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    fun createSession(agentId: String): Session {
        val session = Session(
            id = "session_${System.currentTimeMillis()}",
            agentId = agentId
        )
        _sessions.value = _sessions.value + (session.id to session)
        _activeSessionId.value = session.id
        return session
    }

    fun setActiveSession(sessionId: String) {
        _activeSessionId.value = sessionId
    }

    fun closeSession(sessionId: String) {
        val current = _sessions.value.toMutableMap()
        current[sessionId]?.let { session ->
            current[sessionId] = session.copy(isActive = false)
        }
        _sessions.value = current
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = null
        }
    }

    fun getSessionsForAgent(agentId: String): List<Session> {
        return _sessions.value.values.filter { it.agentId == agentId }
    }

    fun incrementMessageCount(sessionId: String) {
        val current = _sessions.value.toMutableMap()
        current[sessionId]?.let { session ->
            current[sessionId] = session.copy(messageCount = session.messageCount + 1)
            _sessions.value = current
        }
    }
}
