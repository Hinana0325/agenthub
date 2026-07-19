package com.agenthub.app.util

import com.agenthub.app.core.security.CryptoManager
import org.junit.Assert.*
import org.junit.Test

/**
 * CryptoManager 单元测试。
 * 验证 AES-256-GCM 加密/解密的正确性、边界情况和抗篡改能力。
 */
class CryptoManagerTest {

    private val testPassphrase = "my-secure-passphrase-2024"

    @Test
    fun `encrypt returns string with AH1 prefix`() {
        val encrypted = CryptoManager.encrypt("hello", testPassphrase)
        assertTrue("Expected AH1: prefix", encrypted.startsWith("AH1:"))
    }

    @Test
    fun `decrypt recovers original plaintext`() {
        val original = "Hello, AgentHub! 🔐"
        val encrypted = CryptoManager.encrypt(original, testPassphrase)
        val decrypted = CryptoManager.decrypt(encrypted, testPassphrase)
        assertEquals(original, decrypted)
    }

    @Test
    fun `decrypt returns null for wrong passphrase`() {
        val encrypted = CryptoManager.encrypt("secret", testPassphrase)
        val result = CryptoManager.decrypt(encrypted, "wrong-passphrase")
        assertNull("Decryption with wrong passphrase should return null", result)
    }

    @Test
    fun `decrypt returns null for non-AH1 payload`() {
        assertNull(CryptoManager.decrypt("not-encrypted", testPassphrase))
        assertNull(CryptoManager.decrypt("", testPassphrase))
    }

    @Test
    fun `decrypt returns null for tampered ciphertext`() {
        val encrypted = CryptoManager.encrypt("data", testPassphrase)
        // Flip a character in the Base64 body
        val tampered = encrypted.dropLast(2) + "XX"
        assertNull(CryptoManager.decrypt(tampered, testPassphrase))
    }

    @Test
    fun `encrypt produces different output each time due to random IV and salt`() {
        val e1 = CryptoManager.encrypt("same", testPassphrase)
        val e2 = CryptoManager.encrypt("same", testPassphrase)
        assertNotEquals("Two encryptions of the same text should differ", e1, e2)
        // But both should decrypt to the same value
        assertEquals("same", CryptoManager.decrypt(e1, testPassphrase))
        assertEquals("same", CryptoManager.decrypt(e2, testPassphrase))
    }

    @Test
    fun `isEncrypted correctly identifies AH1 payloads`() {
        assertTrue(CryptoManager.isEncrypted(CryptoManager.encrypt("x", testPassphrase)))
        assertFalse(CryptoManager.isEncrypted("plain text"))
        assertFalse(CryptoManager.isEncrypted(""))
    }

    @Test
    fun `handles empty and unicode content`() {
        val empty = ""
        val encrypted = CryptoManager.encrypt(empty, testPassphrase)
        assertEquals(empty, CryptoManager.decrypt(encrypted, testPassphrase))

        val unicode = "中文测试 🚀 Ñoño"
        val enc2 = CryptoManager.encrypt(unicode, testPassphrase)
        assertEquals(unicode, CryptoManager.decrypt(enc2, testPassphrase))
    }

    @Test
    fun `handles long content`() {
        val longText = "A".repeat(100_000)
        val encrypted = CryptoManager.encrypt(longText, testPassphrase)
        assertEquals(longText, CryptoManager.decrypt(encrypted, testPassphrase))
    }
}
