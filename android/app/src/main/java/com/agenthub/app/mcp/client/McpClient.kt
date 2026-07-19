package com.agenthub.app.mcp.client

import com.agenthub.app.mcp.model.JsonRpcError
import com.agenthub.app.mcp.model.JsonRpcRequest
import com.agenthub.app.mcp.model.JsonRpcResponse
import com.agenthub.app.mcp.model.McpContent
import com.agenthub.app.mcp.model.McpServer
import com.agenthub.app.mcp.model.McpServerCapabilities
import com.agenthub.app.mcp.model.McpTool
import com.agenthub.app.mcp.model.McpToolProperty
import com.agenthub.app.mcp.model.McpToolResult
import com.agenthub.app.mcp.model.McpToolSchema
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 客户端 — 通过 JSON-RPC 2.0 over HTTP 与 MCP Server 通信。
 *
 * Phase 5.4: 实现基本的生命周期管理：
 * 1. [connect]: 发送 initialize 请求，完成能力协商
 * 2. [listTools]: 发送 tools/list 请求，获取工具列表
 * 3. [callTool]: 发送 tools/call 请求，执行工具
 * 4. [disconnect]: 关闭连接
 *
 * 传输层使用 HTTP POST（SSE 模式留待后续扩展）。
 * 每个 [McpServer] 对应一个 [McpClient] 实例，由 [McpBridge] 管理生命周期。
 *
 * 协议参考：https://modelcontextprotocol.io/specification/2025-03-26
 */
@Singleton
class McpClient @Inject constructor() {

    private val gson = Gson()
    private val requestCounter = AtomicLong(1)

    // 每个 server 对应一个 HttpClient（隔离超时/重试配置）
    private val clients = mutableMapOf<String, HttpClient>()
    private val clientMutex = Mutex()

    /**
     * 连接到 MCP Server，完成 initialize 握手。
     *
     * @return 协商后的服务器能力，失败返回 null
     */
    suspend fun connect(server: McpServer): McpServerCapabilities? = withContext(Dispatchers.IO) {
        val client = getOrCreateClient(server)
        val request = JsonRpcRequest(
            id = nextId(),
            method = "initialize",
            params = mapOf(
                "protocolVersion" to "2025-03-26",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "AgentHub", "version" to "2.1.3")
            )
        )

        try {
            val response = sendRequest(client, server, request) ?: return@withContext null
            if (response.error != null) return@withContext null

            // 解析 capabilities
            val result = response.result as? Map<*, *> ?: return@withContext null
            val caps = result["capabilities"] as? Map<*, *> ?: return@withContext McpServerCapabilities()
            McpServerCapabilities(
                tools = (caps["tools"] != null),
                resources = (caps["resources"] != null),
                prompts = (caps["prompts"] != null),
                logging = (caps["logging"] != null)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 获取服务器提供的工具列表。
     */
    suspend fun listTools(server: McpServer): List<McpTool>? = withContext(Dispatchers.IO) {
        val client = getOrCreateClient(server)
        val request = JsonRpcRequest(
            id = nextId(),
            method = "tools/list"
        )

        try {
            val response = sendRequest(client, server, request) ?: return@withContext null
            if (response.error != null) return@withContext null

            val result = response.result as? Map<*, *> ?: return@withContext null
            val toolsRaw = result["tools"] as? List<*> ?: return@withContext emptyList()

            toolsRaw.mapNotNull { toolRaw ->
                val toolMap = toolRaw as? Map<*, *> ?: return@mapNotNull null
                val name = toolMap["name"] as? String ?: return@mapNotNull null
                val description = toolMap["description"] as? String ?: ""
                val schemaMap = toolMap["inputSchema"] as? Map<*, *> ?: emptyMap<String, Any?>()

                McpTool(
                    name = name,
                    description = description,
                    inputSchema = parseSchema(schemaMap),
                    serverId = server.id
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 调用工具。
     *
     * @param server 目标 MCP Server
     * @param toolName 工具名
     * @param arguments 调用参数
     * @return 工具执行结果
     */
    suspend fun callTool(
        server: McpServer,
        toolName: String,
        arguments: Map<String, Any?>
    ): McpToolResult? = withContext(Dispatchers.IO) {
        val client = getOrCreateClient(server)
        val request = JsonRpcRequest(
            id = nextId(),
            method = "tools/call",
            params = mapOf(
                "name" to toolName,
                "arguments" to arguments
            )
        )

        try {
            val response = sendRequest(client, server, request) ?: return@withContext null
            if (response.error != null) {
                return@withContext McpToolResult(
                    content = listOf(McpContent.Text("JSON-RPC Error ${response.error.code}: ${response.error.message}")),
                    isError = true
                )
            }

            val result = response.result as? Map<*, *> ?: return@withContext null
            val contentRaw = result["content"] as? List<*> ?: return@withContext McpToolResult(content = emptyList())
            val isError = result["isError"] as? Boolean ?: false

            val content = contentRaw.mapNotNull { item ->
                val itemMap = item as? Map<*, *> ?: return@mapNotNull null
                when (itemMap["type"] as? String) {
                    "text" -> McpContent.Text(itemMap["text"] as? String ?: "")
                    "image" -> McpContent.Image(
                        data = itemMap["data"] as? String ?: "",
                        mimeType = itemMap["mimeType"] as? String ?: "image/png"
                    )
                    "audio" -> McpContent.Audio(
                        data = itemMap["data"] as? String ?: "",
                        mimeType = itemMap["mimeType"] as? String ?: "audio/wav"
                    )
                    else -> null
                }
            }

            McpToolResult(content = content, isError = isError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpToolResult(
                content = listOf(McpContent.Text("Tool call failed: ${e.message ?: e.javaClass.simpleName}")),
                isError = true
            )
        }
    }

    /** 关闭并释放某个 server 的连接资源。 */
    suspend fun disconnect(serverId: String) {
        clientMutex.withLock {
            clients.remove(serverId)?.close()
        }
    }

    // ── 内部方法 ──

    private fun nextId(): Long = requestCounter.getAndIncrement()

    private suspend fun getOrCreateClient(server: McpServer): HttpClient {
        return clientMutex.withLock {
            clients.getOrPut(server.id) {
                HttpClient(OkHttp) {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 10_000
                        requestTimeoutMillis = 30_000
                        socketTimeoutMillis = 30_000
                    }
                }
            }
        }
    }

    private suspend fun sendRequest(
        client: HttpClient,
        server: McpServer,
        request: JsonRpcRequest
    ): JsonRpcResponse? {
        return withTimeoutOrNull(30_000L) {
            val body = gson.toJson(request)
            val response = client.post(server.transportUrl) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                server.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(body)
            }
            val responseText = response.bodyAsText()
            parseResponse(responseText)
        }
    }

    private fun parseResponse(text: String): JsonRpcResponse? {
        return try {
            val json = JsonParser.parseString(text).asJsonObject
            val id = json.get("id")?.asLong ?: return null
            val error = json.get("error")?.let {
                val errObj = it.asJsonObject
                JsonRpcError(
                    code = errObj.get("code")?.asInt ?: -1,
                    message = errObj.get("message")?.asString ?: "Unknown error"
                )
            }
            // result 是任意类型，这里转为 Map 便于后续处理
            val result: Map<String, Any?>? = json.get("result")?.let {
                gson.fromJson(it, object : TypeToken<Map<String, Any?>>() {}.type)
            }
            JsonRpcResponse(id = id, result = result, error = error)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSchema(schemaMap: Map<*, *>): McpToolSchema {
        val properties = (schemaMap["properties"] as? Map<*, *>)?.mapNotNull { (key, value) ->
            val propMap = value as? Map<*, *> ?: return@mapNotNull null
            val propName = key as? String ?: return@mapNotNull null
            propName to McpToolProperty(
                type = propMap["type"] as? String ?: "string",
                description = propMap["description"] as? String ?: "",
                enum = (propMap["enum"] as? List<*>)?.mapNotNull { it?.toString() }
            )
        }?.toMap() ?: emptyMap()

        val required = (schemaMap["required"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        return McpToolSchema(
            type = schemaMap["type"] as? String ?: "object",
            properties = properties,
            required = required
        )
    }
}
