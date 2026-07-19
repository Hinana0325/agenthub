package com.agenthub.app.mcp.bridge

import com.agenthub.app.mcp.client.McpClient
import com.agenthub.app.mcp.model.McpServer
import com.agenthub.app.mcp.model.McpToolResult
import com.agenthub.app.mcp.registry.McpRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 桥接层 — Agent 工具调用与 MCP Server 之间的协调器。
 *
 * Phase 5.5: 实现 @Singleton，编排 [McpRegistry] 和 [McpClient]：
 *
 * 1. [connectServer]: 连接 MCP Server → initialize 握手 → tools/list → 注册到 Registry
 * 2. [callTool]: 从 Registry 查找工具归属 server → 委托 McpClient 执行 tools/call
 * 3. [disconnectServer]: 从 Registry 注销 → 关闭 McpClient 连接
 *
 * Agent 层只需调用 [callTool]，无需关心工具来自哪个 server。
 *
 * 设计参考 MCP Host 角色：
 * https://modelcontextprotocol.io/docs/learn/architecture
 */
@Singleton
class McpBridge @Inject constructor(
    private val registry: McpRegistry,
    private val client: McpClient
) {

    /** 连接状态 */
    enum class ConnectionState { Disconnected, Connecting, Connected, Failed }

    data class ServerConnectionState(
        val serverId: String,
        val state: ConnectionState,
        val errorMessage: String? = null
    )

    private val _connectionStates = MutableStateFlow<Map<String, ServerConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ServerConnectionState>> = _connectionStates.asStateFlow()

    /**
     * 连接 MCP Server：initialize 握手 + tools/list + 注册到 Registry。
     *
     * @return true 表示连接成功并已注册工具
     */
    suspend fun connectServer(server: McpServer): Boolean = withContext(Dispatchers.IO) {
        updateConnectionState(server.id, ConnectionState.Connecting)

        try {
            // 1. initialize 握手
            val capabilities = client.connect(server)
            if (capabilities == null) {
                updateConnectionState(server.id, ConnectionState.Failed, "Initialize failed")
                return@withContext false
            }

            // 2. 注册 server 配置（更新 capabilities）
            registry.registerServer(server.copy(capabilities = capabilities))

            // 3. 若支持 tools，获取工具列表并注册
            if (capabilities.tools) {
                val tools = client.listTools(server)
                if (tools != null) {
                    registry.registerTools(server.id, tools)
                }
            }

            updateConnectionState(server.id, ConnectionState.Connected)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateConnectionState(server.id, ConnectionState.Failed, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /**
     * 调用工具 — Agent 层入口。
     *
     * 从 [McpRegistry] 查找工具名归属的 server，再委托 [McpClient] 执行。
     *
     * @param toolName 工具名
     * @param arguments 调用参数
     * @return 工具结果文本（若 isError=true 则为错误描述），null 表示工具不存在
     */
    suspend fun callTool(toolName: String, arguments: Map<String, Any?>): String? = withContext(Dispatchers.IO) {
        val (serverId, _) = registry.findTool(toolName) ?: return@withContext null
        val server = registry.servers.value[serverId] ?: return@withContext null

        val result = client.callTool(server, toolName, arguments) ?: return@withContext null
        result.asText()
    }

    /**
     * 调用工具，返回完整的 [McpToolResult]（含 isError 标志和多种内容类型）。
     */
    suspend fun callToolDetailed(toolName: String, arguments: Map<String, Any?>): McpToolResult? = withContext(Dispatchers.IO) {
        val (serverId, _) = registry.findTool(toolName) ?: return@withContext null
        val server = registry.servers.value[serverId] ?: return@withContext null
        client.callTool(server, toolName, arguments)
    }

    /** 断开 MCP Server 连接。 */
    suspend fun disconnectServer(serverId: String) {
        registry.unregisterServer(serverId)
        client.disconnect(serverId)
        updateConnectionState(serverId, ConnectionState.Disconnected)
    }

    /** 获取所有已注册的工具名。 */
    fun getAvailableTools(): List<String> = registry.getAllToolNames()

    /** 刷新某个 server 的工具列表（重新 tools/list）。 */
    suspend fun refreshTools(serverId: String): Boolean = withContext(Dispatchers.IO) {
        val server = registry.servers.value[serverId] ?: return@withContext false
        val tools = client.listTools(server) ?: return@withContext false
        registry.registerTools(serverId, tools)
        true
    }

    // ── 内部方法 ──

    private fun updateConnectionState(serverId: String, state: ConnectionState, error: String? = null) {
        _connectionStates.update { current ->
            current + (serverId to ServerConnectionState(serverId, state, error))
        }
    }
}
