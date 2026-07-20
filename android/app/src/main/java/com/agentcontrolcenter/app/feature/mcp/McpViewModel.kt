package com.agentcontrolcenter.app.feature.mcp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcontrolcenter.app.mcp.bridge.McpBridge
import com.agentcontrolcenter.app.mcp.model.McpServer
import com.agentcontrolcenter.app.mcp.model.McpServerCapabilities
import com.agentcontrolcenter.app.mcp.model.McpTransportType
import com.agentcontrolcenter.app.mcp.registry.McpRegistry
import com.agentcontrolcenter.app.core.security.KeystoreManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * MCP 服务器列表持久化到 SharedPreferences（JSON 编码）。
 *
 * 对齐 iOS McpView：iOS 使用 UserDefaults + JSONEncoder 持久化 [McpServer] 列表，
 * Android 端使用 SharedPreferences + Gson 实现等价机制。后续若引入 McpServerEntity
 * 持久化到 Room，可平滑迁移。
 */
private const val MCP_PREFS_NAME = "mcp_prefs"
private const val MCP_SERVERS_KEY = "mcp_servers"

/**
 * UI 状态：服务器列表 + 操作消息 + 测试连接中的 serverId 集合。
 *
 * `connectionStates` 直接来自 [McpBridge]，UI 层 collect 即可。
 */
data class McpUiState(
    val servers: List<McpServer> = emptyList(),
    val editingServer: McpServer? = null,
    val showForm: Boolean = false,
    val message: String? = null,
    val testingServerIds: Set<String> = emptySet()
)

@HiltViewModel
class McpViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mcpBridge: McpBridge,
    private val registry: McpRegistry
) : ViewModel() {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(MCP_PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<McpUiState> = _uiState.asStateFlow()

    /**
     * 组合后的渲染模型：每个 [McpServer] 附带其当前连接状态。
     *
     * 来自 [McpBridge.connectionStates] 的状态实时更新。
     */
    val serversWithState: StateFlow<List<McpServerRowState>> =
        combine(_uiState, mcpBridge.connectionStates) { state, connectionStates ->
            state.servers.map { server ->
                val connState = connectionStates[server.id]?.state
                    ?: McpBridge.ConnectionState.Disconnected
                McpServerRowState(
                    server = server,
                    connectionState = connState,
                    isTesting = server.id in state.testingServerIds
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    /** 已注册到 [McpRegistry] 的工具列表（来自所有已连接服务器）。 */
    val availableTools: StateFlow<List<String>> = registry.tools
        .map { tools -> tools.map { it.name } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = registry.getAllToolNames()
        )

    // ── Form ──

    fun showAddForm() {
        _uiState.update {
            it.copy(
                editingServer = McpServer(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    transportUrl = "",
                    transportType = McpTransportType.HTTP
                ),
                showForm = true
            )
        }
    }

    fun showEditForm(server: McpServer) {
        _uiState.update { it.copy(editingServer = server, showForm = true) }
    }

    fun dismissForm() {
        _uiState.update { it.copy(showForm = false, editingServer = null) }
    }

    /**
     * 保存服务器（新增或更新）。
     * 写入 SharedPreferences 持久化，对齐 iOS saveServers()。
     */
    fun saveServer(server: McpServer) {
        val normalized = server.copy(
            name = server.name.ifBlank { "MCP Server" }
        )
        _uiState.update { current ->
            val exists = current.servers.any { it.id == normalized.id }
            val newServers = if (exists) {
                current.servers.map { if (it.id == normalized.id) normalized else it }
            } else {
                current.servers + normalized
            }
            copyAndPersist(current.copy(servers = newServers, showForm = false, editingServer = null))
        }
    }

    /**
     * 删除服务器：从持久化中移除，并断开当前连接。
     */
    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            try {
                mcpBridge.disconnectServer(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 忽略断开失败，仍要移除本地配置
            }
            _uiState.update { current ->
                val newServers = current.servers.filter { it.id != serverId }
                copyAndPersist(current.copy(servers = newServers))
            }
        }
    }

    /**
     * 连接服务器：委托 [McpBridge.connectServer]。
     */
    fun connectServer(server: McpServer) {
        viewModelScope.launch {
            val ok = try {
                mcpBridge.connectServer(server)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _uiState.update {
                it.copy(message = if (ok) null else it.message)
            }
        }
    }

    /**
     * 断开服务器连接：委托 [McpBridge.disconnectServer]。
     */
    fun disconnectServer(serverId: String) {
        viewModelScope.launch {
            try {
                mcpBridge.disconnectServer(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 忽略
            }
        }
    }

    /**
     * 测试连接：与 [connectServer] 等价，但额外在 UI 上标记 testing 状态。
     */
    fun testConnection(server: McpServer) {
        viewModelScope.launch {
            _uiState.update { it.copy(testingServerIds = it.testingServerIds + server.id) }
            val ok = try {
                mcpBridge.connectServer(server)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _uiState.update {
                it.copy(
                    testingServerIds = it.testingServerIds - server.id,
                    message = if (ok) "MCP_TEST_SUCCESS" else "MCP_TEST_FAILED"
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // ── Persistence ──

    /**
     * 将 servers 列表 JSON 编码后加密并写入 SharedPreferences。
     * 使用 KeystoreManager AES-256-GCM 加密，保护服务器 URL 等敏感配置。
     */
    private fun copyAndPersist(state: McpUiState): McpUiState {
        val json = gson.toJson(state.servers)
        val encrypted = KeystoreManager.encrypt(json)
        prefs.edit().putString(MCP_SERVERS_KEY, encrypted).apply()
        return state
    }

    /**
     * 从 SharedPreferences 加载已保存的 servers 列表。
     * 使用 KeystoreManager.decryptOrRaw 解密，兼容旧版明文存储。
     *
     * 注意：Gson 对没有无参构造函数的 Kotlin data class 会使用 Unsafe 分配，
     * 这会让带默认值的字段（transportType / capabilities）变为 null。
     * 这里在反序列化后显式恢复 Kotlin 默认值，保证非空契约。
     */
    private fun loadInitialState(): McpUiState {
        val raw = prefs.getString(MCP_SERVERS_KEY, null) ?: return McpUiState()
        // decryptOrRaw 兼容旧版明文数据（无 AKS: 前缀时直接返回原文）
        val json = KeystoreManager.decryptOrRaw(raw)
        return try {
            val type = object : TypeToken<List<McpServer>>() {}.type
            val rawList: List<McpServer>? = gson.fromJson(json, type)
            val servers = rawList?.map { server ->
                server.copy(
                    transportType = server.transportType ?: McpTransportType.SSE,
                    capabilities = server.capabilities ?: McpServerCapabilities()
                )
            } ?: emptyList()
            McpUiState(servers = servers)
        } catch (_: Exception) {
            McpUiState()
        }
    }
}

/**
 * 渲染用的服务器行数据：原 [McpServer] + 当前连接状态 + 是否正在测试。
 */
data class McpServerRowState(
    val server: McpServer,
    val connectionState: McpBridge.ConnectionState,
    val isTesting: Boolean
)
