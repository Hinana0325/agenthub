package com.agenthub.app.mcp.model

/**
 * Phase 5.2: MCP 数据模型，基于 Model Context Protocol 2025-03-26 规范。
 *
 * 参考规范：https://modelcontextprotocol.io/specification/2025-03-26/server/tools
 */

/**
 * MCP Server 连接配置。
 *
 * @property id 唯一标识
 * @property name 人类可读名称
 * @property transportUrl 连接地址（stdio 命令路径 或 HTTP/SSE URL）
 * @property transportType 传输类型
 * @property apiKey 可选的认证 Token
 * @property isEnabled 是否启用
 * @property capabilities 协商后的服务器能力
 */
data class McpServer(
    val id: String,
    val name: String,
    val transportUrl: String,
    val transportType: McpTransportType = McpTransportType.SSE,
    val apiKey: String? = null,
    val isEnabled: Boolean = true,
    val capabilities: McpServerCapabilities = McpServerCapabilities()
)

enum class McpTransportType { STDIO, SSE, HTTP }

/**
 * 服务器在 initialize 握手后声明的能力。
 */
data class McpServerCapabilities(
    val tools: Boolean = false,
    val resources: Boolean = false,
    val prompts: Boolean = false,
    val logging: Boolean = false
)

/**
 * MCP 工具定义。
 *
 * 工具是 model-controlled 的，LLM 可自动发现并调用。
 *
 * @property name 唯一标识符
 * @property description 人类可读的功能描述
 * @property inputSchema JSON Schema 格式的参数定义
 * @property serverId 提供此工具的 MCP Server ID
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: McpToolSchema,
    val serverId: String
)

/**
 * 工具输入参数的 JSON Schema（简化版）。
 */
data class McpToolSchema(
    val type: String = "object",
    val properties: Map<String, McpToolProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

data class McpToolProperty(
    val type: String,
    val description: String = "",
    val enum: List<String>? = null
)

/**
 * 工具调用结果。
 *
 * @property content 内容列表（可包含多种类型）
 * @property isError 是否为执行错误（非协议错误）
 */
data class McpToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false
) {
    /** 提取所有文本内容拼接为单个字符串。 */
    fun asText(): String = content
        .filterIsInstance<McpContent.Text>()
        .joinToString("\n") { it.text }
}

/**
 * 工具返回的内容类型。
 */
sealed interface McpContent {
    data class Text(val text: String) : McpContent
    data class Image(val data: String, val mimeType: String) : McpContent
    data class Audio(val data: String, val mimeType: String) : McpContent
}

/**
 * JSON-RPC 2.0 请求封装。
 */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: Map<String, Any?> = emptyMap()
)

/**
 * JSON-RPC 2.0 响应封装。
 */
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long,
    val result: Any? = null,
    val error: JsonRpcError? = null
)

data class JsonRpcError(
    val code: Int,
    val message: String
)
