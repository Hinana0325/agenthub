package com.agentcontrolcenter.app.data.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.Sent,
    // H14: 协议要求字段名为 metadataJson: String，与 MessageEntity / Room 列对齐。
    // 原为 metadata: Map<String, String>，与协议 schema 不一致。
    val metadataJson: String = "{}",
    val attachmentType: String? = null, // "image", "file"
    val attachmentData: String? = null, // base64 or path
    val attachmentName: String? = null, // display name
    val reaction: String = "", // emoji reaction, e.g. "👍", "❤️"
    val replyToId: String? = null
) {
    // H14: 保留 metadata 作为计算属性，从 metadataJson 解析出 Map，
    // 供上层便捷访问，保持调用方 API 兼容。
    val metadata: Map<String, String>
        get() = try {
            gson.fromJson(metadataJson, METADATA_MAP_TYPE) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

    companion object {
        private val gson = Gson()
        private val METADATA_MAP_TYPE = object : TypeToken<Map<String, String>>() {}.type
    }
}

// H13: 增加 apiValue 计算属性，返回协议要求的 lowercase 角色名，
// 供 OpenAIHttpTransport 构建请求体时引用，避免硬编码 "user"/"assistant" 字符串。
enum class MessageRole {
    User, Assistant, System, Tool;

    val apiValue: String get() = name.lowercase()
}

enum class MessageStatus { Sending, Sent, Received, Failed }
