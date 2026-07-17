# AgentHub 开发计划

> v2.0.0 代码审计后的全量迭代规划，Sprint 级可执行

---

## 已完成（v2.0.0 修复）

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

## Sprint 1：构建修复 + 测试基础（第 1 周）

### Day 1-2：构建全链路跑通

| # | 任务 | 文件 | 验收标准 |
|:-:|:-----|:-----|:---------|
| 1.1 | `gradle.properties` 添加 suppressUnsupportedCompileSdk=36 | `android/gradle.properties` | 构建无 warning |
| 1.2 | 验证 connection_failed 字符串 formatted 修复 | 4 个 `strings.xml` | `./gradlew assembleDebug` 无 string 警告 |
| 1.3 | 清理 Capacitor 残留文件 | `capacitor.build.gradle`, `capacitor.settings.gradle` | 删除后构建正常 |
| 1.4 | 本地 `./gradlew assembleDebug` 产出 APK | `app/build/outputs/apk/debug/` | APK 可安装运行 |

### Day 3-5：核心测试覆盖（当前 7 测试 / 81 源文件 → 目标 13+）

| # | 任务 | 新建文件 | 测试点 |
|:-:|:-----|:---------|:-------|
| 2.1 | MarkdownParser 测试 | `ui/chat/MarkdownParserTest.kt` | bold/italic/code/link/table/heading/list/blockquote/空文本/嵌套 |
| 2.2 | TransportFactory 测试 | `provider/TransportFactoryTest.kt` | 6 种 AgentType → 正确 Transport 类型 |
| 2.3 | AgentTransport 接口契约测试 | `provider/AgentTransportTest.kt` | connect/sendMessage/disconnect 生命周期 |
| 2.4 | Room DAO 测试 | `data/local/dao/MessageDaoTest.kt` | CRUD + searchMessages + updateReaction |
| 2.5 | Room DAO 测试 | `data/local/dao/SessionDaoTest.kt` | CRUD + togglePin + incrementMessageCount |

**Sprint 1 产出：** 构建零警告 + 13+ 测试文件 + 核心逻辑覆盖

---

## Sprint 2：Hilt 迁移 + 功能标记（第 2 周）

### Day 6-8：Hilt 依赖注入

当前 `AppModule` 是手写单例，ViewModel 通过 `AndroidViewModel(application)` 获取依赖。引入 Hilt 可以解耦、便于 mock 测试、统一生命周期。

| # | 任务 | 文件变更 | 说明 |
|:-:|:-----|:---------|:-----|
| 3.1 | 添加 Hilt 依赖 | `build.gradle` (root + app) | hilt-android, hilt-compiler (KSP) |
| 3.2 | 创建 `@HiltAndroidApp` | `App.kt` | 替换手动 AppModule |
| 3.3 | Database Module | 新建 `di/DatabaseModule.kt` | @Provides AppDatabase, 各 DAO |
| 3.4 | Repository Module | 新建 `di/RepositoryModule.kt` | @Provides ChatRepository, SettingsDataStore |
| 3.5 | 迁移 ChatViewModel | `ChatViewModel.kt` | @HiltViewModel + @Inject |
| 3.6 | 迁移 SettingsViewModel | `SettingsViewModel.kt` | @HiltViewModel + @Inject |
| 3.7 | 迁移 AgentsViewModel | `AgentsViewModel.kt` | @HiltViewModel + @Inject |
| 3.8 | 迁移 ActivityViewModel | `ActivityViewModel.kt` | @HiltViewModel + @Inject |
| 3.9 | 迁移 InsightsViewModel | `InsightsViewModel.kt` | @HiltViewModel + @Inject |
| 3.10 | 删除 AppModule.kt | `data/AppModule.kt` | 确认无引用后删除 |

### Day 9-10：功能标记 + i18n 收尾

| # | 任务 | 文件变更 | 说明 |
|:-:|:-----|:---------|:-----|
| 4.1 | CollabManager 标记实验性 | `ChatScreen.kt` | 默认隐藏 collab 指示器 |
| 4.2 | WorkflowScreen Beta 标签 | `WorkflowScreen.kt` / `strings.xml` | TopBar 显示 "Beta" badge |
| 4.3 | PluginScreen 硬编码 → i18n | `PluginScreen.kt` + 4 个 `strings.xml` | Run / Input / Copy / Send to Agent / Result 等 7 个 |
| 4.4 | WorkflowScreen 硬编码 → i18n | `WorkflowScreen.kt` + 4 个 `strings.xml` | Blank / nodes / agents 等 6 个 |
| 4.5 | README.md 更新 | `README.md` | 反映 v2.0.0 所有变更 |

**Sprint 2 产出：** Hilt DI 全量迁移 + 空壳功能标记 + i18n 100% 覆盖

---

## Sprint 3：性能优化 + 用户体验（第 3 周）

### Day 11-13：性能优化

| # | 任务 | 文件 | 说明 |
|:-:|:-----|:-----|:-----|
| 5.1 | Room 复合索引 | `AppDatabase.kt` + Migration | `messages(sessionId, timestamp)` |
| 5.2 | 图片附件压缩 | `ChatViewModel.kt` | >1MB 自动压缩到 720p，避免 OOM |
| 5.3 | LazyColumn 记忆化 | `ChatScreen.kt` | `derivedStateOf` 减少 MessageBubble 重组 |
| 5.4 | 首屏预加载 | `ChatViewModel.kt` | Splash 阶段预加载最近 session 消息 |

### Day 14-15：用户体验改进

| # | 任务 | 文件 | 说明 |
|:-:|:-----|:-----|:-----|
| 6.1 | 消息编辑 | `ChatViewModel.kt` + `ChatScreen.kt` | 长按 → Edit（仅 User 消息） |
| 6.2 | 会话搜索 | `SessionsScreen.kt` | 顶部搜索框，按标题过滤 |
| 6.3 | 消息引用回复 | `ChatViewModel.kt` + `ChatScreen.kt` | 长按 → Reply，输入栏显示引用 |

**Sprint 3 产出：** Room 索引 + 图片压缩 + 消息编辑/引用 + 会话搜索

---

## Sprint 4：文档 + 发布（第 4 周）

### Day 16-18：文档完善

| # | 任务 | 文件 | 说明 |
|:-:|:-----|:-----|:-----|
| 7.1 | 架构图 | `docs/architecture.md` | MVVM + Transport 层 + Keystore + Hilt |
| 7.2 | AgentTransport 接口文档 | `docs/transport-api.md` | 协议规范、扩展指南 |
| 7.3 | 插件开发指南 | `docs/plugin-guide.md` | Plugin 接口、示例 |
| 7.4 | 无障碍补全 | 多个 Screen 文件 | TalkBack contentDescription |

### Day 19-20：发布准备

| # | 任务 | 文件 | 说明 |
|:-:|:-----|:-----|:-----|
| 8.1 | CHANGELOG.md | `CHANGELOG.md` | 记录 v2.1.0 所有变更 |
| 8.2 | 版本号升级 | `build.gradle` | versionName "2.1.0", versionCode 13 |
| 8.3 | Release APK | `./gradlew assembleRelease` | 签名 + ProGuard |
| 8.4 | GitHub Release | tag + release notes | 发布到 GitHub |

**Sprint 4 产出：** 完整文档 + v2.1.0 正式发布

---

## 依赖关系

```
Sprint 1 (构建+测试)
  ├── 1.1-1.4 构建修复 ──→ 可独立进行
  └── 2.1-2.5 测试 ──→ 依赖 1.4 构建成功

Sprint 2 (Hilt+i18n)
  ├── 3.1-3.10 Hilt ──→ 依赖 Sprint 1 构建正常
  └── 4.1-4.5 标记+i18n ──→ 可与 Hilt 并行

Sprint 3 (性能+UX)
  └── 全部依赖 Sprint 2 Hilt 迁移完成

Sprint 4 (文档+发布)
  └── 依赖 Sprint 1-3 全部完成
```

---

## 技术债务清单

| 项目 | 严重程度 | Sprint 解决 |
|:-----|:---------|:-----------|
| 无 DI 框架 | 🟠 | Sprint 2 (Hilt) |
| 测试覆盖不足 | 🟠 | Sprint 1 (13+ 测试) |
| CollabManager 空壳 | 🟡 | Sprint 2 (隐藏标记) |
| WorkflowEngine 半成品 | 🟡 | Sprint 2 (Beta 标签) |
| Capacitor 残留 | 🟡 | Sprint 1 (清理) |
| 硬编码字符串 | 🟡 | Sprint 2 (i18n) |

---

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|:-----|:-----|:-----|:-----|
| Hilt 迁移破坏现有功能 | 中 | 高 | 逐 ViewModel 迁移，每步跑测试 |
| compileSdk 36 AGP 不兼容 | 低 | 中 | 降至 compileSdk 35 即可 |
| 测试需要 mock 网络层 | 中 | 中 | 使用接口抽象 + Fake 实现 |
| Room 迁移 schema 变更 | 低 | 高 | 添加 Migration 或 bump version |
