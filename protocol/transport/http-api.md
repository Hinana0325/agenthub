# AgentHub HTTP API 传输协议规范

本文件定义 AgentHub 统一协议层中基于 HTTP 的 RESTful 接口规范，采用 OpenAI 兼容的 Chat Completions 端点。该协议是 Android（Kotlin）与 iOS（Swift）双端共享的永久契约，任何字段语义、取值范围或行为变更均需双端同步并保持向后兼容。服务端为无状态设计，会话历史由客户端维护。

## 1. 端点

- **请求方法**：`POST`
- **完整路径**：`{serverUrl}/v1/chat/completions`
- **协议**：HTTP/1.1 或 HTTP/2（由底层网络栈自动协商）
- **路径说明**：`serverUrl` 为 AgentHub 服务端根地址，末尾不带斜杠；端点固定追加 `/v1/chat/completions`，遵循 OpenAI API 路径约定。

## 2. 请求头

| Header | 值 | 说明 |
|--------|-----|------|
| `Authorization` | `Bearer {apiKey}` | 每次请求必须携带，apiKey 由本地密钥库解密后注入 |
| `Content-Type` | `application/json` | 请求体固定为 JSON |
| `Accept` | `text/event-stream` | 客户端建议显式声明，期望 SSE 流式响应 |

## 3. 请求体

请求体始终使用流式模式（`stream: true`），由客户端构造后发送。

```json
{
  "model": "<model>",
  "messages": [{"role": "user", "content": "..."}],
  "stream": true,
  "temperature": 0.7,
  "max_tokens": 4096
}
```

字段语义说明：

- **`model`**：模型标识符字符串，由服务端配置决定可用值，客户端原样透传。
- **`messages`**：消息数组，按时间顺序排列，详见第 6 节。
- **`stream`**：固定为 `true`，客户端不应发送 `false`。若服务端忽略该字段并返回非流式 JSON，按 SSE 协议的「全量回退」分支处理（详见 `sse-protocol.md`）。
- **`temperature`**：采样温度，默认 `0.7`。
- **`max_tokens`**：单次响应最大 token 数，默认 `4096`。

## 4. 探活端点

用于在配置阶段验证服务端可达性与鉴权配置正确性。

- **请求方法**：`GET`
- **路径构造**：
  - 若 `{base}` 以 `/v1` 结尾，探活路径为 `{base}/models`
  - 否则探活路径为 `{base}/v1/models`
- **超时**：5 秒
- **可达性判定**：

| HTTP 状态码 | 判定结果 |
|-------------|----------|
| 2xx | 可达，鉴权通过 |
| 401 / 403 | 可达，但鉴权失败（apiKey 无效或权限不足） |
| 404 | 可达，端点不存在（仍视为服务在线） |
| 5xx | 不可达，服务端故障 |
| 连接失败 / 超时 | 不可达 |

> 注：401/403/404 归为「可达」是因为能够返回 HTTP 响应本身即证明网络层连通；业务层应进一步区分鉴权状态。

## 5. 超时配置

客户端网络栈应按以下三层超时配置，单位均为秒：

| 超时项 | 值 | 说明 |
|--------|-----|------|
| 连接超时（connect） | 10s | TCP/TLS 建连阶段 |
| 请求超时（request） | 120s | 整个请求生命周期，覆盖流式响应全部时间 |
| Socket 读超时（socket） | 30s | 两个数据帧之间的最大空闲间隔，超时即判定连接停滞 |

## 6. 会话历史管理

- **存储位置**：客户端本地，按 `sessionId` 分组维护。
- **服务端状态**：无状态。服务端不保留任何会话上下文，每次请求自包含完整历史。
- **滑动窗口**：每个 `sessionId` 最多保留最近 20 条消息，超出则丢弃最早的消息。
- **构造规则**：发起请求时，客户端将窗口内的全部消息按时间顺序填入 `messages` 数组，并在末尾追加当前用户输入。
- **新建会话**：使用新的 `sessionId`，历史从空开始。

## 7. systemPrompt 字段

`systemPrompt` 当前在 Android 实现中**不注入**到请求体。协议层保留该字段供未来使用，定义如下以备扩展：

- 预期语义：作为 `messages` 数组首项 `{"role": "system", "content": "<systemPrompt>"}` 注入。
- 当前行为：客户端忽略该字段，不发送任何 system 角色消息。
- 兼容性约束：未来启用时，必须以新增字段或显式开关方式引入，不得改变现有请求体结构。

## 8. messages 数组结构

`messages` 数组每一项的结构如下：

```json
{"role": "user", "content": "..."}
```

- **`role`**：角色枚举，取值必须与 `MessageRole` 枚举一致。合法取值：
  - `"user"` — 用户输入
  - `"assistant"` — 模型响应
  - `"system"` — 系统指令（当前未启用，见第 7 节）
  - `"tool"` — 工具调用结果
- **`content`**：字符串形式的消息内容，不允许为 `null`；空字符串合法但语义为空消息。

> 枚举值大小写敏感，必须使用全小写字符串。客户端序列化时应使用各端 `MessageRole` 枚举的 `name` 属性（如 Kotlin 的 `enum.name`、Swift 的 `String(describing:)`）以保证一致。

## 9. 跨平台实现映射

| 维度 | Android（Kotlin） | iOS（Swift） |
|------|-------------------|--------------|
| HTTP 客户端 | OkHttp / Retrofit | URLSession |
| 请求构造 | `RequestBody` + Gson/Moshi 序列化 | `URLRequest` + JSONEncoder |
| 流式响应处理 | `ResponseBody.source()` 逐行读取 | `URLSessionDataDelegate` 增量回调 |
| 超时配置 | `OkHttpClient.Builder` 的 `connectTimeout` / `readTimeout` / `writeTimeout` | `URLSessionConfiguration.timeoutIntervalForRequest` / `timeoutIntervalForResource` |
| apiKey 解密 | `KeystoreManager.decryptOrRaw()` | Keychain 读取 + 等价 AES-256-GCM 解密 |
| 历史管理 | `SessionManager` 内存 + 持久化 | `SessionManager` 等价实现 |
| 探活实现 | `GET` 请求 + 状态码判定 | `GET` 请求 + `HTTPURLResponse.statusCode` 判定 |
| messages 枚举 | `enum class MessageRole { User, Assistant, System, Tool }`，序列化取 `name.lowercase()` | `enum MessageRole: String` 原始值小写 |
| systemPrompt 注入 | 当前不注入 | 当前不注入（双端一致） |

## 10. 变更记录

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 1.0 | 2026-07-19 | 初始版本，从 Android 实现抽取并固化为双端契约 |
