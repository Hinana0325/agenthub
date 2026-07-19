package com.agenthub.app.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
// Base64 编解码见 object 内的 b64Encode/b64Decode（兼容 JVM 单测）
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 硬件级加密管理器。
 *
 * 设计说明：
 *  - 使用 Android Keystore 生成并存储 AES-256-GCM 密钥，密钥材料由硬件安全模块
 *    (HSM/TEE/StrongBox) 保护，永不以明文形式离开安全区域。
 *  - 加密数据可安全存储在普通 DataStore / Room 中，即使设备被 root 或数据库
 *    被导出，攻击者也无法在没有 Keystore 授权的情况下解密。
 *  - 输出格式：`AKS:` + Base64( IV[12] ‖ ciphertext )，便于识别和迁移。
 *  - 线程安全：Cipher 实例按调用创建，无共享可变状态。
 *
 * 适用场景：加密存储 apiKey、e2eKey、token 等敏感凭据。
 */
object KeystoreManager {
    private const val TAG = "KeystoreManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "agenthub_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PREFIX = "AKS:"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // Base64 编解码：真机优先 android.util.Base64（API 24+ 可用）；
    // 本地 JVM 单测中 android.jar 为桩会抛 RuntimeException，此时回退 java.util.Base64（JVM/API 26+）。
    private fun b64Encode(bytes: ByteArray): String = try {
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    } catch (_: Throwable) {
        java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun b64Decode(s: String): ByteArray = try {
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
    } catch (_: Throwable) {
        java.util.Base64.getDecoder().decode(s)
    }

    /**
     * 获取或创建 Keystore 中的 AES 密钥。
     * 首次调用时自动生成，后续调用直接从 Keystore 读取。
     */
    private fun getOrCreateKey(): SecretKey {
        keyStore.getEntry(KEY_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false) // 后台服务需访问，不强制用户认证
            .setRandomizedEncryptionRequired(true) // 每次加密自动使用随机 IV
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * 加密明文，返回带 `AKS:` 前缀的可存储字符串。
     * 已加密的内容（带 `AKS:` 前缀）会被直接返回，避免双重加密。
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isBlank()) return plaintext
        if (plaintext.startsWith(PREFIX)) return plaintext // 已加密，跳过
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = iv + ciphertext
        return PREFIX + b64Encode(payload)
    }

    /**
     * 解密 [payload]。若不是本工具加密的内容（无 `AKS:` 前缀）或解密失败，
     * 返回 `null`，调用方可据此判断数据是否为旧版明文存储并决定迁移策略。
     */
    fun decrypt(payload: String): String? {
        if (!payload.startsWith(PREFIX)) return null
        return try {
            val raw = b64Decode(payload.removePrefix(PREFIX))
            val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = raw.copyOfRange(GCM_IV_LENGTH, raw.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 智能解密：若为 AKS 加密内容则解密，否则视为旧版明文直接返回。
     * 适用于从旧版 DataStore 明文存储平滑迁移到 Keystore 加密的场景。
     *
     * 区分两种情况：
     *  - 无 `AKS:` 前缀：旧版明文数据，直接返回以保持向后兼容；
     *  - 有 `AKS:` 前缀但解密失败：密文已损坏或被篡改，记录警告并返回空串，
     *    避免把损坏的密文当作明文展示给用户。
     */
    fun decryptOrRaw(value: String): String {
        if (value.isBlank()) return value
        // Old plaintext data without prefix - return as-is for backward compat
        if (!value.startsWith(PREFIX)) return value
        // Has prefix but decryption failed - corrupted or tampered data
        return decrypt(value) ?: run {
            android.util.Log.w(TAG, "Failed to decrypt value with prefix, data may be corrupted")
            ""
        }
    }

    /** 判断内容是否已通过 KeystoreManager 加密 */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}
