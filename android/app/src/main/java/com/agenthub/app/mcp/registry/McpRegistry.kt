package com.agenthub.app.mcp.registry

import com.agenthub.app.mcp.model.McpServer
import com.agenthub.app.mcp.model.McpTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP Server 注册表 — 管理已连接的 MCP Server 及其提供的工具。
 *
 * Phase 5.3: 实现 @Singleton，维护两个索引：
 * - [servers]: serverId -> [McpServer] 配置
 * - [toolsByServer]: serverId -> 该 server 提供的工具列表
 * - [toolIndex]: toolName -> (serverId, [McpTool])，支持按工具名快速查询
 *
 * 当 [McpClient] 连接成功并完成 tools/list 后，调用 [registerTools] 注册工具。
 * [McpBridge] 调用 [findTool] 查询工具归属的 server，再委托 [McpClient] 执行 tools/call。
 *
 * 线程安全：[ConcurrentHashMap] + StateFlow.update 原子写入。
 */
@Singleton
class McpRegistry @Inject constructor() {

    private val _servers = MutableStateFlow<Map<String, McpServer>>(emptyMap())
    val servers: StateFlow<Map<String, McpServer>> = _servers.asStateFlow()

    private val _tools = MutableStateFlow<List<McpTool>>(emptyList())
    val tools: StateFlow<List<McpTool>> = _tools.asStateFlow()

    // 工具名 -> (serverId, McpTool)，用于 O(1) 查找
    private val toolIndex = ConcurrentHashMap<String, Pair<String, McpTool>>()

    /** 注册或更新一个 MCP Server 配置。 */
    fun registerServer(server: McpServer) {
        _servers.update { it + (server.id to server) }
    }

    /** 注销 MCP Server，同时移除其所有工具。 */
    fun unregisterServer(serverId: String) {
        _servers.update { it - serverId }
        // 移除该 server 的所有工具
        val toRemove = toolIndex.entries.filter { it.value.first == serverId }.map { it.key }
        toRemove.forEach { toolIndex.remove(it) }
        _tools.update { list -> list.filter { it.serverId != serverId } }
    }

    /**
     * 注册某个 server 提供的工具列表（来自 tools/list 响应）。
     * 会先清除该 server 的旧工具，再注册新工具。
     */
    fun registerTools(serverId: String, tools: List<McpTool>) {
        // 清除旧工具
        val oldToRemove = toolIndex.entries.filter { it.value.first == serverId }.map { it.key }
        oldToRemove.forEach { toolIndex.remove(it) }

        // 注册新工具
        tools.forEach { tool ->
            toolIndex[tool.name] = serverId to tool
        }

        // 更新 StateFlow：移除旧工具 + 添加新工具
        _tools.update { existing ->
            (existing.filter { it.serverId != serverId } + tools)
        }
    }

    /** 按工具名查找工具及其所属 server。 */
    fun findTool(toolName: String): Pair<String, McpTool>? = toolIndex[toolName]

    /** 获取所有已注册的工具名列表。 */
    fun getAllToolNames(): List<String> = toolIndex.keys().toList().sorted()

    /** 获取某个 server 的所有工具。 */
    fun getToolsForServer(serverId: String): List<McpTool> =
        _tools.value.filter { it.serverId == serverId }

    /** 获取所有已启用的 server。 */
    fun getEnabledServers(): List<McpServer> = _servers.value.values.filter { it.isEnabled }

    /** 清空所有注册信息。 */
    fun clear() {
        toolIndex.clear()
        _servers.value = emptyMap()
        _tools.value = emptyList()
    }
}
