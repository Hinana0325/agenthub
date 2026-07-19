# AgentHub 下一步开发计划

> v2.1.1 → v2.3.0 迭代规划
>
> 上一轮 (v2.0.2 → v2.1.0) 已完成：Hilt 迁移、模型对比、消息回复、测试扩展、无障碍优化、CI 流水线。

---

## 当前状态

| 维度 | 数据 |
|:-----|:-----|
| 版本 | v2.1.0 (versionCode 15) |
| 源文件 | 83 个 Kotlin 文件 |
| 测试文件 | 14 个 (75+ 用例) |
| 审查修复 | 26 个文件，460 行新增，230 行删除 |
| 已知 P0 问题 | 0 |

---

## v2.1.1 Hotfix — 立即发布（第 1 周）

审查修复已在代码中完成，仅需发布流程。

| # | 任务 | 说明 |
|:-:|:-----|:-----|
| 8.1 | 更新 CHANGELOG | 记录 v2.1.1 全部修复 |
| 8.2 | 版本号升级 | `versionName "2.1.1"` · `versionCode 16` |
| 8.3 | 本地构建验证 | `./gradlew assembleDebug testDebugUnitTest` |
| 8.4 | CI 触发 | tag `v2.1.1` → GitHub Actions |
| 8.5 | GitHub Release | APK + Release Notes |

---

## Sprint 8：多轮对话 + Widget 重构（第 1–2 周）

| # | 优先级 | 任务 | 文件 |
|:-:|:------|:-----|:-----|
| 8.1 | P0 | 多轮对话：OpenAIHttpTransport 维护会话消息历史 | `OpenAIHttpTransport.kt` |
| 8.2 | P0 | Widget 输入重构：EditText → 快捷输入 Activity | `AgentHubWidget.kt` |
| 8.3 | P0 | Widget 快捷发送 Activity | `WidgetInputActivity.kt` (新建) |
| 8.4 | P1 | WebSocket 多轮对话 | `WebSocketTransport.kt` |
| 8.5 | P2 | SuperIsland 真实实现或降级 | `SuperIslandManager.kt` |

---

## Sprint 9：Plugin 执行引擎 + 测试扩展（第 3–4 周）

### Plugin 执行引擎

| # | 优先级 | 任务 | 文件 |
|:-:|:------|:-----|:-----|
| 9.1 | P0 | Plugin 接口定义 | `AgentPlugin.kt` (新建) |
| 9.2 | P0 | PluginExecutor 实现 | `PluginExecutor.kt` (新建) |
| 9.3 | P1 | 内置插件：Web Search, Calculator, File Reader | `plugins/` (新建目录) |
| 9.4 | P1 | PluginScreen 重构为完整管理界面 | `PluginScreen.kt` |

### 测试扩展（14 → 20 文件）

| # | 优先级 | 任务 | 文件 |
|:-:|:------|:-----|:-----|
| 9.5 | P1 | VoiceInputManager 测试 | `VoiceInputManagerTest.kt` (新建) |
| 9.6 | P1 | KeystoreManager 测试 | `KeystoreManagerTest.kt` (新建) |
| 9.7 | P1 | WorkflowEngine 执行测试 | 扩展现有 |
| 9.8 | P2 | PerformanceMonitor 测试 | `PerformanceMonitorTest.kt` (新建) |
| 9.9 | P2 | LocalModelManager 测试 | `LocalModelManagerTest.kt` (新建) |
| 9.10 | P2 | CompareViewModel 执行测试 | 扩展现有 |

---

## Sprint 10：Collaboration + 构建优化（第 5–6 周）

| # | 优先级 | 任务 | 文件 |
|:-:|:------|:-----|:-----|
| 10.1 | P1 | CollaborationManager 实现 | `CollaborationManager.kt` |
| 10.2 | P1 | DeviceSync 增强 | `DeviceSyncManager.kt` |
| 10.3 | P2 | Gradle 版本目录迁移 | `libs.versions.toml` (新建) |
| 10.4 | P2 | ProGuard 规则精简 | `proguard-rules.pro` |
| 10.5 | P3 | 启用 Release Lint | `build.gradle` |
| 10.6 | P3 | 启用并行构建 | `gradle.properties` |

---

## Sprint 11：文档 + 发布 v2.2.0（第 7 周）

| # | 任务 | 文件 |
|:-:|:-----|:-----|
| 11.1 | Plugin 开发指南 | `docs/plugin-guide.md` |
| 11.2 | Transport API 文档 | `docs/transport-api.md` |
| 11.3 | 架构文档更新 | `docs/architecture.md` |
| 11.4 | README 更新 | `README.md` |
| 11.5 | CHANGELOG + 版本号 | `CHANGELOG.md` |
| 11.6 | Release v2.2.0 | tag + CI + GitHub Release |

---

## v2.3.0 — 未来版本（2026-Q3/Q4）

| 优先级 | 任务 | 说明 |
|:------|:-----|:-----|
| P1 | 真正的 Backdrop Blur | View 快照 + RenderEffect 毛玻璃穿透，替代当前半透明模拟 |
| P1 | 本地模型推理 | llama.cpp / MediaPipe 集成，需评估 APK 体积 |
| P2 | Workflow 可视化编辑器 | 拖拽式节点编辑 + 连线 + 实时预览 |
| P2 | Agent Marketplace 集成 | 社区 Agent 配置发现和下载 |
| P2 | 智能通知管理 | 分组、优先级、免打扰 |
| P3 | E2E 加密传输 | 加密从存储扩展到传输层 |
| P3 | Android Auto / Wear OS | 车载和手表平台 |

---

## 依赖关系

```
v2.1.1 Hotfix (已就绪，可直接发布)
  │
  ▼
Sprint 8 (多轮对话 + Widget)
  ├──► 依赖 v2.1.1 修复
  └──► 可与 Sprint 9 部分并行
  │
  ▼
Sprint 9 (Plugin 引擎 + 测试)
  ├──► Plugin 引擎依赖 Sprint 8 的多轮对话
  └──► 测试文件可提前开始
  │
  ▼
Sprint 10 (Collaboration + 构建)
  ├──► Collaboration 依赖 Sprint 8 的 WebSocket 增强
  └──► 构建优化可独立进行
  │
  ▼
Sprint 11 (文档 + 发布)
  └──► 依赖 Sprint 8-10 全部完成

v2.3.0 (独立于 v2.2.0，可并行调研)
```

---

## 风险与缓解

| 风险 | 概率 | 缓解 |
|:-----|:-----|:-----|
| 多轮对话消息膨胀 | 中 | 滑动窗口 + 可配置最大轮数 |
| Plugin 沙箱安全 | 高 | 首批仅内置 Plugin；外部需签名校验 + 权限声明 |
| SuperIsland API 不可用 | 高 | 降级为通用通知 + 重命名 |
| Backdrop Blur 性能 | 中 | API 31+ 启用，低端设备降级 |
| 本地推理 APK 体积 | 中 | 模型动态下载，APK 仅含推理引擎 |
| 测试覆盖回归 | 低 | CI 每次提交自动验证 |