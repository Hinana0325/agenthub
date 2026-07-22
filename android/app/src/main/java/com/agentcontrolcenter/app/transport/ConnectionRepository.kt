package com.agentcontrolcenter.app.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.transport.protocol.AgentConnectionState
import com.agentcontrolcenter.app.transport.protocol.AgentEvent
import com.agentcontrolcenter.app.transport.protocol.AgentTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport 单例所有者。
 *
 * **背景**：此前 [com.agentcontrolcenter.app.feature.chat.ChatViewModel] 和
 * [com.agentcontrolcenter.app.AgentConnectionService] 各自通过 [TransportFactory.create]
 * 创建独立的 transport 实例，导致双连接、双消息流、生命周期割裂。
 *
 * **修复**：本类作为 `@Singleton`，在进程内持有唯一的 transport 实例。
 * ChatViewModel 和 AgentConnectionService 通过 Hilt 注入本类，共享同一个
 * transport 及其 events / connectionState 流。
 *
 * **Phase 1.4 网络切换感知**：注册 [ConnectivityManager.NetworkCallback]，
 * 在网络丢失后恢复时主动重连。WiFi 与蜂窝切换不再需要等超时才感知。
 */
@Singleton
class ConnectionRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val transportFactory: TransportFactory
) {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _transport = MutableStateFlow<AgentTransport?>(null)

    /**
     * 最近一次 [connect] 的配置，用于网络恢复后自动重连。
     * 在 [connect] 中赋值，在 [shutdown] 中清空。
     *
     * H6: 加 @Volatile。这两个字段在 [connect]（mutex 内）写入，
     * 但在 ConnectivityManager 的 onAvailable 回调（工作线程）中无锁读取，
     * 普通 var 无跨线程内存可见性保证。
     */
    @Volatile private var lastConfig: AgentConfig? = null
    @Volatile private var lastE2eKey: String? = null

    /**
     * 当前 transport 实例的事件流。当 transport 被切换时，[flatMapLatest] 自动
     * 取消旧 transport 的事件收集并切换到新 transport。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: Flow<AgentEvent> = _transport.filterNotNull().flatMapLatest { it.events }

    /**
     * 当前 transport 的连接状态。通过 [stateIn] 转换为 [StateFlow]，支持 `.value`
     * 同步读取和 Flow 收集。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState: StateFlow<AgentConnectionState> =
        _transport.flatMapLatest { t -> t?.connectionState ?: flowOf(AgentConnectionState()) }
            .stateIn(repositoryScope, SharingStarted.Eagerly, AgentConnectionState())

    /** 当前是否已连接（便捷访问器）。 */
    val isConnected: Boolean get() = connectionState.value.isConnected

    /** 网络回调，用于感知 WiFi ↔ 蜂窝切换并在恢复时重连。 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // C5 修复：原为普通 var，onLost/onAvailable 在 ConnectivityManager 工作线程写入，
    // 主线程读取无内存可见性保证。改为 @Volatile 保证跨线程可见。
    @Volatile private var isNetworkAvailable = true

    init {
        registerNetworkCallback()
    }

    /**
     * Phase 1.4: 注册网络回调，在网络恢复后主动重连。
     *
     * - onLost: 标记网络不可用，transport 的超时机制会自然感知断开。
     * - onAvailable: 网络恢复，若此前有连接配置则主动重连，不等超时。
     *
     * 回调注册在 init 中完成，随 @Singleton 生命周期常驻。
     */
    private fun registerNetworkCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                isNetworkAvailable = false
            }
            override fun onAvailable(network: Network) {
                if (!isNetworkAvailable) {
                    isNetworkAvailable = true
                    // 网络从断开恢复到可用，主动重连
                    val config = lastConfig ?: return
                    repositoryScope.launch {
                        try {
                            connect(config, lastE2eKey)
                        } catch (_: Exception) {
                            // 重连失败，等待下次网络变化或手动重试
                        }
                    }
                }
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
        } catch (_: Exception) {
            // 某些设备可能缺少 NETWORK_CALLBACK 权限，静默降级
        }
    }

    /**
     * 连接到指定 Agent。
     *
     * 若当前 transport 不存在或类型不同，则先 [AgentTransport.shutdown] 旧 transport，
     * 再通过 [TransportFactory] 创建新 transport。若类型相同则复用并重新连接。
     */
    suspend fun connect(config: AgentConfig, e2eKey: String?) {
        mutex.withLock {
            // 记录配置用于网络恢复后重连
            lastConfig = config
            lastE2eKey = e2eKey
            val current = _transport.value
            val needsNew = current == null || current.connectionState.value.agentType != config.type
            val transport = if (needsNew) {
                current?.shutdown()
                transportFactory.create(config.type).also { _transport.value = it }
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
     * 断开当前连接但保留 transport 实例以便后续重连。
     */
    fun disconnect() {
        _transport.value?.disconnect()
    }

    /**
     * 彻底关闭 transport 并释放所有底层资源。
     *
     * 调用后 [_transport] 置为 null，后续需要重新 [connect] 才能恢复连接。
     */
    fun shutdown() {
        _transport.value?.shutdown()
        _transport.value = null
        lastConfig = null
        lastE2eKey = null
    }

    /**
     * Hilt 销毁单例时调用，确保 NetworkCallback 注销 + repositoryScope 取消。
     *
     * C5 修复：原实现仅在 [shutdown] 中关闭 transport，从未注销 NetworkCallback
     * 也未取消 repositoryScope（SupervisorJob 泄漏）。在多进程 / 测试 / 内存压力
     * 进程重启场景下，回调绑定对象无法回收，且 ConnectivityManager 维护的 callback
     * 列表会强引用 appContext 导致进程级泄漏。
     *
     * CI-fix: 移除 @PreDestroy 注解。F3 原加 `import javax.annotation.PreDestroy`，
     * 但项目未引入 `javax.annotation-api` 依赖，导致 `Unresolved reference 'PreDestroy'`
     * 编译失败（Android Lint + Assemble 两个 job 均 FAILED）。
     * Hilt `@Singleton` 生命周期与 Application 一致，`@PreDestroy` 仅在进程退出时触发，
     * 实用价值有限；显式调用 [shutdown] / [onDispose] 仍是主要清理路径，F3 的 `@Volatile`
     * 并发修复保留。
     */
    fun onDispose() {
        // 注销网络回调，避免 ConnectivityManager 持有 callback 强引用
        networkCallback?.let { cb ->
            try {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
                // callback 已注销或未注册，忽略
            }
        }
        networkCallback = null
        // 取消所有 repositoryScope 启动的协程（含 stateIn 热流）
        repositoryScope.cancel()
        // 顺带 shutdown transport（防御性）
        shutdown()
    }
}
