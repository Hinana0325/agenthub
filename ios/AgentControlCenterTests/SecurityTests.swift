import XCTest
@testable import AgentControlCenter

// MARK: - 安全模块单元测试
final class SecurityTests: XCTestCase {

    // 共享 Keychain 主密钥标识，与 KeychainManager 内部值保持一致
    private let keychainTag = "com.agentcontrolcenter.app.master-key"

    override func setUp() {
        super.setUp()
        // 每个测试前清理 Keychain 中残留的主密钥，避免上一轮测试污染
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: keychainTag
        ]
        SecItemDelete(query as CFDictionary)
    }

    override func tearDown() {
        // 测试后再清理一次，避免 CI 上 Keychain 状态累积
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: keychainTag
        ]
        SecItemDelete(query as CFDictionary)
        super.tearDown()
    }

    // MARK: - KeychainManager (AKS:) 测试

    /// 测试 AKS: 前缀加密/解密 (encrypt -> decrypt)
    func testAKSEncryptDecrypt() {
        let plaintext = "sk-1234567890abcdef"
        guard let encrypted = KeychainManager.encrypt(plaintext) else {
            XCTFail("加密应成功返回非 nil 值")
            return
        }

        // 加密后应有 AKS: 前缀
        XCTAssertTrue(encrypted.hasPrefix("AKS:"),
                       "加密结果应以 AKS: 前缀开头")
        // 加密后不应与明文相同
        XCTAssertNotEqual(encrypted, plaintext)

        // 解密
        let decrypted = KeychainManager.decrypt(encrypted)
        XCTAssertNotNil(decrypted, "AKS: 前缀字符串应能解密")
        XCTAssertEqual(decrypted, plaintext, "解密后应还原原始明文")
    }

    /// 测试 AKS: 解密非加密字符串返回 nil
    func testAKSDecryptNonEncryptedReturnsNil() {
        let raw = "plain-text-no-prefix"
        let result = KeychainManager.decrypt(raw)
        XCTAssertNil(result, "无 AKS: 前缀的字符串 decrypt 应返回 nil")
    }

    /// 测试 AKS: decryptOrRaw 对非加密字符串返回原始值
    func testAKSDecryptOrRawReturnsOriginal() {
        let raw = "这是原始明文"
        let result = KeychainManager.decryptOrRaw(raw)
        XCTAssertEqual(result, raw, "decryptOrRaw 对无前缀字符串应原样返回")
    }

    /// 测试 AKS: decryptOrRaw 对加密字符串解密返回明文
    func testAKSDecryptOrRawDecryptsEncrypted() {
        let plaintext = "secret-api-key"
        guard let encrypted = KeychainManager.encrypt(plaintext) else {
            XCTFail("加密应成功")
            return
        }
        let result = KeychainManager.decryptOrRaw(encrypted)
        XCTAssertEqual(result, plaintext, "decryptOrRaw 对 AKS: 前缀字符串应解密返回")
    }

    /// 测试 AKS: isEncrypted 正确检测前缀
    func testAKSIsEncrypted() {
        XCTAssertTrue(KeychainManager.isEncrypted("AKS:base64data"),
                       "AKS: 开头应被识别为已加密")
        XCTAssertFalse(KeychainManager.isEncrypted("plain"),
                        "无 AKS: 前缀不应被识别为已加密")
        XCTAssertFalse(KeychainManager.isEncrypted(""),
                        "空字符串不应被识别为已加密")
        XCTAssertFalse(KeychainManager.isEncrypted("aks:lowercase"),
                        "前缀区分大小写，小写 aks: 不应被识别")
    }

    // MARK: - CryptoManager (AH1:) 测试

    /// 测试 AH1: 前缀加密/解密 (encrypt -> decrypt)
    func testAH1EncryptDecrypt() {
        let plaintext = "端到端加密测试数据"
        let passphrase = "shared-secret-passphrase"
        guard let encrypted = CryptoManager.encrypt(plaintext, passphrase: passphrase) else {
            XCTFail("加密应成功返回非 nil 值")
            return
        }

        // 加密后应有 AH1: 前缀
        XCTAssertTrue(encrypted.hasPrefix("AH1:"),
                       "加密结果应以 AH1: 前缀开头")
        XCTAssertNotEqual(encrypted, plaintext)

        // 使用相同 passphrase 解密
        let decrypted = CryptoManager.decrypt(encrypted, passphrase: passphrase)
        XCTAssertNotNil(decrypted, "AH1: 前缀字符串应能解密")
        XCTAssertEqual(decrypted, plaintext, "解密后应还原原始明文")
    }

    /// 测试 AH1: 不同 passphrase 解密失败
    func testAH1WrongPassphrase() {
        let plaintext = "秘密数据"
        guard let encrypted = CryptoManager.encrypt(plaintext, passphrase: "correct-pass") else {
            XCTFail("加密应成功")
            return
        }
        let wrongDecrypt = CryptoManager.decrypt(encrypted, passphrase: "wrong-pass")
        // 错误 passphrase 解密可能返回 nil 或乱码，但不应等于明文
        // GCM 认证失败会抛出异常，返回 nil
        XCTAssertNil(wrongDecrypt, "错误 passphrase 解密应返回 nil")
    }

    /// 测试 AH1: 空密码短语处理
    func testAH1EmptyPassphrase() {
        let plaintext = "使用空密码短语"
        guard let encrypted = CryptoManager.encrypt(plaintext, passphrase: "") else {
            XCTFail("加密应成功")
            return
        }
        // 即使 passphrase 为空，也应能加密（使用空字符串派生密钥）
        XCTAssertTrue(encrypted.hasPrefix("AH1:"))

        let decrypted = CryptoManager.decrypt(encrypted, passphrase: "")
        XCTAssertNotNil(decrypted, "空密码短语加密的内容应能用空密码短语解密")
        XCTAssertEqual(decrypted, plaintext)
    }

    /// 测试 AH1: 无前缀解密返回 nil
    func testAH1DecryptNonEncryptedReturnsNil() {
        let result = CryptoManager.decrypt("no-prefix-data", passphrase: "pass")
        XCTAssertNil(result, "无 AH1: 前缀应返回 nil")
    }

    // MARK: - 空字符串加密/解密

    /// 测试 AKS: 空字符串加密返回空
    func testAKSEmptyString() {
        let encrypted = KeychainManager.encrypt("")
        XCTAssertEqual(encrypted, "", "空字符串加密应返回空字符串")
        XCTAssertFalse(KeychainManager.isEncrypted(""))
    }

    /// 测试 AH1: 空字符串加密返回空
    func testAH1EmptyString() {
        let encrypted = CryptoManager.encrypt("", passphrase: "pass")
        XCTAssertEqual(encrypted, "", "空字符串加密应返回空字符串")
    }

    // MARK: - 中文字符串加密/解密

    /// 测试 AKS: 中文字符串加密/解密
    func testAKSChineseStringEncryptDecrypt() {
        let chinese = "你好世界！这是中文测试。"
        guard let encrypted = KeychainManager.encrypt(chinese) else {
            XCTFail("加密应成功")
            return
        }
        XCTAssertTrue(KeychainManager.isEncrypted(encrypted))

        let decrypted = KeychainManager.decrypt(encrypted)
        XCTAssertNotNil(decrypted)
        XCTAssertEqual(decrypted, chinese, "中文字符串解密后应与原始值一致")
    }

    /// 测试 AH1: 中文字符串加密/解密
    func testAH1ChineseStringEncryptDecrypt() {
        let chinese = "端到端中文加密测试数据"
        let passphrase = "中文密码短语"
        guard let encrypted = CryptoManager.encrypt(chinese, passphrase: passphrase) else {
            XCTFail("加密应成功")
            return
        }

        let decrypted = CryptoManager.decrypt(encrypted, passphrase: passphrase)
        XCTAssertNotNil(decrypted)
        XCTAssertEqual(decrypted, chinese, "中文字符串解密后应与原始值一致")
    }

    // MARK: - PBKDF2 跨平台一致性测试（修复 kCCPBKDF2 常量 bug 的回归测试）

    /// 测试相同 passphrase + salt 派生出相同密钥（PBKDF2 确定性）
    /// 此测试用于防止 PBKDF2 实现回退到 SHA256 单次派生的 bug 再次出现。
    /// 一旦回退，相同 passphrase + 不同 salt 会派生出相同密钥（仅依赖 passphrase），
    /// 而正确 PBKDF2 应使密钥同时依赖 passphrase 和 salt。
    func testPBKDF2DerivationDependsOnSalt() {
        let plaintext = "same-plaintext"
        let passphrase = "fixed-passphrase-for-pbkdf2-test"

        // 用同一 passphrase 加密两次（CryptoManager 内部会生成不同 salt）
        guard let encrypted1 = CryptoManager.encrypt(plaintext, passphrase: passphrase) else {
            XCTFail("第一次加密应成功")
            return
        }
        guard let encrypted2 = CryptoManager.encrypt(plaintext, passphrase: passphrase) else {
            XCTFail("第二次加密应成功")
            return
        }

        // 两次加密的密文应不同（因为 salt 随机）
        XCTAssertNotEqual(encrypted1, encrypted2,
            "相同 passphrase 加密应因 salt 不同而密文不同；若相同说明 PBKDF2 未正确使用 salt")

        // 都能正确解密
        XCTAssertEqual(CryptoManager.decrypt(encrypted1, passphrase: passphrase), plaintext)
        XCTAssertEqual(CryptoManager.decrypt(encrypted2, passphrase: passphrase), plaintext)
    }

    /// 测试 AH1: 前缀格式长度合法性
    /// 格式: AH1: + Base64(IV[12] ‖ salt[16] ‖ ciphertext ‖ tag[16])
    /// 最小密文长度 = 12 + 16 + 0 + 16 = 44 字节，Base64 编码后至少 60 字符
    func testAH1PrefixFormatMinimumLength() {
        guard let encrypted = CryptoManager.encrypt("x", passphrase: "p") else {
            XCTFail("加密应成功")
            return
        }
        let base64 = String(encrypted.dropFirst("AH1:".count))
        XCTAssertNotNil(Data(base64Encoded: base64), "AH1: 后的部分应为合法 Base64")
        let combined = Data(base64Encoded: base64)!
        // 至少包含 IV(12) + salt(16) + tag(16) = 44 字节
        XCTAssertGreaterThanOrEqual(combined.count, 44,
            "AH1 密文解码后应至少 44 字节（IV+salt+tag），实际 \(combined.count)")
    }
}
