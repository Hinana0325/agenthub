import Foundation
import Security
import CryptoKit
import CommonCrypto
import os

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

    // M-4 修复：通过 os.Logger 在 Keychain 写入失败时输出 error 级日志，
    // 便于生产环境监控 Keychain 不可用 / entitlement 缺失等问题
    private static let logger = Logger(subsystem: "com.agentcontrolcenter.app.ios", category: "KeychainManager")

    // CI-fix: 当 Keychain 不可用（CI 模拟器无 entitlement / errSecMissingEntitlement）
    // 时，saveKey 静默失败，loadKey 返回 nil，getOrCreateKey 会生成新密钥 —
    // 同进程内 encrypt/decrypt 用不同密钥，AES-GCM 认证失败导致 decrypt 永远返回 nil。
    // 此 in-memory 缓存作为 Keychain 不可用时的降级：保证同一进程内 encrypt 与
    // decrypt 使用同一密钥。生产环境（真机 / 有 entitlement 的模拟器）Keychain 写
    // 入成功，不会进入此分支。`nonisolated(unsafe)` 因为静态枚举本身就是进程级单例。
    nonisolated(unsafe) private static var inMemoryFallbackKey: Data?

    // M-5 修复：与 Android @Synchronized 对齐，用 NSLock 保护 getOrCreateKey 全过程，
    // 避免并发调用导致同一进程内生成两把不同的密钥（旧实现 loadKey 与 saveKey 之间存在
    // TOCTOU 窗口：线程 A loadKey→nil → 生成 key1 → saveKey；线程 B 在此期间也
    // loadKey→nil → 生成 key2 → saveKey 覆盖 key1，使用 key1 加密的密文永远无法解密）。
    private static let keySyncLock = NSLock()

    // MARK: - Key Management

    /// 获取或创建主密钥（AES-256），存储在 Keychain 中
    private static func getOrCreateKey() -> SymmetricKey {
        // M-5 修复：用 NSLock 包裹全过程，与 Android @Synchronized 对齐
        keySyncLock.lock()
        defer { keySyncLock.unlock() }

        if let keyData = loadKey() {
            return SymmetricKey(data: keyData)
        }
        // 生成新的 256 位密钥
        let key = SymmetricKey(size: .bits256)
        let keyData = key.withUnsafeBytes { Data($0) }
        saveKey(keyData)
        // CI-fix: Keychain 写入失败时回退到 in-memory 缓存，保证同一进程内
        // encrypt/decrypt 使用相同密钥（避免 CI 模拟器无 entitlement 导致测试失败）
        if inMemoryFallbackKey == nil {
            inMemoryFallbackKey = keyData
        } else if inMemoryFallbackKey != keyData {
            // M-4 修复：loadKey 返回 nil 但 inMemoryFallbackKey 已存在且与新密钥不同，
            // 说明此前已有其他密钥在使用 — 此时新生成的密钥将无法解密旧密文。
            // 输出生产环境告警，便于诊断"加密后无法解密"类问题。
            logger.error("Keychain loadKey returned nil but inMemoryFallbackKey already exists with a different value — newly generated key may not decrypt previously encrypted payloads")
        }
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
        if status == errSecSuccess, let data = result as? Data {
            return data
        }
        // CI-fix: Keychain 不可用时回退到 in-memory 缓存
        return inMemoryFallbackKey
    }

    private static func saveKey(_ data: Data) {
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: keyTag,
            kSecValueData as String: data,
            // C2: 与 auth.md / SECURITY.md 对齐——AfterFirstUnlock 允许前台服务在设备
            // 首次解锁后的任意时刻（含锁屏）读取 apiKey，与 Android setUserAuthenticationRequired(false) 语义一致。
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        // 先尝试添加；若 key 已存在（errSecDuplicateItem），则更新
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecDuplicateItem {
            SecItemUpdate(
                [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: keyTag] as CFDictionary,
                [kSecValueData as String: data] as CFDictionary
            )
        } else if status != errSecSuccess {
            // M-4 修复：SecItemAdd 非 errSecDuplicateItem 的失败状态通过 os.Logger 记录 error 级日志，
            // 便于生产环境监控 Keychain 不可用（errSecMissingEntitlement / errSecAuthFailed 等）
            logger.error("SecItemAdd failed with status \(status.rawValue, privacy: .public) — falling back to in-memory key")
        }
        // CI-fix: 无论 SecItemAdd 是否成功，都同步更新 in-memory 缓存
        // （写入失败时下次 loadKey 仍可拿到同一密钥）
        inMemoryFallbackKey = data
    }

    // MARK: - Encrypt / Decrypt

    /// 加密明文。输出格式: `AKS:` + Base64(IV[12] ‖ ciphertext)
    /// 空字符串原样返回；已以 AKS: 开头则直接返回（避免双重加密）。
    /// 加密失败返回 nil（不再静默回退明文，避免 apiKey 以明文落入持久化层）。
    static func encrypt(_ plaintext: String) -> String? {
        guard !plaintext.isEmpty else { return "" }
        if plaintext.hasPrefix(prefix) { return plaintext }

        let key = getOrCreateKey()
        let iv = AES.GCM.Nonce()
        guard let sealedBox = try? AES.GCM.seal(Data(plaintext.utf8), using: key, nonce: iv) else {
            return nil
        }

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

    // MARK: - Passphrase Storage（E2E 密码短语专用，不进入 UserDefaults）

    private static let passphraseTag = "com.agentcontrolcenter.app.e2e-passphrase"

    /// 读取 E2E 密码短语。未设置时返回空串。
    static func loadPassphrase() -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: passphraseTag,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess,
              let data = result as? Data,
              let str = String(data: data, encoding: .utf8) else {
            return ""
        }
        return str
    }

    /// 保存 E2E 密码短语到 Keychain（ThisDeviceOnly，不随 iCloud 备份迁移）。
    static func savePassphrase(_ value: String) {
        let data = Data(value.utf8)
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: passphraseTag,
            kSecValueData as String: data,
            // C2: 同 saveKey，passphrase 也用 AfterFirstUnlock 以支持后台 E2E 加密。
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecDuplicateItem {
            SecItemUpdate(
                [kSecClass as String: kSecClassGenericPassword,
                 kSecAttrAccount as String: passphraseTag] as CFDictionary,
                [kSecValueData as String: data] as CFDictionary
            )
        }
    }

    /// 清除 E2E 密码短语。
    static func clearPassphrase() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: passphraseTag
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Injectable Provider（测试可替换）

    /// 可注入的存储提供者。生产环境使用 `DefaultKeychainStorage`（内部转发到上面的静态方法）；
    /// 单元测试可在 `setUp()` 替换为内存实现（如 `InMemoryKeychainStorage`），
    /// 避免污染 CI 上的真实 Keychain 并允许确定性断言。
    @MainActor static var provider: KeychainStoring = DefaultKeychainStorage()
}

// MARK: - KeychainStoring Protocol (Test Injectable)
// 对应 Android KeychainStorage interface（Hilt 注入点）

/// Keychain 存储协议。提取该协议便于在单元测试中替换为内存实现。
protocol KeychainStoring: Sendable {
    /// 加密明文，返回 `AKS:` 前缀密文；失败返回 nil
    func encrypt(_ plaintext: String) -> String?
    /// 解密 `AKS:` 前缀密文；非前缀返回 nil
    func decrypt(_ payload: String) -> String?
    /// 解密或原样返回（向后兼容旧明文）
    func decryptOrRaw(_ value: String) -> String
    /// 判断是否已加密
    func isEncrypted(_ value: String) -> Bool
    /// 读取 E2E 密码短语；未设置返回空串
    func loadPassphrase() -> String
    /// 保存 E2E 密码短语到 Keychain
    func savePassphrase(_ value: String)
    /// 清除 E2E 密码短语
    func clearPassphrase()
}

/// 默认 `KeychainStoring` 实现：转发到 `KeychainManager` 静态方法。
/// 生产代码无需显式使用该类型，仅作为 `KeychainManager.provider` 的默认值。
struct DefaultKeychainStorage: KeychainStoring, Sendable {
    func encrypt(_ plaintext: String) -> String? { KeychainManager.encrypt(plaintext) }
    func decrypt(_ payload: String) -> String? { KeychainManager.decrypt(payload) }
    func decryptOrRaw(_ value: String) -> String { KeychainManager.decryptOrRaw(value) }
    func isEncrypted(_ value: String) -> Bool { KeychainManager.isEncrypted(value) }
    func loadPassphrase() -> String { KeychainManager.loadPassphrase() }
    func savePassphrase(_ value: String) { KeychainManager.savePassphrase(value) }
    func clearPassphrase() { KeychainManager.clearPassphrase() }
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

    /// PBKDF2 派生失败错误。
    private enum KeyDerivationError: Error { case derivationFailed(OSStatus) }

    /// 通过 passphrase 派生 AES-256 密钥（PBKDF2-HMAC-SHA256）
    /// 与 Android 端 SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") 完全兼容
    /// 600000 轮迭代，输出 32 字节密钥
    ///
    /// F15 修复：PBKDF2 失败时抛错而非返回零密钥。原实现回退 `SymmetricKey(size: .bits256)`
    /// 会产生一个全零密钥，调用方（encrypt/decrypt）无法区分「派生成功」与「派生失败」，
    /// 结果 encrypt 会用零密钥加密成功并返回密文（无人可解），decrypt 会静默返回 nil，
    /// E2E 加密在派生失败时静默失效。修复后失败立即抛错，由 encrypt/decrypt 转为 nil 返回。
    private static func deriveKey(passphrase: String, salt: Data) throws -> SymmetricKey {
        let passphraseData = Data(passphrase.utf8)
        var derivedKeyBytes = [UInt8](repeating: 0, count: 32)

        // 第 1 个参数必须是 kCCPBKDF2（值=2），CCPseudoRandomAlgorithm 才用 kCCPRFHmacAlgSHA256
        // 修复前误传 CCPBKDFAlgorithm(kCCPRFHmacAlgSHA256)（值=3，无效），导致 PBKDF2 必然失败，
        // 回退为单次 SHA256(passphrase)，与 Android PBKDF2 派生的密钥不同，AH1 密文跨平台不可解。
        //
        // CI-fix: Xcode 16.4 下 CommonCrypto 的 `CCKeyDerivationPBKDF` Swift overlay 要求
        // `algorithm` 为 `CCPBKDFAlgorithm`（UInt32）、`prf` 为 `CCPseudoRandomAlgorithm`
        // （UInt32），而 `kCCPBKDF2` / `kCCPRFHmacAlgSHA256` 在 C 头文件中是 Int 字面量枚举值，
        // 需显式构造为对应类型。同时移除原代码中多余的 `nil, 0` 两个参数（签名只接受 9 个参数，
        // derivedKey/derivedKeyLen 应直接为 `&derivedKeyBytes` / `32`）。
        let status = passphraseData.withUnsafeBytes { passphraseBytes in
            salt.withUnsafeBytes { saltBytes in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    passphraseBytes.baseAddress?.assumingMemoryBound(to: CChar.self),
                    passphraseData.count,
                    saltBytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(pbkdf2Iterations),
                    &derivedKeyBytes,
                    32
                )
            }
        }

        guard status == kCCSuccess else {
            throw KeyDerivationError.derivationFailed(status)
        }

        return SymmetricKey(data: derivedKeyBytes)
    }

    /// 加密明文。输出格式: `AH1:` + Base64(IV[12] ‖ salt[16] ‖ ciphertext+tag)
    /// 加密失败返回 nil（不再静默回退明文，避免 E2E 加密失效时裸传敏感内容）。
    static func encrypt(_ plaintext: String, passphrase: String) -> String? {
        guard !plaintext.isEmpty else { return "" }

        var saltData = Data(count: saltLength)
        // F15 修复：原 `ptr.baseAddress!` 强解包，空 buffer 场景会崩；改为可选绑定。
        saltData.withUnsafeMutableBytes { ptr in
            guard let base = ptr.baseAddress else { return }
            _ = SecRandomCopyBytes(kSecRandomDefault, saltLength, base)
        }

        // F15 修复：派生失败转为 nil（不再用零密钥加密出无人可解的密文）
        let key: SymmetricKey
        do {
            key = try deriveKey(passphrase: passphrase, salt: saltData)
        } catch {
            return nil
        }
        let nonce = AES.GCM.Nonce()
        guard let sealedBox = try? AES.GCM.seal(Data(plaintext.utf8), using: key, nonce: nonce) else {
            return nil
        }

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

        // F15 修复：派生失败转为 nil（不再用零密钥尝试解密）
        let key: SymmetricKey
        do {
            key = try deriveKey(passphrase: passphrase, salt: salt)
        } catch {
            return nil
        }
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
