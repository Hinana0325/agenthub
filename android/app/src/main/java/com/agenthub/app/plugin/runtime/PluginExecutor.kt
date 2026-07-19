package com.agenthub.app.plugin.runtime

import android.content.Context
import android.content.Intent
import com.agenthub.app.plugin.api.Plugin
import com.agenthub.app.plugin.api.PluginAction
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * 插件执行引擎：根据 [Plugin.action] 真实执行动作。
 *  - [PluginAction.HttpCall]  -> Ktor 发起 HTTP 请求并返回响应文本。
 *  - [PluginAction.Broadcast] -> 通过 [Context.sendBroadcast] 发送系统广播。
 *  - [PluginAction.Workflow]  -> 产出提示词，交由已连接 Agent 执行（[PluginResult.sendToAgent]=true）。
 *
 * 所有网络/IO 均在 IO 调度器执行。
 */
class PluginExecutor(private val context: Context) {

    private val client = HttpClient(OkHttp)

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
        return@withContext try {
            val response = client.request(url) {
                method = HttpMethod(action.method)
                action.headers.forEach { (k, v) -> headers.append(k, v) }
                if (body != null) setBody(body)
            }
            val text = response.bodyAsText().take(3000)
            PluginResult("HTTP ${response.status.value}\n${text}")
        } catch (e: Exception) {
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
}
