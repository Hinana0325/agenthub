# AgentHub 下一步开发计划

> v2.0.2 → v2.1.0 迭代规划

---

## 当前状态

| 维度 | 数据 |
|:-----|:-----|
| 版本 | v2.0.2 (versionCode 14) |
| 源文件 | 83 个 Kotlin 文件 |
| 测试文件 | 10 个（覆盖率 ~12%） |
| 已完成功能 | 聊天、会话管理、Agent 管理、市场、对比、插件、工作流、同步、洞察、设置 |
| 技术债 | Hilt 迁移失败、测试覆盖不足、部分硬编码字符串 |

---

## Sprint 5：Hilt 重试 + 测试扩展（第 1 周）

### Day 1-3：Hilt 迁移（换方案）

上次失败原因：KSP 无法解析 ChatRepository 类型。新方案：**不使用 @Provides，改为 @Inject constructor**。

| # | 任务 | 文件 | 说明 |
|:-:|:-----|:-----|:-----|
| 5.1 | 添加 Hilt 依赖 | `build.gradle` (root + app) | hilt-android 2.56.2 + KSP |
| 5.2 | 创建 Application | `AgentHubApplication.kt` | @HiltAndroidApp |
| 5.3 | ChatRepository @Inject | `ChatRepository.kt` | 给构造函数加 @Inject |
| 5.4 | AppModule @Inject | `AppModule.kt` | 给构造函数加 @Inject |
| 5.5 | SettingsDataStore @Inject | `SettingsDataStore.kt` | 给构造函数加 @Inject |
| 5.6 | DatabaseModule | `di/DatabaseModule.kt` | @Provides 数据库和 DAO |
| 5.7 | 迁移 6 个 ViewModel | 各 ViewModel.kt | @HiltViewModel + @Inject |
| 5.8 | MainActivity @AndroidEntryPoint | `MainActivity.kt` | 启用 Hilt 注入 |
| 5.9 | AppNavigation hiltViewModel | `AppNavigation.kt` | 使用 hiltViewModel() |
| 5.10 | 删除 AppModule 单例 | `AppModule.kt` | 移除 getRepository/getPluginManager |

**关键改动**：上次用 `@Provides` 提供 ChatRepository，KSP 无法解析。这次改为给 ChatRepository 构造函数加 `@Inject`，让 Hilt 自动创建。

### Day 4-5：测试扩展（10 → 15 文件）

| # | 任务 | 新建文件 | 测试点 |
|:-:|:-----|:---------|:-------|
| 5.11 | CompareViewModel 测试 | `ui/compare/CompareViewModelTest.kt` | startCompare、cancelCompare、超时、竞态 |
| 5.12 | CryptoManager 扩展 | 已有 | 边界：空字符串、超长文本、特殊字符 |
| 5.13 | KeystoreManager 测试 | `util/KeystoreManagerTest.kt` | encrypt/decrypt、旧版迁移、已加密跳过 |
| 5.14 | MarkdownParser 扩展 | 已有 | 嵌套列表、空代码块、表格边界 |
| 5.15 | WorkflowEngine 扩展 | 已有 | 执行状态、节点输出缓存 |

---

## Sprint 6：性能 + 无障碍（第 2 周）

### Day 6-8：性能优化

| # | 任务 | 文件 | 说明 |
|:-:|:-----|:-----|:-----|
| 6.1 | LazyColumn 记忆化 | `ChatScreen.kt` | `derivedStateOf` 减少 MessageBubble 重组 |
| 6.2 | 首屏预加载 | `ChatViewModel.kt` | Splash 阶段预加载最近 session |
| 6.3 | 图片缓存 | `ChatViewModel.kt` | Base64 附件 LRU 缓存，避免重复编码 |

### Day 9-10：无障碍

| # | 任务 | 文件 | 说明 |
|:-:|:-----|:-----|:-----|
| 6.4 | ChatScreen a11y | `ChatScreen.kt` | 消息气泡、输入栏、操作按钮 contentDescription |
| 6.5 | SessionsScreen a11y | `SessionsScreen.kt` | 会话卡片、搜索框 contentDescription |
| 6.6 | AgentsScreen a11y | `AgentsScreen.kt` | Agent 卡片 contentDescription |
| 6.7 | CompareScreen a11y | `CompareScreen.kt` | 对比面板 contentDescription |

---

## Sprint 7：文档 + 发布（第 3 周）

### Day 11-13：文档

| # | 任务 | 文件 | 说明 |
|:-:|:-----|:-----|:-----|
| 7.1 | 架构图 | `docs/architecture.md` | MVVM + Transport + Keystore + Hilt |
| 7.2 | Transport API 文档 | `docs/transport-api.md` | 协议规范、扩展指南 |
| 7.3 | 插件开发指南 | `docs/plugin-guide.md` | Plugin 接口、示例 |
| 7.4 | README 更新 | `README.md` | v2.1.0 变更说明 |

### Day 14-15：发布

| # | 任务 | 文件 | 说明 |
|:-:|:-----|:-----|:-----|
| 7.5 | CHANGELOG | `CHANGELOG.md` | v2.1.0 所有变更 |
| 7.6 | 版本号 | `build.gradle` | versionName "2.1.0", versionCode 15 |
| 7.7 | Release APK | CI 自动构建 | tag v2.1.0 触发 |
| 7.8 | GitHub Release | tag + notes | 发布到 GitHub |

---

## 依赖关系

```
Sprint 5 (Hilt + 测试)
  ├── 5.1-5.10 Hilt 迁移 → 可独立进行
  └── 5.11-5.15 测试 → 依赖 Hilt 完成后验证

Sprint 6 (性能 + 无障碍)
  ├── 6.1-6.3 性能 → 可与 Sprint 5 并行
  └── 6.4-6.7 无障碍 → 可与 Sprint 5 并行

Sprint 7 (文档 + 发布)
  └── 全部依赖 Sprint 5-6 完成
```

---

## 风险与缓解

| 风险 | 概率 | 缓解 |
|:-----|:-----|:-----|
| Hilt 再次失败 | 中 | 改用 @Inject constructor 方案，避免 @Provides |
| 测试需要 mock 网络 | 中 | 使用 FakeTransport 实现 |
| Room 迁移问题 | 低 | 已有 fallbackToDestructiveMigration |
