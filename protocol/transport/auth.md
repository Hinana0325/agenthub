# AgentHub 认证与令牌规范

本文件定义 AgentHub 统一协议层中的认证机制、API Key 存储格式与端到端传输加密方案。认证是所有传输层（HTTP/SSE/WebSocket/MCP）的前置依赖，存储格式与加密方案的跨平台一致性是双端互操作的基础。本规范为 Android（Kotlin）与 iOS（Swift）双端共享的永久契约，`AKS:` 与 `AH1:` 前缀格式必须双端严格一致。

## 1. 认证机制总览

AgentHub 根据传输层使用三种认证机制，各机制独立但共用同一 apiKey 凭据：

| 序号 | 传输层 | 认证方式 | 触发时机 |
|------|--------|----------|----------|
| 1 | HTTP / SSE | `Authorization: Bearer {apiKey}` 请求头 | 每次请求 |
| 2 | WebSocket | 首帧 `{"type": "auth", "key": "{apiKey}"}` | 连接建立后立即发送 |
| 3 | MCP | `Authorization: Bearer {server.apiKey}` 请求头 | 每次 JSON-RPC POST |

### 1.1 HTTP / SSE 认证

- **载体**：HTTP 请求头。
- **格式**：`Authorization: Bearer {apiKey}`
- **`apiKey` 来源**：本地密钥库解密后得到（见第 2 节）；若存储为明文则直接使用。
- **覆盖范围**：Chat Completions 端点、探活端点（`/v1/models`）等所有 HTTP 请求。
- **失败处理**：服务端返回 401/403 时，客户端应提示鉴权失败，不得无限重试。

### 1.2 WebSocket 认证

- **载体**：WebSocket 文本帧（JSON）。
- **格式**：

```json
{"type": "auth", "key": "<apiKey>"}
```

- **发送时机**：连接 `onOpen` 后立即发送；若 `apiKey` 为空则跳过。
- **详见**：`websocket-protocol.md` 第 2 节。

### 1.3 MCP 认证

- **载体**：HTTP 请求头（JSON-RPC over HTTP）。
- **格式**：`Authorization: Bearer {server.apiKey}`
- **`server.apiKey`**：MCP server 配置中的 apiKey 字段，解密逻辑同上。
- **覆盖范围**：所有发往 MCP server 的 JSON-RPC POST 请求。

## 2. API Key 静态存储

API Key 在本地持久化时必须加密存储，双端采用统一的 `AKS:` 前缀格式以保证跨平台可读性。

### 2.1 存储格式

```
AKS:<Base64(IV ‖ ciphertext)>
```

- **前缀**：固定字符串 `AKS:`，标识 AgentHub Keystore 加密方案。
- **Base64 内容**：`IV[12] ‖ ciphertext` 的 Base64 编码。
  - `IV`：12 字节初始化向量，每次加密随机生成。
  - `ciphertext`：AES-256-GCM 密文（含 16 字节 GCM 认证标签）。
- **算法**：AES-256-GCM（无额外 PBKDF2，密钥由硬件密钥库保护）。

### 2.2 Android 实现（KeystoreManager）

- **密钥库**：AndroidKeyStore。
- **密钥别名**：`agenthub_master_key`。
- **硬件支持**：硬件 backed（TEE/StrongBox，设备支持时自动启用）。
- **密钥生成**：通过 `KeyGenerator` 在 AndroidKeyStore 中生成 AES-256 密钥，绑定设备且不可导出。
- **加密流程**：
  1. 从 AndroidKeyStore 取出 `agenthub_master_key` 对应的 SecretKey。
  2. 随机生成 12 字节 IV。
  3. `Cipher.getInstance("AES/GCM/NoPadding")` 加密明文。
  4. 拼接 `IV ‖ ciphertext`，Base64 编码，加 `AKS:` 前缀。
- **无 passphrase**：主密钥由硬件保护，无需用户输入密码派生。

### 2.3 iOS 实现（Keychain）

- **密钥库**：iOS Keychain。
- **等价要求**：必须使用与 Android 相同的 AES-256-GCM 算法与 `AKS:` 前缀格式。
- **密钥保护**：通过 Keychain 的 `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` 等访问控制保护主密钥，绑定设备。
- **跨平台兼容**：iOS 加密产物必须能被 Android 解密，反之亦然——前提是同一设备的同一主密钥。实际上由于主密钥绑定各自设备，跨设备读取无意义；`AKS:` 格式一致性的意义在于：任一端读取本端存储的密文时，能正确识别并解密。

### 2.4 decryptOrRaw(value) 函数

统一的解密入口函数，处理三种输入情形：

| 输入情形 | 判定条件 | 返回值 |
|----------|----------|--------|
| 已加密且解密成功 | 前缀为 `AKS:` 且 GCM 认证通过 | 解密后的明文 |
| 已加密但解密失败 | 前缀为 `AKS:` 但 GCM 认证失败或密钥不存在 | 空字符串 `""` |
| 未加密（明文，旧版兼容） | 无 `AKS:` 前缀 | 原始输入值 |

伪代码：

```
function decryptOrRaw(value):
    if value.startsWith("AKS:"):
        plaintext = tryDecrypt(value)
        if plaintext != null:
            return plaintext
        else:
            return ""    // 解密失败，返回空串，不回退到原文
    else:
        return value     // 旧版明文，原样返回
```

> 设计要点：解密失败时返回空串而非抛异常或回退原文，是为了避免将密文误当明文使用导致鉴权失败时难以定位。上层应根据空串提示用户重新配置 apiKey。

### 2.5 isEncrypted(value) 函数

判定存储值是否为加密格式：

```
function isEncrypted(value):
    return value.startsWith("AKS:")
```

- 返回 `true`：值为 `AKS:` 加密格式，需经 `decryptOrRaw` 解密后使用。
- 返回 `false`：值为明文（旧版或未配置加密），可直接使用。
- 仅做前缀判定，不验证密文完整性。

## 3. E2E 传输加密

E2E（End-to-End）加密为可选项，用于保护 WebSocket 等 P2P/中继传输场景下的消息内容机密性。

### 3.1 密文格式

```
AH1:<Base64(IV ‖ salt ‖ ciphertext)>
```

- **前缀**：固定字符串 `AH1:`，标识 AgentHub E2E 加密方案版本 1。
- **Base64 内容**：`IV[12] ‖ salt[16] ‖ ciphertext` 的 Base64 编码。

### 3.2 加密参数

| 参数 | 值 |
|------|-----|
| 算法 | AES-256-GCM |
| 密钥派生 | PBKDF2WithHmacSHA256 |
| 迭代次数 | 600000 |
| 盐长度 | 16 字节 |
| IV 长度 | 12 字节 |
| 密钥长度 | 256 位（32 字节） |
| 认证标签 | GCM 内置，16 字节 |

### 3.3 使用约束

- **双端共享 passphrase**：通信双方必须使用相同的 `e2eKey`（passphrase），否则解密失败。
- **每消息独立 salt/IV**：每次加密随机生成 salt 与 IV，保证语义安全。
- **应用场景**：仅用于 WebSocket 传输的消息 `content` 字段加密，不用于 HTTP/SSE 传输（后者依赖 TLS）。
- **详见**：`websocket-protocol.md` 第 7 节。

## 4. KeystoreManager 与 CryptoManager 的区分

AgentHub 存在两套加密体系，二者目的、密钥来源、使用场景均不同，不可混淆：

| 维度 | KeystoreManager（凭据存储） | CryptoManager（E2E 传输） |
|------|----------------------------|--------------------------|
| 目的 | 保护静态凭据（apiKey 等） | 保护传输中的消息内容 |
| 密文前缀 | `AKS:` | `AH1:` |
| 算法 | AES-256-GCM | AES-256-GCM |
| 密钥来源 | 硬件密钥库（AndroidKeyStore/Keychain）生成，设备绑定 | passphrase 经 PBKDF2 派生 |
| 密钥派生 | 无（直接使用硬件密钥） | PBKDF2WithHmacSHA256，600000 迭代 |
| passphrase | 无 | 有，双端共享 |
| salt | 无 | 16 字节，每消息随机 |
| IV | 12 字节，每次加密随机 | 12 字节，每次加密随机 |
| 密文结构 | `IV ‖ ciphertext` | `IV ‖ salt ‖ ciphertext` |
| 硬件 backed | 是（TEE/StrongBox） | 否（纯软件） |
| 使用场景 | 存储/读取 apiKey 至本地 | WebSocket 消息内容加密 |
| 跨设备 | 不可（密钥绑定设备） | 可（只要 passphrase 相同） |

> 关键区别：KeystoreManager 的密钥不可导出、绑定硬件，因此 `AKS:` 密文仅在原设备可解；CryptoManager 的密钥由 passphrase 派生，任意设备只要持有 passphrase 即可解密 `AH1:` 密文。

## 5. 密钥生命周期

### 5.1 apiKey 生命周期

```
[用户输入明文 apiKey]
        |
        v
[KeystoreManager.encrypt(plaintext)] --存入--> 本地存储 (AKS: 密文)
        |
        v
[读取时] KeystoreManager.decryptOrRaw(storedValue) --> 明文 apiKey
        |
        v
[注入传输层] HTTP Header / WebSocket auth 帧 / MCP Header
```

### 5.2 e2eKey（passphrase）生命周期

```
[用户配置 e2eKey]
        |
        v
[本地存储] (建议同样经 KeystoreManager 加密，但传输时不带前缀)
        |
        v
[发送消息时] CryptoManager.encrypt(content, e2eKey) --> AH1: 密文
        |
        v
[接收消息时] CryptoManager.decrypt(AH1: 密文, e2eKey) --> 明文 content
```

## 6. 安全注意事项

1. **apiKey 明文内存驻留最小化**：解密后的明文 apiKey 仅在发送请求时短暂使用，不应长期保存在内存或日志中。
2. **日志脱敏**：任何日志输出不得包含 apiKey 明文、`AH1:` 密文、passphrase。`Authorization` 头与 WebSocket auth 帧在日志中必须脱敏。
3. **GCM 认证失败处理**：解密时若 GCM 认证标签校验失败，必须丢弃该数据，不得返回部分解密结果。
4. **IV 不可复用**：同一密钥下 IV 必须每次随机且不可重复，否则破坏 GCM 安全性。
5. **passphrase 强度**：E2E 加密的 passphrase 应具有足够熵；PBKDF2 600000 迭代已提供较强抗暴力破解能力，但弱 passphrase 仍是风险。
6. **设备迁移**：由于 `AKS:` 密文绑定设备硬件，迁移至新设备时需用户重新输入 apiKey 重新加密存储。

## 7. 跨平台实现映射

| 维度 | Android（Kotlin） | iOS（Swift） |
|------|-------------------|--------------|
| 凭据存储密钥库 | AndroidKeyStore | Keychain |
| 主密钥别名/标识 | `agenthub_master_key` | Keychain item key（等价命名） |
| 主密钥算法 | `KeyGenerator` AES-256，硬件 backed | `SecKeyCreateRandomKey` 或等价 |
| 凭据加密算法 | `Cipher.getInstance("AES/GCM/NoPadding")` | `CryptoKit` AES-GCM / `CommonCrypto` |
| 凭据密文前缀 | `AKS:` | `AKS:`（必须一致） |
| decryptOrRaw 实现 | `KeystoreManager.decryptOrRaw(value)` | 等价 Keychain + AES-GCM 实现 |
| isEncrypted 实现 | `value.startsWith("AKS:")` | `value.hasPrefix("AKS:")` |
| E2E 加密类 | `CryptoManager` | 等价 `CryptoManager` |
| PBKDF2 实现 | `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` | `CCKeyDerivationPBKDF`（kCCPRFHmacAlgSHA256） |
| E2E 加密算法 | `Cipher.getInstance("AES/GCM/NoPadding")` | `CryptoKit` AES-GCM / `CommonCrypto` |
| E2E 密文前缀 | `AH1:` | `AH1:`（必须一致） |
| Base64 | `Base64.encodeToString` / `decode` | `base64EncodedString()` / `Data(base64Encoded:)` |
| Keychain 访问控制 | N/A | `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` |
| 硬件 backed 标志 | `setUserAuthenticationRequired` / `StrongBox` | Secure Enclave（设备支持时） |

## 8. 变更记录

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 1.0 | 2026-07-19 | 初始版本，从 Android 实现抽取并固化为双端契约；明确 `AKS:` 与 `AH1:` 跨平台格式一致性要求 |
