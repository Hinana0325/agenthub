package com.agentcontrolcenter.app.plugin.runtime

import android.content.Context
import android.content.Intent
import com.agentcontrolcenter.app.core.security.UrlValidator
import com.agentcontrolcenter.app.plugin.api.Plugin
import com.agentcontrolcenter.app.plugin.api.PluginAction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件执行引擎：根据 [Plugin.action] 真实执行动作。
 *  - [PluginAction.HttpCall]  -> Ktor 发起 HTTP 请求并返回响应文本。
 *  - [PluginAction.Broadcast] -> 通过 [Context.sendBroadcast] 发送系统广播。
 *  - [PluginAction.Workflow]  -> 产出提示词，交由已连接 Agent 执行（[PluginResult.sendToAgent]=true）。
 *
 * 所有网络/IO 均在 IO 调度器执行。
 *
 * Phase 5.1: 从普通 class 改为 [@Singleton]，通过 Hilt 注入 [@ApplicationContext]。
 * 便于测试时替换为 mock，且保证整个 App 生命周期内只有一个 HttpClient 实例。
 */
@Singleton
class PluginExecutor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * F24 修复：
     * 1. 安装 HttpTimeout（原 client 无超时，恶意/卡死端点会让协程永久挂起）。
     * 2. 所有 httpCall 入口经 UrlValidator.validate（与 OpenAIHttpTransport/WebSocketTransport 对齐）。
     */
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    /** 执行结果。[sendToAgent]=true 时 [content] 为应发送给 Agent 的提示词。 */
    data class PluginResult(val content: String, val sendToAgent: Boolean = false)

    suspend fun execute(plugin: Plugin, input: String = ""): PluginResult {
        val action = plugin.action ?: return PluginResult("Plugin \"${plugin.name}\" has no executable action.")
        return when (action) {
            is PluginAction.HttpCall -> httpCall(action, input)
            is PluginAction.Broadcast -> broadcast(action, input)
            is PluginAction.Workflow -> PluginResult(
                content = action.promptTemplate.replace("{query}", input),
                sendToAgent = true
            )
        }
    }

    private suspend fun httpCall(action: PluginAction.HttpCall, input: String): PluginResult = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(input, "UTF-8")
        val url = action.url.replace("{query}", query)
        val body = action.bodyTemplate?.replace("{query}", input)
        // F24 修复：插件 url 来自用户/marketplace 配置，未校验可触发 SSRF
        // （如 http://169.254.169.254/...）。allowLocalhost=false：插件不应访问本地服务。
        if (UrlValidator.validate(url, allowLocalhost = false) == null) {
            return@withContext PluginResult("Error: URL not allowed (blocked by UrlValidator)")
        }
        return@withContext try {
            val response = client.request(url) {
                method = HttpMethod(action.method)
                // F19 对齐：过滤敏感 header（Authorization/Cookie/Host 等）
                action.headers.forEach { (k, v) ->
                    if (k.lowercase() !in BLOCKED_HEADERS) headers.append(k, v)
                }
                if (body != null) setBody(body)
            }
            val text = response.bodyAsText().take(3000)
            PluginResult("HTTP ${response.status.value}\n${text}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PluginResult("Request failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun broadcast(action: PluginAction.Broadcast, input: String): PluginResult {
        return try {
            val intent = Intent(action.action).apply {
                action.extras.forEach { (k, v) -> putExtra(k, v.replace("{query}", input)) }
            }
            context.sendBroadcast(intent)
            PluginResult("Broadcast sent: ${action.action}")
        } catch (e: Exception) {
            PluginResult("Broadcast failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private companion object {
        /** F19/F24 对齐：插件 HTTP 请求禁止设置的敏感 header（小写匹配）。 */
        val BLOCKED_HEADERS = setOf(
            "authorization", "proxy-authorization",
            "cookie", "set-cookie", "cookie2", "set-cookie2",
            "host", "content-length", "connection",
            "upgrade", "transfer-encoding", "te",
            "trailer", "expect",
            "www-authenticate", "proxy-authenticate",
            "x-api-key", "x-auth-token", "x-session-token"
        )
    }
}
