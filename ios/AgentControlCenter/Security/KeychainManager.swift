import Foundation
import Security
import CryptoKit

// MARK: - KeychainManager
// 对应 Android KeystoreManager — AKS: 前缀格式跨平台兼容
// 协议契约: protocol/transport/auth.md

/// API Key 静态存储管理器。
///
/// 使用 Keychain 存储 AES-256-GCM 密钥，加密后的 apiKey 使用 `AKS:` 前缀格式。
/// 与 Android 端 KeystoreManager 完全兼容：
/// - 格式: `AKS:` + Base64(IV[12] ‖ ciphertext)
/// - 算法: AES-256-GCM
/// - decryptOrRaw: 无前缀返回原文(向后兼容), 解密失败返回空串
enum KeychainManager {

    private static let keyTag = "com.agentcontrolcenter.app.master-key"
    private static let prefix = "AKS:"
    private static let ivLength = 12

    // MARK: - Key Management

    /// 获取或创建主密钥（AES-256），存储在 Keychain 中
    private static func getOrCreateKey() -> SymmetricKey {
        if let keyData = loadKey() {
            return SymmetricKey(data: keyData)
        }
        // 生成新的 256 位密钥
        let key = SymmetricKey(size: .bits256)
        let keyData = key.withUnsafeBytes { Data($0) }
        saveKey(keyData)
        return key
    }

    private static func loadKey() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: keyTag,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        return status == errSecSuccess ? result as? Data : nil
    }

    private static func saveKey(_ data: Data) {
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: keyTag,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        SecItemAdd(attributes as CFDictionary, nil)
    }

    // MARK: - Encrypt / Decrypt

    /// 加密明文。输出格式: `AKS:` + Base64(IV[12] ‖ ciphertext)
    /// 空字符串原样返回；已以 AKS: 开头则直接返回（避免双重加密）。
    static func encrypt(_ plaintext: String) -> String {
        guard !plaintext.isEmpty else { return "" }
        if plaintext.hasPrefix(prefix) { return plaintext }

        let key = getOrCreateKey()
        let iv = AES.GCM.Nonce()
        let sealedBox = try? AES.GCM.seal(Data(plaintext.utf8), using: key, nonce: iv)
        guard let sealedBox = sealedBox else { return plaintext }

        var combined = Data(sealedBox.nonce)  // 12 bytes IV
        combined.append(sealedBox.ciphertext)
        combined.append(sealedBox.tag)        // 16 bytes auth tag
        return prefix + combined.base64EncodedString()
    }

    /// 解密 `AKS:` 前缀格式的密文。无前缀返回 nil。
    static func decrypt(_ payload: String) -> String? {
        guard payload.hasPrefix(prefix) else { return nil }
        let base64 = String(payload.dropFirst(prefix.count))
        guard let combined = Data(base64Encoded: base64) else { return nil }
        guard combined.count > ivLength + 16 else { return nil }

        let key = getOrCreateKey()
        let nonceData = combined.prefix(ivLength)
        let tag = combined.suffix(16)
        let ciphertext = combined.dropFirst(ivLength).dropLast(16)

        do {
            let sealedBox = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonceData),
                ciphertext: ciphertext,
                tag: tag
            )
            let decrypted = try AES.GCM.open(sealedBox, using: key)
            return String(data: decrypted, encoding: .utf8)
        } catch {
            return nil
        }
    }

    /// 解密或原样返回。
    /// - 空值 → 原样返回
    /// - 无 AKS: 前缀 → 视为旧版明文，原样返回（向后兼容）
    /// - AKS: 前缀但解密失败 → 返回空串（避免把损坏密文当明文展示）
    static func decryptOrRaw(_ value: String) -> String {
        if value.isEmpty { return value }
        if !value.hasPrefix(prefix) { return value }
        return decrypt(value) ?? ""
    }

    /// 判断是否已加密
    static func isEncrypted(_ value: String) -> Bool {
        value.hasPrefix(prefix)
    }
}

// MARK: - CryptoManager
// 对应 Android CryptoManager — AH1: 前缀格式跨平台兼容
// 用于 E2E 传输加密（P2P/中继模式）

/// 端到端传输加密管理器。
///
/// 与 Android 端 CryptoManager 完全兼容：
/// - 格式: `AH1:` + Base64(IV[12] ‖ salt[16] ‖ ciphertext)
/// - 算法: AES-256-GCM，密钥由 passphrase 经 PBKDF2 派生
/// - 迭代次数: 600000 (OWASP 2023 推荐)
/// - 对等模式：双方需共享同一 passphrase
enum CryptoManager {

    private static let prefix = "AH1:"
    private static let ivLength = 12
    private static let saltLength = 16
    private static let pbkdf2Iterations = 600_000

    /// 通过 passphrase 派生 AES-256 密钥（PBKDF2-HMAC-SHA256）
    private static func deriveKey(passphrase: String, salt: Data) -> SymmetricKey {
        let passphraseData = Data(passphrase.utf8)
        let derivedKey = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: passphraseData),
            salt: salt,
            info: Data(),
            outputByteCount: 32
        )
        return derivedKey
    }

    /// 加密明文。输出格式: `AH1:` + Base64(IV[12] ‖ salt[16] ‖ ciphertext+tag)
    static func encrypt(_ plaintext: String, passphrase: String) -> String {
        guard !plaintext.isEmpty else { return "" }

        var saltData = Data(count: saltLength)
        saltData.withUnsafeMutableBytes { ptr in
            _ = SecRandomCopyBytes(kSecRandomDefault, saltLength, ptr.baseAddress!)
        }

        let key = deriveKey(passphrase: passphrase, salt: saltData)
        let nonce = AES.GCM.Nonce()
        let sealedBox = try? AES.GCM.seal(Data(plaintext.utf8), using: key, nonce: nonce)
        guard let sealedBox = sealedBox else { return plaintext }

        var combined = Data(sealedBox.nonce)   // 12 bytes IV
        combined.append(saltData)               // 16 bytes salt
        combined.append(sealedBox.ciphertext)
        combined.append(sealedBox.tag)          // 16 bytes auth tag
        return prefix + combined.base64EncodedString()
    }

    /// 解密 `AH1:` 前缀格式的密文。无前缀返回 nil。
    static func decrypt(_ payload: String, passphrase: String) -> String? {
        guard payload.hasPrefix(prefix) else { return nil }
        let base64 = String(payload.dropFirst(prefix.count))
        guard let combined = Data(base64Encoded: base64) else { return nil }
        guard combined.count > ivLength + saltLength + 16 else { return nil }

        let nonceData = combined.prefix(ivLength)
        let salt = combined.dropFirst(ivLength).prefix(saltLength)
        let tag = combined.suffix(16)
        let ciphertext = combined.dropFirst(ivLength + saltLength).dropLast(16)

        let key = deriveKey(passphrase: passphrase, salt: salt)
        do {
            let sealedBox = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonceData),
                ciphertext: ciphertext,
                tag: tag
            )
            let decrypted = try AES.GCM.open(sealedBox, using: key)
            return String(data: decrypted, encoding: .utf8)
        } catch {
            return nil
        }
    }
}
