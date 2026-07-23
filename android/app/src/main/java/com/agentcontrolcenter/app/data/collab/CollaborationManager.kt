package com.agentcontrolcenter.app.data.collab

import com.agentcontrolcenter.app.data.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * 协作会话 — 多人同时与 Agent 对话
 *
 * MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
 *
 * 当前为本地 MVP：会话状态、参与者列表与广播消息均保存在本地内存中，
 * 不涉及任何网络信令。跨设备实时协作所需的 WebSocket 信令将在 v5.2.0 引入。
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
        val inviteCode: String,
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

    // MVP: 本地会话存储（inviteCode -> CollabSession），仅保存在内存中。
    // 真正的跨设备持久化/同步推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）。
    private val localSessions = mutableMapOf<String, CollabSession>()

    // MVP: 本地广播消息日志（仅内存，无网络）。
    private val localMessageLog = mutableListOf<Message>()

    companion object {
        private const val INVITE_CODE_LENGTH = 6
        private const val INVITE_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** 生成 6 位大写字母+数字的邀请码 */
        private fun generateInviteCode(): String =
            (1..INVITE_CODE_LENGTH)
                .map { INVITE_CODE_CHARS.random() }
                .joinToString("")

        /** 校验邀请码格式：6 位大写字母或数字 */
        fun isValidInviteCode(code: String): Boolean =
            code.length == INVITE_CODE_LENGTH &&
                code.all { it in INVITE_CODE_CHARS }
    }

    /**
     * 创建新的协作会话（当前用户为 Host）
     *
     * MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
     */
    suspend fun createSession(hostName: String): CollabSession {
        val host = Participant(
            id = UUID.randomUUID().toString(),
            name = hostName,
            isHost = true
        )
        val session = CollabSession(
            id = UUID.randomUUID().toString(),
            inviteCode = generateInviteCode(),
            participants = listOf(host),
            isActive = true
        )
        // MVP: 持久化到本地内存映射
        localSessions[session.inviteCode] = session
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
     *
     * MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
     */
    suspend fun joinSession(inviteCode: String, participantName: String): Boolean {
        // MVP: 校验邀请码格式
        if (!isValidInviteCode(inviteCode)) return false
        // MVP: 在本地会话存储中查找（跨设备加入需要 v5.2.0 信令支持）
        val existing = localSessions[inviteCode] ?: return false
        val participant = Participant(
            id = UUID.randomUUID().toString(),
            name = participantName,
            isHost = false
        )
        // MVP: 添加参与者并持久化到本地内存
        val updated = existing.copy(participants = existing.participants + participant)
        localSessions[inviteCode] = updated
        _participants.value = updated.participants
        _collabState.value = CollabState(
            session = updated,
            isInSession = true,
            isHost = false
        )
        return true
    }

    /**
     * 广播消息给所有参与者
     *
     * MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
     */
    suspend fun broadcastMessage(message: Message) {
        val session = _collabState.value.session ?: return
        if (!session.isActive) return
        // MVP: 仅追加到本地消息日志，不发送网络
        localMessageLog.add(message)
    }

    /**
     * 离开协作会话
     *
     * MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
     */
    suspend fun leaveSession() {
        val session = _collabState.value.session ?: return
        // MVP: 移除当前参与者（当前实现中以列表最后一个作为本地演示参与者）
        val remaining = _participants.value.dropLast(1)
        _participants.value = remaining

        if (remaining.isEmpty() || _collabState.value.isHost) {
            // MVP: Host 离开或已无参与者 → 销毁本地会话
            localSessions.remove(session.inviteCode)
            _collabState.value = CollabState()
        } else {
            // MVP: 非主持人离开，更新本地会话状态
            val updated = session.copy(participants = remaining)
            localSessions[session.inviteCode] = updated
            _collabState.value = _collabState.value.copy(
                session = updated
            )
        }
    }

    /**
     * 模拟其他参与者加入（用于演示）
     *
     * MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
     */
    suspend fun simulateJoin(name: String) {
        val participant = Participant(
            id = UUID.randomUUID().toString(),
            name = name,
            isHost = false
        )
        val current = _participants.value
        _participants.value = current + participant
        _collabState.value.session?.let { session ->
            val updated = session.copy(participants = _participants.value)
            localSessions[session.inviteCode] = updated
            _collabState.value = _collabState.value.copy(
                session = updated
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
