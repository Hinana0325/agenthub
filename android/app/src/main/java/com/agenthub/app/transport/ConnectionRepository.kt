package com.agenthub.app.transport

import com.agenthub.app.agent.model.AgentConfig
import com.agenthub.app.transport.protocol.AgentConnectionState
import com.agenthub.app.transport.protocol.AgentEvent
import com.agenthub.app.transport.protocol.AgentTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport 单例所有者。
 *
 * **背景**：此前 [com.agenthub.app.feature.chat.ChatViewModel] 和
 * [com.agenthub.app.AgentConnectionService] 各自通过 [TransportFactory.create]
 * 创建独立的 transport 实例，导致：
 *  - 双连接：同一 Agent 被连接两次，浪费资源且状态不一致。
 *  - 双消息流：两条独立的 events 流，消息可能被重复处理或丢失。
 *  - 生命周期割裂：ViewModel 销毁断开其连接，Service 的连接不受影响，反之亦然。
 *
 * **修复**：本类作为 `@Singleton`，在进程内持有唯一的 transport 实例。
 * ChatViewModel 和 AgentConnectionService 通过 Hilt 注入本类，共享同一个
 * transport 及其 events / connectionState 流，彻底消除双连接问题。
 *
 * **transport 切换**：当 [connect] 收到的 [AgentConfig.type] 与当前 transport
 * 的类型不同时，旧 transport 会被 [AgentTransport.shutdown] 彻底释放
 * （取消协程作用域、关闭 HttpClient、关闭 Channel），然后创建新 transport。
 * 这修复了此前仅调用 [AgentTransport.disconnect] 导致的协程作用域与
 * HttpClient 泄漏。
 *
 * **生命周期**：本类为 `@Singleton`，其生命周期与 [dagger.hilt.components.SingletonComponent]
 * （即进程）一致。`repositoryScope` 用于 [connectionState] 的 [stateIn]，
 * 在进程存活期间持续收集 transport 的连接状态。
 */
@Singleton
class ConnectionRepository @Inject constructor() {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _transport = MutableStateFlow<AgentTransport?>(null)

    /**
     * 当前 transport 实例的事件流。当 transport 被切换时，[flatMapLatest] 自动
     * 取消旧 transport 的事件收集并切换到新 transport，确保事件不会跨 transport 串流。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: Flow<AgentEvent> = _transport.filterNotNull().flatMapLatest { it.events }

    /**
     * 当前 transport 的连接状态。通过 [stateIn] 转换为 [StateFlow]，支持 `.value`
     * 同步读取（用于 [connect] 中的类型判断）和 Flow 收集（用于 UI / Service 监听）。
     *
     * 当 transport 为 null（尚未连接或已 shutdown）时，发出默认的
     * [AgentConnectionState]（isConnected = false）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState: StateFlow<AgentConnectionState> =
        _transport.flatMapLatest { t -> t?.connectionState ?: flowOf(AgentConnectionState()) }
            .stateIn(repositoryScope, SharingStarted.Eagerly, AgentConnectionState())

    /** 当前是否已连接（便捷访问器）。 */
    val isConnected: Boolean get() = connectionState.value.isConnected

    /**
     * 连接到指定 Agent。
     *
     * 若当前 transport 不存在或类型不同，则先 [AgentTransport.shutdown] 旧 transport
     * （释放协程作用域与 HttpClient），再通过 [TransportFactory] 创建新 transport。
     * 若类型相同则复用现有 transport 并重新连接。
     *
     * [mutex] 保证并发调用时的原子性：不会出现两个协程同时创建 transport 的情况。
     */
    suspend fun connect(config: AgentConfig, e2eKey: String?) {
        mutex.withLock {
            val current = _transport.value
            val needsNew = current == null || current.connectionState.value.agentType != config.type
            val transport = if (needsNew) {
                // 彻底关闭旧 transport（释放 scope + client），而非仅 disconnect。
                // disconnect 只断开连接但不释放底层资源，切换 Agent 类型时会导致泄漏。
                current?.shutdown()
                TransportFactory.create(config.type).also { _transport.value = it }
            } else {
                current
            }
            transport.connect(config, e2eKey = e2eKey)
        }
    }

    /** 通过当前 transport 发送消息。transport 为 null 时安全跳过。 */
    suspend fun sendMessage(sessionId: String, content: String) {
        _transport.value?.sendMessage(sessionId, content)
    }

    /** 清空指定 session 的传输层历史。transport 为 null 时安全跳过。 */
    suspend fun clearHistory(sessionId: String) {
        _transport.value?.clearHistory(sessionId)
    }

    /**
     * 断开当前连接但保留 transport 实例以便后续 [connect] 重连。
     * transport 为 null 时安全跳过。
     */
    fun disconnect() {
        _transport.value?.disconnect()
    }

    /**
     * 彻底关闭 transport 并释放所有底层资源。
     *
     * 调用后 [_transport] 置为 null，后续需要重新 [connect] 才能恢复连接。
     * 通常在进程退出或用户显式断开时调用。
     */
    fun shutdown() {
        _transport.value?.shutdown()
        _transport.value = null
    }
}
