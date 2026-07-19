package com.agenthub.app.data.repository

import androidx.room.withTransaction
import com.agenthub.app.core.database.AppDatabase
import com.agenthub.app.core.database.dao.ActivityDao
import com.agenthub.app.core.database.dao.AgentConfigDao
import com.agenthub.app.core.database.dao.MessageDao
import com.agenthub.app.core.database.dao.SessionDao
import com.agenthub.app.core.database.entity.ActivityLogEntity
import com.agenthub.app.core.database.entity.AgentConfigEntity
import com.agenthub.app.core.database.entity.MessageEntity
import com.agenthub.app.core.database.entity.SessionEntity
import com.agenthub.app.agent.model.AgentConfig
import com.agenthub.app.agent.model.AgentType
import com.agenthub.app.data.model.ActivityItem
import com.agenthub.app.data.model.Message
import com.agenthub.app.data.model.MessageRole
import com.agenthub.app.data.model.MessageStatus
import com.agenthub.app.data.model.Session
import com.agenthub.app.core.security.KeystoreManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Singleton

@Singleton
class ChatRepository @javax.inject.Inject constructor(
    private val database: AppDatabase,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val agentConfigDao: AgentConfigDao,
    private val activityDao: ActivityDao
) {
    private val gson = Gson()

    // 首次启动播种一个本地 Ollama 默认端点（id 以 "seed_" 开头，开屏不自动连，
    // 仅作为「Agents」里的一键连接起点；若本机未运行 Ollama 则连接会如实报错）。
    private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        seedScope.launch {
            try {
                if (agentConfigDao.getAllConfigsOnce().isEmpty()) {
                    saveConfig(
                        AgentConfig(
                            id = "seed_ollama",
                            name = "Local Ollama",
                            type = AgentType.LocalModel,
                            serverUrl = "http://127.0.0.1:11434",
                            model = "llama3"
                        )
                    )
                }
            } catch (_: Exception) { /* 忽略种子失败，不影响主流程 */ }
        }
    }

    // ── Sessions ──

    fun getAllSessions(): Flow<List<Session>> =
        sessionDao.getAllSessions().map { entities -> entities.map { it.toModel() } }
            .flowOn(Dispatchers.IO)

    suspend fun getAllSessionsList(): List<Session> =
        withContext(Dispatchers.IO) { sessionDao.getAllSessionsOnce().map { it.toModel() } }

    suspend fun getSessionById(id: String): Session? =
        sessionDao.getSessionById(id)?.toModel()

    suspend fun createSession(title: String): Session {
        val session = SessionEntity(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        sessionDao.insertSession(session)
        return session.toModel()
    }

    suspend fun deleteSession(id: String) {
        sessionDao.deleteSessionById(id)
    }

    suspend fun insertSessionDirect(session: Session) {
        sessionDao.insertSession(SessionEntity(
            id = session.id,
            title = session.title,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            isPinned = session.isPinned,
            messageCount = session.messageCount,
            summary = session.summary
        ))
    }

    suspend fun togglePin(id: String, isPinned: Boolean) {
        sessionDao.togglePin(id, isPinned)
    }

    // ── Messages ──

    fun getMessagesBySession(sessionId: String): Flow<List<Message>> =
        messageDao.getMessagesBySession(sessionId).map { entities -> entities.map { it.toModel() } }
            .flowOn(Dispatchers.IO)

    suspend fun getMessagesBySessionList(sessionId: String): List<Message> =
        withContext(Dispatchers.IO) { messageDao.getMessagesBySessionOnce(sessionId).map { it.toModel() } }

    suspend fun sendMessage(
        sessionId: String,
        content: String,
        role: MessageRole,
        attachmentType: String? = null,
        attachmentData: String? = null,
        attachmentName: String? = null,
        replyToId: String? = null
    ): Message {
        val message = MessageEntity(
            id = java.util.UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role.name,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.Sending.name,
            attachmentType = attachmentType,
            attachmentData = attachmentData,
            attachmentName = attachmentName,
            replyToId = replyToId
        )
        // Phase 1.2: 包裹事务，保证 insertMessage 与 incrementMessageCount 原子完成。
        database.withTransaction {
            messageDao.insertMessage(message)
            sessionDao.incrementMessageCount(sessionId, System.currentTimeMillis())
        }
        return message.toModel()
    }

    suspend fun updateMessageStatus(id: String, content: String, status: MessageStatus) {
        messageDao.updateMessage(id, content, status.name)
    }

    suspend fun deleteMessagesBySession(sessionId: String) {
        // Phase 1.2: 包裹事务，保证删消息与重置计数原子完成。
        database.withTransaction {
            messageDao.deleteMessagesBySession(sessionId)
            sessionDao.resetMessageCount(sessionId, System.currentTimeMillis())
        }
    }

    suspend fun deleteMessage(messageId: String) {
        // Phase 1.2: 包裹事务，保证查消息、删消息、减计数原子完成。
        database.withTransaction {
            val message = messageDao.getMessageById(messageId)
            messageDao.deleteMessageById(messageId)
            message?.sessionId?.let { sessionDao.decrementMessageCount(it) }
        }
    }

    // ── Backup Import (atomic) ──

    suspend fun importBackup(sessions: List<Session>, messages: List<Message>) {
        // 用 Room 事务确保原子性：任一插入失败则整体回滚，避免半导入状态。
        database.withTransaction {
            sessions.forEach { insertSessionDirect(it) }
            messages.forEach { insertMessageDirect(it) }
        }
    }

    suspend fun insertMessageDirect(message: Message) {
        messageDao.insertMessage(MessageEntity(
            id = message.id,
            sessionId = message.sessionId,
            role = message.role.name,
            content = message.content,
            timestamp = message.timestamp,
            status = message.status.name,
            metadataJson = gson.toJson(message.metadata),
            attachmentType = message.attachmentType,
            attachmentData = message.attachmentData,
            attachmentName = message.attachmentName,
            reaction = message.reaction,
            replyToId = message.replyToId
        ))
    }

    suspend fun searchMessages(query: String): List<Message> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        // Phase 1.2: LIKE 查询 + toModel 映射在 IO 线程执行。
        return withContext(Dispatchers.IO) {
            messageDao.searchMessages("%$escaped%").map { it.toModel() }
        }
    }

    suspend fun updateReaction(messageId: String, reaction: String) {
        messageDao.updateReaction(messageId, reaction)
    }

    // ── Agent Configs ──

    fun getAllConfigs(): Flow<List<AgentConfig>> =
        agentConfigDao.getAllConfigs().map { entities -> entities.map { it.toModel() } }.flowOn(Dispatchers.IO)

    suspend fun getAllConfigsList(): List<AgentConfig> =
        // Phase 1.2: toModel() 内部调用 KeystoreManager.decryptOrRaw（硬件 Keystore +
        // AES-GCM 同步操作），必须在 IO 线程执行，否则阻塞主线程导致 ANR。
        withContext(Dispatchers.IO) { agentConfigDao.getAllConfigsOnce().map { it.toModel() } }

    suspend fun saveConfig(config: AgentConfig) {
        // Phase 1.2: toEntity() 内部调用 KeystoreManager.encrypt（硬件 Keystore +
        // AES-GCM 同步操作），必须在 IO 线程执行。
        withContext(Dispatchers.IO) { agentConfigDao.insertConfig(config.toEntity()) }
    }

    suspend fun deleteConfig(id: String) {
        agentConfigDao.deleteConfig(id)
    }

    // ── Activity ──

    fun getAllActivities(): Flow<List<ActivityItem>> =
        activityDao.getAllActivities().map { entities -> entities.map { it.toActivityModel() } }
            .flowOn(Dispatchers.IO)

    suspend fun logActivity(type: String, title: String, description: String = "") {
        val entity = ActivityLogEntity(
            id = java.util.UUID.randomUUID().toString(),
            type = type,
            title = title,
            description = description,
            timestamp = System.currentTimeMillis()
        )
        activityDao.insertActivity(entity)
    }

    suspend fun clearActivityLog() {
        activityDao.clearAll()
    }

    // ── Mappers ──

    private fun SessionEntity.toModel() = Session(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isPinned = isPinned,
        messageCount = messageCount,
        summary = summary
    )

    private fun MessageEntity.toModel() = Message(
        id = id,
        sessionId = sessionId,
        role = try { MessageRole.valueOf(role) } catch (_: Exception) { MessageRole.System },
        content = content,
        timestamp = timestamp,
        status = try { MessageStatus.valueOf(status) } catch (_: Exception) { MessageStatus.Sent },
        metadata = try {
            // Phase 1.2: 使用缓存的 TypeToken，避免每次调用都新建匿名子类实例（反射开销大）。
            (gson.fromJson(metadataJson, METADATA_MAP_TYPE) as? Map<String, String>) ?: emptyMap()
        } catch (_: Exception) { emptyMap() },
        attachmentType = attachmentType,
        attachmentData = attachmentData,
        attachmentName = attachmentName,
        reaction = reaction,
        replyToId = replyToId
    )

    private fun AgentConfig.toEntity() = AgentConfigEntity(
        id = id,
        name = name,
        type = type.name,
        serverUrl = serverUrl,
        apiKey = if (apiKey.isBlank()) apiKey else KeystoreManager.encrypt(apiKey),
        model = model,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens
    )

    private fun AgentConfigEntity.toModel() = AgentConfig(
        id = id,
        name = name,
        type = try { AgentType.valueOf(type) } catch (_: Exception) { AgentType.Hermes },
        serverUrl = serverUrl,
        apiKey = KeystoreManager.decryptOrRaw(apiKey),
        model = model,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens
    )

    private fun ActivityLogEntity.toActivityModel() = ActivityItem(
        id = id,
        type = type,
        title = title,
        description = description,
        timestamp = timestamp
    )

    companion object {
        /**
         * Phase 1.2: 缓存 metadata Map 的 TypeToken，避免每次 toModel() 调用都
         * 新建匿名 TypeToken 子类实例（涉及类加载 + 反射，开销极大）。
         * TypeToken.type 是线程安全的，可在 companion object 中共享。
         */
        private val METADATA_MAP_TYPE = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
    }
}
