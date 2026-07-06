package com.agenthub.app.data.collab

import com.agenthub.app.data.model.Message
import com.agenthub.app.data.model.MessageRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * 协作会话 — 多人同时与 Agent 对话
 *
 * 使用 WebSocket 在已配对设备间广播消息
 * 每个参与者有独立的标识
 */
class CollaborationManager {

    data class Participant(
        val id: String,
        val name: String,
        val isHost: Boolean,
        val joinedAt: Long = System.currentTimeMillis()
    )

    data class CollabSession(
        val id: String,
        val participants: List<Participant>,
        val isActive: Boolean,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class CollabState(
        val session: CollabSession? = null,
        val isInSession: Boolean = false,
        val isHost: Boolean = false,
        val pendingMessages: List<Message> = emptyList()
    )

    private val _collabState = MutableStateFlow(CollabState())
    val collabState: StateFlow<CollabState> = _collabState

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants

    /**
     * 创建新的协作会话（当前用户为 Host）
     */
    suspend fun createSession(hostName: String): CollabSession {
        delay(300)
        val host = Participant(
            id = UUID.randomUUID().toString(),
            name = hostName,
            isHost = true
        )
        val session = CollabSession(
            id = UUID.randomUUID().toString(),
            participants = listOf(host),
            isActive = true
        )
        _participants.value = listOf(host)
        _collabState.value = CollabState(
            session = session,
            isInSession = true,
            isHost = true
        )
        return session
    }

    /**
     * 加入已有的协作会话
     */
    suspend fun joinSession(sessionId: String, participantName: String): Boolean {
        delay(500)
        val participant = Participant(
            id = UUID.randomUUID().toString(),
            name = participantName,
            isHost = false
        )
        val currentState = _collabState.value.session
        if (currentState != null && currentState.id == sessionId) {
            _participants.value = currentState.participants + participant
            _collabState.value = _collabState.value.copy(
                session = currentState.copy(participants = _participants.value)
            )
        } else {
            // Create a mock session for joining
            val session = CollabSession(
                id = sessionId,
                participants = listOf(participant),
                isActive = true
            )
            _participants.value = listOf(participant)
            _collabState.value = CollabState(
                session = session,
                isInSession = true,
                isHost = false
            )
        }
        return true
    }

    /**
     * 广播消息给所有参与者
     */
    suspend fun broadcastMessage(message: Message) {
        val session = _collabState.value.session ?: return
        if (!session.isActive) return
        // In a real implementation, this would send via WebSocket
        delay(100)
    }

    /**
     * 离开协作会话
     */
    suspend fun leaveSession() {
        val session = _collabState.value.session ?: return
        // Remove current participant (last one in list for demo)
        val remaining = _participants.value.dropLast(1)
        _participants.value = remaining

        if (remaining.isEmpty() || _collabState.value.isHost) {
            // Host leaving ends the session
            _collabState.value = CollabState()
        } else {
            _collabState.value = _collabState.value.copy(
                session = session.copy(participants = remaining)
            )
        }
    }

    /**
     * 模拟其他参与者加入（用于演示）
     */
    suspend fun simulateJoin(name: String) {
        delay(800)
        val participant = Participant(
            id = UUID.randomUUID().toString(),
            name = name,
            isHost = false
        )
        val current = _participants.value
        _participants.value = current + participant
        _collabState.value.session?.let { session ->
            _collabState.value = _collabState.value.copy(
                session = session.copy(participants = _participants.value)
            )
        }
    }

    /**
     * 获取当前参与者数量
     */
    fun getParticipantCount(): Int = _participants.value.size

    /**
     * 检查是否在协作会话中
     */
    fun isInCollabSession(): Boolean = _collabState.value.isInSession
}
