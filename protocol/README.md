# Agent Control Center 协议层

Agent Control Center Android (Kotlin) 与 iOS (Swift) 之间永久统一的协议契约。

## 设计原则

- **只统一协议层，不强求代码复用** — 两端各自使用平台最佳实践的原生技术栈
- **JSON Schema 是唯一事实来源** — 数据模型的字段名、类型、枚举值以 schema 文件为准，两端手写映射
- **传输格式跨平台一致** — HTTP/SSE/WebSocket/MCP 的线上字节流格式必须完全相同
- **加密格式跨平台兼容** — `AKS:` 前缀（静态存储）和 `AH1:` 前缀（传输加密）在两端行为一致

## 目录结构

```
protocol/
├── schemas/                        # JSON Schema 契约（机器可读，唯一事实来源）
│   ├── agent-schema.json           # Agent 标识 + 信息模型 + 配置
│   ├── session-schema.json         # 会话模型
│   ├── message-schema.json         # 消息模型 + 角色枚举 + 附件
│   ├── task-schema.json            # 任务模型 + 类型/状态枚举
│   ├── workflow-schema.json        # 工作流 DAG 模型
│   ├── event-schema.json           # 实时事件模型（判别联合）
│   ├── error-codes.json            # 错误码注册表（37 个码，10 个类别）
│   ├── plugin-schema.json          # 插件协议（HttpCall/Broadcast/Workflow）
│   ├── mcp-schema.json             # MCP 桥接协议（JSON-RPC 2.0）
│   └── file-transfer-schema.json   # 文件传输协议（v1 内联 / v2 分块）
│
├── transport/                      # 传输协议规范（人类可读）
│   ├── http-api.md                 # HTTP REST API（OpenAI 兼容）
│   ├── sse-protocol.md             # SSE 流式输出协议
│   ├── websocket-protocol.md       # WebSocket 双向通信协议
│   └── auth.md                     # 认证与令牌机制
│
└── README.md                       # 本文件
```

## 16 个统一模块

| 模块 | Schema 文件 | 说明 |
|------|------------|------|
| Agent ID | agent-schema.json | `Agent.id`，格式 `agent_<uuid>` |
| Agent 信息模型 | agent-schema.json | name、status、capabilities、protocol |
| Session 模型 | session-schema.json | 会话结构，sessionId 贯穿 WebSocket 传输 |
| Message 模型 | message-schema.json | 消息结构，含角色/状态/附件/回应/回复 |
| Task 模型 | task-schema.json | 异步任务结构，5 种类型/5 种状态 |
| Workflow 模型 | workflow-schema.json | DAG 工作流，4 种节点/8 种变换 |
| Event 模型 | event-schema.json | 6 种实时事件（判别联合） |
| Error Code | error-codes.json | 37 个错误码，10 个类别 |
| JSON Schema | 全部 schemas/ | 所有数据格式以 JSON Schema 2020-12 定义 |
| HTTP API | transport/http-api.md | OpenAI 兼容的 `/v1/chat/completions` |
| SSE 协议 | transport/sse-protocol.md | `text/event-stream`，`choices[0].delta.content` |
| WebSocket 协议 | transport/websocket-protocol.md | JSON 帧，服务端有状态会话 |
| Auth Token | transport/auth.md | Bearer Token / auth 帧 / `AKS:` 加密存储 |
| Plugin 协议 | plugin-schema.json | HttpCall / Broadcast / Workflow 三种动作 |
| MCP Bridge 协议 | mcp-schema.json | JSON-RPC 2.0，initialize/tools-list/tools-call |
| File Transfer 协议 | file-transfer-schema.json | v1 内联 Base64 / v2 分块上传 |

## 跨平台实现映射

| 层 | Android (Kotlin) | iOS (Swift) |
|----|-------------------|-------------|
| 数据模型 | `data class` + `@Serializable` | `struct` + `Codable` |
| 枚举 | `enum class` | `enum` + `String` rawValue |
| 可空类型 | `String?` | `String?` / `Optional<String>` |
| 时间戳 | `Long` (Unix 毫秒) | `Int64` (Unix 毫秒) |
| Map | `Map<String, String>` | `[String: String]` |
| 序列化 | Gson / Kotlin Serialization | `JSONEncoder` / `JSONDecoder` |
| Keystore | `AndroidKeyStore` + `KeystoreManager` | Keychain + 等价 Manager |
| WebSocket | Ktor WebSocket | `URLSessionWebSocketTask` |
| SSE | Ktor SSE | `URLSession` + 自实现 EventSource |

## 版本与变更

协议层变更必须遵循：
1. 新增字段：向后兼容，添加 `default` 值，旧客户端忽略未知字段
2. 移除字段：先标记 `deprecated`，至少一个版本周期后移除
3. 枚举变更：只能新增值，不能移除或重命名已有值
4. 传输格式变更：两端必须同步发布

当前版本：`4.6.2`（与 Android app 版本对齐）
