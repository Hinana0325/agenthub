# AgentHub v2.1.0 开发计划

> 基于 v2.0.0 代码审计 + 已完成修复，规划下一版本迭代

---

## 已完成（v2.0.0 后续修复）

- [x] KeystoreManager 硬件级加密（apiKey / e2eKey）
- [x] 网络安全配置收紧（默认禁止明文，仅放行本地端点）
- [x] 消息重复 / 竞态条件修复
- [x] 平板语音/附件功能补全
- [x] URL 规范化修复（HTTP 端点不再被加 ws://）
- [x] 搜索框重叠 / TopBar 结构修复
- [x] 内层 Scaffold 双重 padding 修复
- [x] 性能模块实时刷新
- [x] 斜杠命令补全（/model /help /export /compare）
- [x] 7 个单元测试文件 + i18n 补全
- [x] CONTRIBUTING.md / SECURITY.md 更新

---

## v2.1.0 目标：质量加固 + 功能补全

### P0 — 必须完成

#### 1. 测试覆盖（当前 7 测试 / 81 源文件 → 目标 30%+）

| 模块 | 优先级 | 测试项 |
|:-----|:-------|:------|
| ChatViewModel | 🔴 | 消息发送、session 切换、命令执行、流式增量更新 |
| TransportFactory | 🔴 | 按 AgentType 路由到正确 Transport |
| OpenAIHttpTransport | 🔴 | SSE 解析、端点探活、错误处理 |
| WebSocketTransport | 🟠 | 连接/断开/重连、E2E 加解密 |
| MarkdownParser | 🟠 | 各类 Markdown 元素解析、边界情况 |
| SettingsViewModel | 🟠 | 主题切换、E2E 密钥管理、导入导出 |
| KeystoreManager | 🟠 | 加密/解密、旧版明文迁移 |
| AgentsViewModel | 🟡 | CRUD、导入导出 |

```bash
# 目录结构
android/app/src/test/java/com/agenthub/app/
├── ui/chat/ChatViewModelTest.kt
├── provider/TransportFactoryTest.kt
├── provider/OpenAIHttpTransportTest.kt
├── ui/chat/MarkdownParserTest.kt
├── data/settings/SettingsViewModelTest.kt
└── util/KeystoreManagerTest.kt   # 需要 instrumented test
```

#### 2. 修复构建警告

- [ ] `formatted="false"` 已修复 connection_failed 系列字符串
- [ ] `gradle.properties` 添加 `android.suppressUnsupportedCompileSdk=36`
- [ ] 升级 AGP 至支持 compileSdk 36 的版本（或降至 35）

#### 3. 补全 PluginScreen / WorkflowScreen 硬编码字符串

```kotlin
// PluginScreen.kt 中的硬编码 → stringResource
"Run" → R.string.plugin_run
"Input (optional)" → R.string.plugin_input_hint
"Run Plugin" → R.string.plugin_run_action
"Copy" → R.string.action_copy
"Send to Agent" → R.string.plugin_send_to_agent
"Result" → R.string.plugin_result

// WorkflowScreen.kt 中的硬编码
"Blank" → R.string.workflow_blank
"Start from scratch" → R.string.workflow_blank_desc
"$nodeCount nodes" → R.string.workflow_node_count
"$agentCount agents" → R.string.workflow_agent_count
```

---

### P1 — 应该完成

#### 4. 引入依赖注入（Hilt）

当前 `AppModule` 是手写单例，ViewModel 通过 `AndroidViewModel(application)` 获取依赖。引入 Hilt 可以：
- 解耦 ViewModel 与 Application
- 便于测试（mock 注入）
- 统一生命周期管理

```kotlin
// 目标架构
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val settingsDataStore: SettingsDataStore,
    private val transportFactory: TransportFactory
) : ViewModel()
```

**工作量估计：** 2-3 天
- 添加 Hilt 依赖 + @HiltAndroidApp
- 迁移 6 个 ViewModel
- 迁移 AppModule → @Module + @Provides
- 迁移 Room Database → @Module

#### 5. CollaborationManager 真实实现

当前 `CollaborationManager` 是空壳，UI 指示器永远不显示。需要：
- 定义协作协议（基于 WebSocket 的房间机制）
- 实现 session 共享、实时光标/typing 指示
- 或者标记为 "实验性功能" 并在 UI 中隐藏

**建议：** v2.1.0 先隐藏 collab 指示器，v2.2.0 再实现真实功能。

#### 6. WorkflowEngine 完善

当前 `WorkflowEngine` 有 UI 但缺少：
- 节点间实际数据传递
- Agent 节点的真实调用（连接到 AgentTransport）
- 执行状态持久化
- 错误恢复和重试

**建议：** v2.1.0 先标记为 Beta，添加 "实验性" 标签。

---

### P2 — 可以完成

#### 7. 性能优化

| 项目 | 说明 |
|:-----|:-----|
| LazyColumn 记忆化 | `MessageBubble` 使用 `key` + `remember` 减少重组 |
| 图片附件压缩 | Base64 编码前压缩大图，避免 OOM |
| Room 索引 | 为 messages.sessionId + timestamp 添加复合索引 |
| 首屏加载 | Splash → 预加载最近 session 的消息 |

#### 8. 用户体验改进

| 项目 | 说明 |
|:-----|:-----|
| 消息编辑 | 长按消息 → 编辑（仅 User 消息） |
| 会话搜索 | SessionsScreen 顶部添加搜索框 |
| 消息引用 | 滑动消息回复引用 |
| 深色主题跟随 | Liquid Glass 深色模式优化 |
| 无障碍 | TalkBack 支持、内容描述补全 |

#### 9. 文档完善

- [ ] README.md 更新（反映 v2.0.0 后的所有变更）
- [ ] API 文档（AgentTransport 接口、插件 API）
- [ ] 架构图更新（MVVM + Transport 层 + Keystore）

---

## 版本规划

| 版本 | 主题 | 预计时间 |
|:-----|:-----|:---------|
| v2.0.1 | 构建警告修复 + 硬编码字符串 + 测试基础 | 1 周 |
| v2.1.0 | 测试覆盖 30%+ + Hilt 迁移 + 功能标记 | 3-4 周 |
| v2.2.0 | 协作功能 + Workflow 引擎 + 性能优化 | 4-6 周 |

---

## 技术债务清单

| 项目 | 严重程度 | 说明 |
|:-----|:---------|:-----|
| 无 DI 框架 | 🟠 | 手写 AppModule，测试困难 |
| 测试覆盖不足 | 🟠 | 7/81 文件，核心逻辑无测试 |
| CollabManager 空壳 | 🟡 | UI 存在但无后端 |
| WorkflowEngine 半成品 | 🟡 | UI 完整但无真实执行 |
| Capacitor 残留 | 🟡 | capacitor.build.gradle 等文件仍在 |
| string 资源警告 | 🟡 | 部分已修复，需验证构建 |
