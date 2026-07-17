package com.agenthub.app.data.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Message 数据模型及枚举测试。
 */
class MessageTest {

    @Test
    fun `message creation with all fields`() {
        val msg = Message(
            id = "msg-1",
            sessionId = "sess-1",
            role = MessageRole.User,
            content = "Hello agent",
            timestamp = 1700000000000L,
            status = MessageStatus.Sent,
            metadata = mapOf("key" to "value"),
            attachmentType = "image",
            attachmentData = "base64data",
            attachmentName = "photo.png",
            reaction = "👍"
        )
        assertEquals("msg-1", msg.id)
        assertEquals("sess-1", msg.sessionId)
        assertEquals(MessageRole.User, msg.role)
        assertEquals("Hello agent", msg.content)
        assertEquals(1700000000000L, msg.timestamp)
        assertEquals(MessageStatus.Sent, msg.status)
        assertEquals("value", msg.metadata["key"])
        assertEquals("image", msg.attachmentType)
        assertEquals("👍", msg.reaction)
    }

    @Test
    fun `message default values`() {
        val msg = Message(id = "m1", sessionId = "s1", role = MessageRole.Assistant, content = "hi")
        assertEquals(MessageStatus.Sent, msg.status)
        assertTrue(msg.metadata.isEmpty())
        assertNull(msg.attachmentType)
        assertNull(msg.attachmentData)
        assertNull(msg.attachmentName)
        assertEquals("", msg.reaction)
    }

    @Test
    fun `all message roles are defined`() {
        val roles = MessageRole.entries.map { it.name }
        assertTrue(roles.containsAll(listOf("User", "Assistant", "System", "Tool")))
        assertEquals(4, roles.size)
    }

    @Test
    fun `all message statuses are defined`() {
        val statuses = MessageStatus.entries.map { it.name }
        assertTrue(statuses.containsAll(listOf("Sending", "Sent", "Received", "Failed")))
        assertEquals(4, statuses.size)
    }

    @Test
    fun `valueOf parses roles correctly`() {
        assertEquals(MessageRole.User, MessageRole.valueOf("User"))
        assertEquals(MessageRole.Assistant, MessageRole.valueOf("Assistant"))
        assertEquals(MessageRole.System, MessageRole.valueOf("System"))
        assertEquals(MessageRole.Tool, MessageRole.valueOf("Tool"))
    }

    @Test
    fun `valueOf parses statuses correctly`() {
        assertEquals(MessageStatus.Sending, MessageStatus.valueOf("Sending"))
        assertEquals(MessageStatus.Sent, MessageStatus.valueOf("Sent"))
        assertEquals(MessageStatus.Received, MessageStatus.valueOf("Received"))
        assertEquals(MessageStatus.Failed, MessageStatus.valueOf("Failed"))
    }
}
