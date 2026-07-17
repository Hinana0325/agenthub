# AgentHub 下一步开发计划（Sprint 级）

> 紧接 v2.0.0 修复后，拆解为可执行的 2 周 Sprint

---

## Sprint 1：构建修复 + 测试基础（第 1 周）

### Day 1-2：构建全链路跑通

| # | 任务 | 文件 | 验收标准 |
|:-:|:-----|:-----|:---------|
| 1.1 | `gradle.properties` 添加 suppressUnsupportedCompileSdk=36 | `android/gradle.properties` | 构建无 warning |
| 1.2 | 验证 connection_failed 字符串 formatted 修复 | 4 个 `strings.xml` | `./gradlew assembleDebug` 无 string 警告 |
| 1.3 | 清理 Capacitor 残留文件 | `capacitor.build.gradle`, `capacitor.settings.gradle`, `android/app/capacitor.build.gradle` | 删除后构建正常 |
| 1.4 | 本地 `./gradlew assembleDebug` 产出 APK | `app/build/outputs/apk/debug/` | APK 可安装运行 |

### Day 3-5：核心测试覆盖

| # | 任务 | 新建文件 | 测试点 |
|:-:|:-----|:---------|:-------|
| 2.1 | MarkdownParser 测试 | `ui/chat/MarkdownParserTest.kt` | bold/italic/code/link/table/heading/list/blockquote/空文本/嵌套 |
| 2.2 | TransportFactory 测试 | `provider/TransportFactoryTest.kt` | 6 种 AgentType → 正确 Transport 类型 |
| 2.3 | ConnectionState 模型测试 | 已有 | 确认已覆盖 |
| 2.4 | AgentTransport 接口契约测试 | `provider/AgentTransportTest.kt` | connect/sendMessage/disconnect 生命周期 |
| 2.5 | Room DAO 测试 | `data/local/dao/MessageDaoTest.kt` | CRUD + searchMessages + updateReaction |
| 2.6 | Room DAO 测试 | `data/local/dao/SessionDaoTest.kt` | CRUD + togglePin + incrementMessageCount |

**Sprint 1 产出：** 构建零警告 + 13+ 测试文件 + 核心逻辑覆盖

---

## Sprint 2：Hilt 迁移 + 功能标记（第 2 周）

### Day 6-8：Hilt 依赖注入

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
| 4.1 | CollabManager 标记实验性 | `ChatScreen.kt` | 添加 `isExperimental = true` 条件，默认隐藏 |
| 4.2 | WorkflowScreen Beta 标签 | `WorkflowScreen.kt` / `strings.xml` | TopBar 显示 "Beta" badge |
| 4.3 | PluginScreen 硬编码 → i18n | `PluginScreen.kt` + 4 个 `strings.xml` | 7 个字符串 |
| 4.4 | WorkflowScreen 硬编码 → i18n | `WorkflowScreen.kt` + 4 个 `strings.xml` | 6 个字符串 |
| 4.5 | README.md 更新 | `README.md` | 反映 v2.0.0 所有变更 |

**Sprint 2 产出：** Hilt DI 全量迁移 + 空壳功能标记 + i18n 100% 覆盖

---

## Sprint 3-4 预览（第 3-4 周）

### 性能优化
- Room 复合索引 `messages(sessionId, timestamp)`
- 图片附件压缩（>1MB 时自动压缩到 720p）
- LazyColumn `key` + `derivedStateOf` 减少重组
- Splash 预加载最近 session

### 用户体验
- 消息编辑（长按 → Edit，仅 User 消息）
- 会话搜索（SessionsScreen 顶部搜索框）
- 消息引用回复（滑动或长按 → Reply）
- 无障碍：TalkBack contentDescription 补全

### 文档
- 架构图（MVVM + Transport 层 + Keystore + Hilt）
- AgentTransport 接口文档
- 插件开发指南

---

## 依赖关系图

```
Sprint 1 (构建+测试)
  ├── 1.1-1.4 构建修复 ──→ 可独立进行
  └── 2.1-2.6 测试 ──→ 依赖 1.4 构建成功

Sprint 2 (Hilt+i18n)
  ├── 3.1-3.10 Hilt ──→ 依赖 Sprint 1 构建正常
  └── 4.1-4.5 标记+i18n ──→ 可与 Hilt 并行

Sprint 3-4 (性能+UX)
  └── 全部依赖 Sprint 2 Hilt 迁移完成
```

---

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|:-----|:-----|:-----|:-----|
| Hilt 迁移破坏现有功能 | 中 | 高 | 逐 ViewModel 迁移，每步跑测试 |
| compileSdk 36 AGP 不兼容 | 低 | 中 | 降至 compileSdk 35 即可 |
| 测试需要 mock 网络层 | 中 | 中 | 使用接口抽象 + Fake 实现 |
| Room 迁移 schema 变更 | 低 | 高 | 添加 Migration 或 bump version |
