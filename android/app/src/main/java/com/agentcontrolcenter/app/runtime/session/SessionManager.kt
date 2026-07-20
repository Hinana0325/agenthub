package com.agentcontrolcenter.app.runtime.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
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
        // 使用 UUID 而非 currentTimeMillis()，避免同一毫秒创建多个 session 时 ID 碰撞。
        val session = Session(
            id = "session_${UUID.randomUUID()}",
            agentId = agentId
        )
        // 原子更新：使用 update { } 而非 value = value + x，避免「读-改-写」竞争丢失并发更新。
        _sessions.update { it + (session.id to session) }
        _activeSessionId.value = session.id
        return session
    }

    fun setActiveSession(sessionId: String) {
        _activeSessionId.value = sessionId
    }

    fun closeSession(sessionId: String) {
        _sessions.update { current ->
            current[sessionId]?.let { session ->
                current + (sessionId to session.copy(isActive = false))
            } ?: current
        }
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = null
        }
    }

    fun getSessionsForAgent(agentId: String): List<Session> {
        return _sessions.value.values.filter { it.agentId == agentId }
    }

    fun incrementMessageCount(sessionId: String) {
        _sessions.update { current ->
            current[sessionId]?.let { session ->
                current + (sessionId to session.copy(messageCount = session.messageCount + 1))
            } ?: current
        }
    }
}
