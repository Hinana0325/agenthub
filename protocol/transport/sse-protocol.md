# Agent Control Center SSE 流式传输协议规范

本文件定义 Agent Control Center 统一协议层中基于 Server-Sent Events（SSE）的流式响应协议，用于 HTTP Chat Completions 端点的增量响应传输。该协议遵循 W3C SSE 标准，并在此基础上约定 OpenAI 兼容的增量数据格式与客户端事件映射规则。本规范为 Android（Kotlin）与 iOS（Swift）双端共享的永久契约。

## 1. 传输层

- **承载协议**：HTTP 响应，状态码 `200`。
- **响应头**：`Content-Type: text/event-stream`
- **字符编码**：UTF-8
- **连接特性**：长连接，服务端持续推送数据帧直至流结束或连接断开。
- **关联请求**：SSE 流由 `POST {serverUrl}/v1/chat/completions`（`stream: true`）触发，详见 `http-api.md`。

## 2. 事件格式

遵循标准 SSE 行格式：

- 每行以字段名开头，字段名与值之间以冒号加空格分隔（`field: value`）。
- 字段之间以单个换行符 `\n` 分隔。
- 一个完整事件以空行（两个连续换行符）结束。
- 客户端应在收到空行时触发当前事件的派发。

SSE 规范定义的字段包括 `data`、`event`、`id`、`retry`。Agent Control Center 仅使用 `data` 字段承载 JSON 载荷，其余字段如出现应忽略。

## 3. data 字段与多行拼接

- 单个事件可包含多个 `data:` 行。
- 客户端解析时，将同一事件内所有 `data:` 行的值按出现顺序以 `\n` 拼接，形成完整 JSON 载荷。

示例（多行 data）：

```
data: {"choices":[{"delta":{"content":"Hello
data: , world"}}]}
```

拼接后载荷为：

```
{"choices":[{"delta":{"content":"Hello\n, world"}}]}
```

> 注：OpenAI 兼容服务端通常将完整 JSON 放在单行 `data:` 中，但客户端必须支持多行拼接以兼容标准 SSE 实现。

## 4. 注释与心跳

- 以冒号 `:` 开头的行视为注释或心跳，客户端必须忽略其内容。
- 服务端可定期发送 `: keepalive` 之类的注释行以保持连接活跃，防止中间代理超时断开。
- 注释行不构成事件，不应触发任何事件派发。

示例：

```
: ping
```

## 5. 流终止

- 当某个事件的 `data` 值为字面量 `[DONE]`（不含引号）时，表示流结束。
- 客户端收到 `[DONE]` 后应：
  1. 停止读取流。
  2. 关闭底层 HTTP 连接。
  3. 派发 `AgentEvent.StreamComplete` 事件。
- `[DONE]` 本身不是合法 JSON，客户端在尝试 JSON 解析前必须先做字面量匹配判断。

示例：

```
data: [DONE]
```

## 6. 增量解析（Delta）

流式模式下，每个 `data:` 载荷为合法 JSON，增量文本位于路径 `choices[0].delta.content`。

载荷结构示例：

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion.chunk",
  "choices": [
    {
      "index": 0,
      "delta": {
        "content": "Hello"
      },
      "finish_reason": null
    }
  ]
}
```

解析规则：

- 取 `choices` 数组首元素（`index === 0`）的 `delta.content` 字段作为增量文本。
- `delta.content` 可能为 `null` 或缺失（如首帧仅含 `role` 字段），此时视为空增量，跳过派发。
- `finish_reason` 为 `null` 表示流未结束；为 `"stop"` 等非空值表示该帧为最后一帧内容，但仍需等待 `[DONE]` 才派发 `StreamComplete`。

## 7. 全量回退（Full Response Fallback）

当服务端忽略 `stream: true` 并返回非流式 JSON 响应时，客户端应能识别并回退到全量解析：

- **识别条件**：响应 `Content-Type` 为 `application/json`（非 `text/event-stream`），或首帧即包含完整 `choices[0].message`。
- **解析路径**：`choices[0].message.content`，作为一次性全量文本。
- **载荷结构示例**：

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello, world"
      },
      "finish_reason": "stop"
    }
  ]
}
```

- **派发规则**：将 `message.content` 作为单次全量消息派发，并立即派发 `StreamComplete`。

## 8. 事件映射

客户端将 SSE 原始数据映射为统一的 `AgentEvent`，供上层业务消费：

| SSE 原始信号 | 映射事件 | 说明 |
|--------------|----------|------|
| 单个 `delta.content` 增量 | `AgentEvent.MessageReceived(content, isDelta=true)` | 每个增量帧派发一次 |
| 全量 `message.content` 回退 | `AgentEvent.MessageReceived(content, isDelta=false)` | 仅在全量回退分支派发一次 |
| `data: [DONE]` | `AgentEvent.StreamComplete` | 流结束，附带累积完整文本 |
| 连接错误 / 异常断开 | `AgentEvent.Error` | 网络层或解析层异常 |

`AgentEvent.MessageReceived` 参数语义：

- `content`：本次帧的文本（增量或全量），非累积值。
- `isDelta`：`true` 表示增量帧，`false` 表示全量帧。上层可据此决定是追加显示还是整体替换。

## 9. 累积逻辑

客户端必须维护一个累积缓冲区，将所有增量帧的 `delta.content` 按到达顺序拼接，形成完整响应文本：

- 累积范围：从首个非空增量帧开始，至 `[DONE]` 之前的最后一个增量帧。
- 累积结果在 `AgentEvent.StreamComplete` 中作为最终完整文本返回。
- 全量回退分支下，累积缓冲区直接等于 `message.content`，无需逐帧拼接。
- 累积缓冲区在每次新请求开始前必须清空。

## 10. 解析状态机

客户端解析器应实现以下状态机：

```
[INIT] --收到首个 data--> [PARSING]
[PARSING] --data: [DONE]--> [COMPLETE] --派发 StreamComplete--> [CLOSED]
[PARSING] --delta.content 非空--> 派发 MessageReceived(isDelta=true) --> [PARSING]
[PARSING] --连接断开/错误--> [ERROR] --派发 Error--> [CLOSED]
[COMPLETE] / [ERROR] --> 关闭连接
```

全量回退分支：`[INIT]` --识别非 SSE--> 直接派发 `MessageReceived(isDelta=false)` + `StreamComplete` --> `[CLOSED]`。

## 11. 跨平台实现映射

| 维度 | Android（Kotlin） | iOS（Swift） |
|------|-------------------|--------------|
| SSE 读取 | OkHttp `ResponseBody.source().readUtf8Line()` 逐行 | `URLSessionDataDelegate` 增量 `data` 回调 + 行缓冲 |
| 行缓冲实现 | 自定义 `BufferedReader` 或 `Source.readUtf8Line()` | `Data` 累积 + 按 `\n` 切分 |
| JSON 解析 | Gson / Moshi / `org.json.JSONObject` | `JSONSerialization` / `Codable` |
| `[DONE]` 判定 | `line == "data: [DONE]"` 或去除前缀后 `== "[DONE]"` | 同左，字符串字面量比较 |
| 累积缓冲 | `StringBuilder` | `String` 拼接或 `Data` 累积 |
| 事件派发 | `Flow<AgentEvent>` / 回调 | `AsyncStream<AgentEvent>` / 闭包回调 |
| 多行 data 拼接 | `List<String>.joinToString("\n")` | `[String].joined(separator: "\n")` |
| 全量回退识别 | 检查 `Content-Type` 或首帧 `message` 字段 | 检查 `HTTPURLResponse.value(forHTTPHeaderField:)` 或首帧结构 |
| 错误处理 | `IOException` 捕获 → `AgentEvent.Error` | `URLSessionDelegate` 错误回调 → `AgentEvent.Error` |

## 12. 变更记录

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 1.0 | 2026-07-19 | 初始版本，从 Android 实现抽取并固化为双端契约 |
