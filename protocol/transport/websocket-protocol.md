# Agent Control Center WebSocket 双向传输协议规范

本文件定义 Agent Control Center 统一协议层中基于 WebSocket 的双向实时通信协议，用于需要全双工交互的场景（如 P2P/中继对话、低延迟增量响应）。与 HTTP/SSE 传输不同，WebSocket 传输下会话状态由服务端维护，客户端无需重放历史。本规范为 Android（Kotlin）与 iOS（Swift）双端共享的永久契约。

## 1. URL 构造

WebSocket 连接地址由 HTTP `serverUrl` 转换而来：

| 原始协议 | 转换后协议 | 说明 |
|----------|------------|------|
| `http://` | `ws://` | 明文 WebSocket |
| `https://` | `wss://` | 加密 WebSocket（TLS） |

构造规则：

1. 将 `serverUrl` 的协议前缀按上表替换。
2. 在替换后的地址末尾追加 `/ws`。
3. 其余路径、端口、host 部分保持不变。

示例：

- `https://api.agentcontrolcenter.io` → `wss://api.agentcontrolcenter.io/ws`
- `http://192.168.1.10:8080/v1` → `ws://192.168.1.10:8080/v1/ws`

> 若 `serverUrl` 末尾带斜杠，应在追加 `/ws` 前去除尾部斜杠，避免出现 `//ws`。

## 2. 鉴权帧

连接建立（`onOpen`）后，若 `apiKey` 非空，客户端必须**立即**发送鉴权帧；若 `apiKey` 为空则跳过此帧。

```json
{"type": "auth", "key": "<apiKey>"}
```

字段说明：

- **`type`**：固定字符串 `"auth"`。
- **`key`**：apiKey 明文（由本地密钥库解密后得到）。服务端据此完成会话级鉴权。

> 鉴权帧是 WebSocket 传输下的唯一鉴权手段，不使用 HTTP Header。服务端应在收到鉴权帧并校验通过后才接受后续业务帧。

## 3. 客户端→服务端消息帧

用户消息以如下 JSON 帧发送：

```json
{"type": "message", "sessionId": "<sessionId>", "content": "<content>", "role": "User"}
```

字段说明：

- **`type`**：固定字符串 `"message"`。
- **`sessionId`**：会话标识符，用于服务端关联会话状态。新建会话使用新的 UUID。
- **`content`**：消息内容字符串。其值取决于是否启用 E2E 加密：
  - **E2E 加密启用**（提供 `e2eKey`）：`content` 为加密后的密文，采用 `AH1:` 前缀格式（详见第 7 节）。
  - **E2E 加密未启用**：`content` 为明文。
- **`role`**：固定字符串 `"User"`，对应 `MessageRole.User` 枚举名。客户端发送的消息角色恒为 `"User"`，不发送 `assistant`/`system`/`tool`。

> 注意：`role` 字段使用枚举名（PascalCase 首字母大写 `"User"`），与 HTTP API 中 `messages` 数组使用的小写 `"user"` 不同。这是历史实现差异，双端必须保持一致。

## 4. 服务端→客户端帧类型

服务端可下发以下四类帧：

### 4.1 消息帧（message）

```json
{"type": "message", "content": "...", "delta": true, "sessionId": "..."}
```

- **`content`**：响应内容。若启用 E2E 加密，此字段为 `AH1:` 前缀密文，客户端需解密后使用。
- **`delta`**：布尔值。
  - `true`：增量帧，仅包含本次新增文本片段。
  - `false`：全量帧，包含完整响应文本。
- **`sessionId`**：回显会话标识符，客户端应校验与当前会话一致。

映射为 `AgentEvent.MessageReceived(content, isDelta=delta)`。

### 4.2 响应帧（response）

```json
{"type": "response", "content": "...", "delta": true, "sessionId": "..."}
```

- 语义与 `message` 帧完全相同，是 `message` 的别名。
- 客户端必须同时支持 `type: "message"` 与 `type: "response"`，处理逻辑一致。
- 此别名存在的目的是兼容不同服务端实现，客户端不应假定只收到其中一种。

### 4.3 错误帧（error）

```json
{"type": "error", "message": "..."}
```

- **`message`**：人类可读的错误描述字符串。
- 客户端收到后映射为 `AgentEvent.Error`，并携带错误描述。
- 错误帧不一定意味着连接断开，客户端应继续维持连接，除非服务端主动关闭。

### 4.4 心跳帧（ping）

```json
{"type": "ping"}
```

- 服务端心跳，用于保活与连接探测。
- 客户端**必须忽略**此帧，不产生任何业务事件，也**无需**回复 `pong`。
- 客户端可自行实现应用层心跳（可选），但协议不强制。

## 5. 会话管理

- **服务端有状态**：服务端通过 `sessionId` 维护完整对话历史与上下文。
- **客户端不重放历史**：客户端每条消息帧仅包含当前用户输入，不附带历史消息。这与 HTTP/SSE 传输（客户端维护滑动窗口）形成对比。
- **新建会话**：生成新的 `sessionId`（建议 UUID v4），服务端据此创建全新会话上下文。
- **会话隔离**：不同 `sessionId` 之间状态完全隔离，互不影响。
- **会话生命周期**：由服务端决定，客户端无需显式销毁；如需结束会话，关闭 WebSocket 连接即可，服务端按自身策略清理。

## 6. 重连策略

连接异常断开时，客户端按以下策略自动重连：

| 参数 | 值 | 说明 |
|------|-----|------|
| 最大重试次数 | 3 | 超过后放弃重连，派发 `AgentEvent.Error` |
| 初始退避 | 1000ms | 首次重连前的等待时间 |
| 退避倍率 | ×2 | 每次失败后退避时间翻倍 |
| 退避上限 | 30000ms | 单次等待不超过此值 |

退避序列示例：`1000ms → 2000ms → 4000ms → 8000ms → 16000ms → 30000ms`（封顶）。

- 每次进入重连等待时，派发 `AgentEvent.Reconnecting`，携带当前重试次数与下次重连等待时间。
- 重连成功后，必须重新发送鉴权帧（若 `apiKey` 非空），并恢复原有 `sessionId` 以续接会话。
- 重连期间，客户端应缓存用户后续输入，待重连成功后按序发送。

## 7. E2E 端到端加密

WebSocket 传输支持可选的端到端加密，用于保护 P2P/中继场景下的消息内容机密性。

### 7.1 密文格式

```
AH1:<Base64(IV ‖ salt ‖ ciphertext)>
```

- **前缀**：固定字符串 `AH1:`，用于标识加密方案版本与区分明文。
- **Base64 内容**：`IV[12] ‖ salt[16] ‖ ciphertext` 的 Base64 编码。
  - `IV`：12 字节初始化向量，每次加密随机生成。
  - `salt`：16 字节盐值，每次加密随机生成。
  - `ciphertext`：AES-256-GCM 密文（含 GCM 认证标签）。

### 7.2 加密参数

| 参数 | 值 |
|------|-----|
| 算法 | AES-256-GCM |
| 密钥派生 | PBKDF2WithHmacSHA256 |
| 迭代次数 | 600000 |
| 盐长度 | 16 字节 |
| IV 长度 | 12 字节 |
| 密钥长度 | 256 位（32 字节） |
| 认证标签 | GCM 内置，16 字节 |

### 7.3 密钥派生流程

1. 通信双方共享同一 passphrase（`e2eKey`）。
2. 对每条消息，随机生成 16 字节 salt。
3. 使用 PBKDF2WithHmacSHA256 从 passphrase + salt 派生 32 字节密钥，迭代 600000 次。
4. 随机生成 12 字节 IV。
5. 以派生密钥 + IV 对明文执行 AES-256-GCM 加密，得到密文（含认证标签）。
6. 拼接 `IV ‖ salt ‖ ciphertext`，Base64 编码后加 `AH1:` 前缀。

### 7.4 解密流程

1. 校验前缀为 `AH1:`，去除前缀。
2. Base64 解码，按 `[0:12]` 取 IV、`[12:28]` 取 salt、`[28:]` 取 ciphertext。
3. 用 salt + passphrase 经 PBKDF2 派生密钥。
4. 用密钥 + IV 解密 ciphertext，GCM 认证失败则视为篡改，丢弃该消息。
5. 解密后的明文即为原始 `content`。

> 重要区分：此 E2E 加密（CryptoManager）与本地凭据存储加密（KeystoreManager）目的不同。前者保护传输中的消息内容（passphrase 派生密钥，双端共享），后者保护静态凭据（硬件 backed，无 passphrase）。详见 `auth.md`。

## 8. 消息流示例

完整交互流程示例（含 E2E 加密）：

```
客户端                                服务端
  |                                     |
  |--- connect wss://.../ws ----------->|
  |<--- connection open ----------------|
  |--- {"type":"auth","key":"***"} ---->|
  |<--- (auth ok) ----------------------|
  |--- {"type":"message",              |
  |     "sessionId":"abc-123",         |
  |     "content":"AH1:<base64...>",   |
  |     "role":"User"} ---------------->|
  |<--- {"type":"message",             |
  |     "content":"AH1:<base64...>",   |
  |     "delta":true,                  |
  |     "sessionId":"abc-123"} ---------|
  |<--- {"type":"message",             |
  |     "content":"AH1:<base64...>",   |
  |     "delta":true,                  |
  |     "sessionId":"abc-123"} ---------|
  |<--- {"type":"message",             |
  |     "content":"AH1:<base64...>",   |
  |     "delta":false,                 |
  |     "sessionId":"abc-123"} ---------|
  |<--- {"type":"ping"} ---------------| (客户端忽略)
  |                                     |
```

## 9. 跨平台实现映射

| 维度 | Android（Kotlin） | iOS（Swift） |
|------|-------------------|--------------|
| WebSocket 客户端 | OkHttp `WebSocket` / Java-WebSocket | `URLSessionWebSocketTask` |
| URL 构造 | `serverUrl.replace("http://","ws://").replace("https://","wss://") + "/ws"` | 同左，`String.replacingOccurrences` |
| 连接回调 | `WebSocketListener.onOpen/onMessage/onClosed/onFailure` | `URLSessionWebSocketTask.receive` / 闭包 |
| 鉴权帧发送 | `webSocket.send(json)` 于 `onOpen` 中 | `webSocketTask.send(.string(json))` 于 open 后 |
| 帧序列化 | Gson / Moshi | `JSONEncoder` / `JSONSerialization` |
| 帧反序列化 | `JSONObject(text)` 按字段取值 | `JSONSerialization.jsonObject` |
| E2E 加密 | `javax.crypto.Cipher`（AES/GCM/NoPadding）+ `SecretKeyFactory`（PBKDF2WithHmacSHA256） | `CryptoKit` / `CommonCrypto`（CCKeyDerivationPBKDF + AES-GCM） |
| Base64 | `Base64.encodeToString` / `decode` | `Data(base64Encoded:)` / `base64EncodedString()` |
| 重连实现 | 协程 `delay()` + 递增计数 | `DispatchQueue.asyncAfter` + 计数 |
| 心跳处理 | `onMessage` 中判断 `type == "ping"` 直接 return | `receive` 中判断 `type == "ping"` 跳过 |
| sessionId 生成 | `UUID.randomUUID().toString()` | `UUID().uuidString` |
| 事件派发 | `Flow<AgentEvent>` / 回调 | `AsyncStream<AgentEvent>` / 闭包回调 |

## 10. 变更记录

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 1.0 | 2026-07-19 | 初始版本，从 Android 实现抽取并固化为双端契约 |
