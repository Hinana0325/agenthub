package com.agentcontrolcenter.app.plugin.api

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * 插件可执行动作。三种真实动作类型：
 *  - [HttpCall]  : 通过 Ktor 发起真实 HTTP 请求（GET/POST...），用于天气、搜索、计算等。
 *  - [Broadcast] : 向系统发送 Android 广播 Intent，用于唤起其它 App / 系统能力。
 *  - [Workflow]  : 产出一段提示词，交由已连接的 Agent 执行（本地不执行任意代码，安全）。
 *
 * 序列化：以 `type` + `config`(JSON) 两个字段存储，便于持久化到 Room。
 */
sealed class PluginAction {
    abstract val type: String

    data class HttpCall(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        /** 请求体模板，`{query}` 在运行时被用户输入替换（仅 POST 等需要 body 时使用）。 */
        val bodyTemplate: String? = null
    ) : PluginAction() {
        override val type: String get() = "http"
    }

    data class Broadcast(
        val action: String,
        val extras: Map<String, String> = emptyMap()
    ) : PluginAction() {
        override val type: String get() = "broadcast"
    }

    data class Workflow(
        /** 发送给 Agent 的提示词模板，`{query}` 在运行时被用户输入替换。 */
        val promptTemplate: String
    ) : PluginAction() {
        override val type: String get() = "workflow"
    }

    companion object {
        private val gson = Gson()

        fun toConfig(action: PluginAction): String = when (action) {
            is HttpCall -> gson.toJson(
                JsonObject().apply {
                    addProperty("url", action.url)
                    addProperty("method", action.method)
                    addProperty("headers", gson.toJson(action.headers))
                    addProperty("bodyTemplate", action.bodyTemplate)
                }
            )
            is Broadcast -> gson.toJson(
                JsonObject().apply {
                    addProperty("action", action.action)
                    addProperty("extras", gson.toJson(action.extras))
                }
            )
            is Workflow -> gson.toJson(
                JsonObject().apply { addProperty("promptTemplate", action.promptTemplate) }
            )
        }

        fun fromConfig(type: String, config: String): PluginAction? = runCatching {
            val json = gson.fromJson(config, JsonObject::class.java) ?: return null
            when (type) {
                "http" -> HttpCall(
                    url = json.stringOrNull("url") ?: "",
                    method = json.stringOrNull("method") ?: "GET",
                    headers = runCatching {
                        gson.fromJson<Map<String, String>>(
                            json.stringOrNull("headers") ?: "{}",
                            object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                        )
                    }.getOrElse { emptyMap<String, String>() },
                    bodyTemplate = json.stringOrNull("bodyTemplate")
                )
                "broadcast" -> Broadcast(
                    action = json.stringOrNull("action") ?: "",
                    extras = runCatching {
                        gson.fromJson<Map<String, String>>(
                            json.stringOrNull("extras") ?: "{}",
                            object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                        )
                    }.getOrElse { emptyMap<String, String>() }
                )
                "workflow" -> Workflow(
                    promptTemplate = json.stringOrNull("promptTemplate") ?: ""
                )
                else -> null
            }
        }.getOrNull()

        /**
         * 安全地从 [JsonObject] 读取一个可选字符串字段。
         *
         * Gson 的 `JsonObject.get(key)` 在 key 存在但值为 `JsonNull` 时返回 `JsonNull`
         * 实例而非 Kotlin `null`，因此 `?.asString` 不会短路——对 `JsonNull` 调用
         * `asString` 会抛 `UnsupportedOperationException`。这里显式检查 `isJsonNull`
         * 并返回 Kotlin `null`，从而正确短路后续的 `?: ""` / `?: "GET"` 等回退逻辑。
         */
        private fun JsonObject.stringOrNull(key: String): String? =
            get(key)?.let { if (it.isJsonNull) null else it.asString }
    }
}
