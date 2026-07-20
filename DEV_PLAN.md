# Agent Control Center 开发计划

> 仓库：`Hinana0325/Agent-Control-Center`
> 当前版本：v2.1.3（Android versionCode 18 / iOS build 1）

## 当前状态

项目已从早期 PWA + Capacitor 架构完全重构为**双原生应用**，并建立了两端共享的永久统一协议层。

| 维度 | 数据 |
|:-----|:-----|
| 版本 | v2.1.3（Android versionCode 18 / iOS build 1） |
| 架构 | 双原生：Android（Kotlin + Compose）+ iOS（Swift + SwiftUI） |
| 协议层 | 已建立：10 JSON Schema + 4 传输协议（`protocol/`） |
| Android | 成熟：MVVM + Hilt + Room + Ktor，Liquid Glass 主题落地 |
| iOS | 骨架完成：十层架构 + SwiftData + URLSession，6 功能视图就位 |
| 测试 | Android 14 测试文件 / 75+ 用例；iOS 待补 |
| 已知 P0 问题 | 0 |

### 已完成里程碑

- **v2.0.x**：移除 Capacitor / PWA 残留，纯 Kotlin + Compose 重构
- **v2.1.0**：Hilt 迁移、模型对比、消息回复、测试扩展、无障碍优化、CI 流水线
- **v2.1.1**：代码审查 Hotfix（SSE 流式、Widget、WebSocket 生命周期、ProGuard 规则等）
- **v2.1.3**：建立 `protocol/` 统一协议层 + iOS 原生骨架（十层架构、6 功能视图、SwiftData 持久化）

---

## Sprint 12：iOS 对齐 + 协议验证（第 1–2 周）

目标：让 iOS 在功能与协议层面与 Android 对齐，达到可内测状态。

| # | 优先级 | 任务 | 说明 |
|:-:|:------|:-----|:-----|
| 12.1 | P0 | iOS Transport 真机联调 | `OpenAIHTTPTransport` / `WebSocketTransport` 接入真实 Agent，校验与 Android 线上格式一致 |
| 12.2 | P0 | iOS MCP 联调 | `McpBridge` 完整链路：initialize → tools-list → tools-call |
| 12.3 | P0 | iOS Plugin 执行器 | `PluginExecutor` 三类动作（HttpCall / Broadcast / Workflow）落地 |
| 12.4 | P0 | iOS E2E 加密 | `KeychainManager` 实现 `AH1:` 传输加密，与 Android PBKDF2 600000 轮对齐 |
| 12.5 | P1 | iOS 流式聊天 | `ChatView` 流式增量渲染 + 取消 |
| 12.6 | P1 | iOS 单元测试 | Transport / WorkflowEngine / KeychainManager 测试骨架 |

---

## Sprint 13：Android 功能深化（第 3–4 周）

| # | 优先级 | 任务 | 文件 |
|:-:|:------|:-----|:-----|
| 13.1 | P0 | 多轮对话：OpenAIHttpTransport 维护会话消息历史 | `transport/http/OpenAIHttpTransport.kt` |
| 13.2 | P0 | Widget 快捷输入 Activity | `AgentControlCenterWidget.kt` / `widget/WidgetInputActivity.kt` |
| 13.3 | P1 | WebSocket 多轮对话 | `transport/websocket/WebSocketTransport.kt` |
| 13.4 | P2 | CollaborationManager 真实实现 | `data/collab/CollaborationManager.kt` |
| 13.5 | P2 | DeviceSync 增强 | `data/sync/DeviceSyncManager.kt` |

---

## Sprint 14：双端测试 + 协议一致性（第 5–6 周）

| # | 优先级 | 任务 | 说明 |
|:-:|:------|:-----|:-----|
| 14.1 | P0 | 协议一致性测试 | 对照 `protocol/schemas/` 生成两端测试夹具，校验序列化字节一致 |
| 14.2 | P1 | Android 测试扩展（14 → 20） | VoiceInputManager / KeystoreManager / LocalModelManager 等 |
| 14.3 | P1 | iOS 测试扩展 | Transport / Models / WorkflowEngine / KeychainManager |
| 14.4 | P2 | CI 增加 iOS 构建 | XcodeGen + xcodebuild 接入 GitHub Actions |

---

## Sprint 15：文档 + 发布 v2.2.0（第 7 周）

| # | 任务 | 文件 |
|:-:|:-----|:-----|
| 15.1 | Plugin 开发指南 | `docs/plugin-guide.md` |
| 15.2 | Transport API 文档 | `docs/transport-api.md` |
| 15.3 | 架构文档更新 | `docs/architecture.md` |
| 15.4 | README 更新 | `README.md` |
| 15.5 | CHANGELOG + 版本号 | `CHANGELOG.md` |
| 15.6 | Release v2.2.0 | tag + CI + GitHub Release（双端） |

---

## v2.3.0 — 未来版本

| 优先级 | 任务 | 说明 |
|:------|:-----|:-----|
| P1 | 真正的 Backdrop Blur | Android：View 快照 + RenderEffect；iOS：评估 `.ultraThinMaterial` |
| P1 | 本地模型推理 | Android：llama.cpp / MediaPipe；iOS：MLX / llama.cpp |
| P2 | Workflow 可视化编辑器 | 拖拽式节点编辑 + 连线 + 实时预览 |
| P2 | Agent Marketplace 集成 | 社区 Agent 配置发现与下载 |
| P3 | iOS Widget / 快捷指令 | WidgetKit + App Intents |
| P3 | 跨端同步 | 双端会话 / 配置同步（与 DeviceSyncManager 协同） |

---

## 依赖关系

```
v2.1.3（当前，双端骨架就位）
  │
  ▼
Sprint 12（iOS 对齐 + 协议验证）
  ├──► iOS 功能与 Android 对齐
  └──► 协议一致性是后续所有迭代前提
  │
  ▼
Sprint 13（Android 功能深化）  ← 可与 Sprint 12 部分并行
  │
  ▼
Sprint 14（双端测试 + 一致性）
  │
  ▼
Sprint 15（文档 + 发布 v2.2.0）
  │
  ▼
v2.3.0（独立调研，可并行）
```

---

## 风险与缓解

| 风险 | 概率 | 缓解 |
|:-----|:-----|:-----|
| 双端协议漂移 | 高 | 以 `protocol/` 为单一事实来源，CI 校验 schema 一致性 |
| iOS 真机联调阻塞 | 中 | 优先打通 OpenAI HTTP 路径，WebSocket 次之 |
| 多轮对话消息膨胀 | 中 | 滑动窗口 + 可配置最大轮数 |
| 本地推理包体积（APK / IPA） | 中 | 模型动态下载，包仅含推理引擎 |
| Liquid Glass 性能（低端设备） | 中 | Android API 31+ 启用；iOS 评估材质性能 |
| 测试覆盖回归 | 低 | CI 每次提交自动验证 |
