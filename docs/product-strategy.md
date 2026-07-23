# Agent Control Center 产品策略

> 最后更新：v4.8.0（2026-07-23）
> 状态：策略锚定文档，驱动后续版本规划

## 一、产品定位

**面向 AI 重度使用者与开发者的移动端统一 Agent 控制台** — 把散落在多个客户端/网页里的 Agent 交互，收敛到一个手机入口，并提供跨 Agent 编排能力。

### 核心价值主张

单一移动端入口统一操控异构 AI Agent（目前 8 种 AgentType：Hermes / OpenClaw / OpenCode / OpenAI 兼容 / OpenRouter / 小米 MiMo / Ollama/LM Studio 本地模型 / ComfyUI / OpenWebUI），并通过 DAG 工作流引擎实现跨 Agent 编排。

### 差异化壁垒

1. **双原生 + 共享协议层架构** — Android Kotlin/Compose + iOS Swift/SwiftUI 双端原生，通过 `protocol/` 统一契约，而非跨平台框架折中
2. **8 种 AgentType 异构接入** — 覆盖云端 LLM、本地 LLM、文生图、OpenAI 兼容协议，单一 App 内完成跨类型协作
3. **DAG 工作流引擎** — 多 Agent 编排能力，从"聊天工具"升级为"生产力平台"
4. **E2E 端到端加密** — `AH1:` 传输加密 + `AKS:` 静态存储，PBKDF2 600000 轮，Android Keystore + iOS Keychain
5. **端侧能力路线** — 规划本地推理（llama.cpp/MLX/MediaPipe），从"控制"走向"执行"

## 二、目标用户

### 核心层（now）
**同时使用 2+ 种 AI Agent 的技术 power user**
- 自部署 Ollama/ComfyUI + 用 OpenAI/Claude API 的开发者
- 痛点：Agent 散落在 N 个客户端，无法跨 Agent 编排，移动端无统一入口
- 特征：技术能力强，付费意愿强，口碑传播力大

### 扩展层（next）
**独立开发者 / 小团队**
- 需要多 Agent 协作完成代码审查、文档翻译、研究汇总等工作流
- 痛点：现有工作流工具（n8n/Zapier）面向 SaaS 集成，不面向 AI Agent 编排
- 特征：需要可复用的工作流模板，需要团队协作

### 泛化层（future）
**普通 AI 用户**
- 需要极简入口和场景化模板（"写周报"、"做海报"）
- 痛点：现有 AI App 功能单一，无法组合多步骤任务
- 特征：不关心 Agent 类型，只关心场景结果

## 三、三条增长主线

### 主线 A：Agent 生态广度（横向）— 从 8 种到 N 种

```
现状: 8 种 AgentType，覆盖 LLM + 文生图
缺口: 语音 Agent、RAG/知识库 Agent、MCP 工具服务器仅框架
下一步:
  - 接入语音类 Agent（本地 Whisper + 云端 TTS）— 复用已有 VoiceChatScreen 骨架
  - 把 MCP 从"连接框架"升级为"工具市场"— 用户可挂载任意 MCP server 作为 Agent 能力扩展
  - 新增 RAG Agent 类型（接向量库）— 从"聊天"走向"知识工作"的关键
```

**战略意义**：Agent 类型数是这类产品的核心护城河。每多一种类型，用户切换成本就高一档。

### 主线 B：编排能力深度（纵向）— 从 DAG 到可视化

```
现状: WorkflowEngine 支持串行 DAG，4 节点类型，无可视化编辑器
缺口: 无并行执行、无持久化历史、无拖拽编辑器、CollaborationManager 全 stub
下一步:
  v4.9.0 - Workflow 持久化 + 历史记录
  v5.0.0 - 可视化拖拽编辑器（Canvas + 节点连线）
  v5.1.0 - 并行分支执行 + 条件分支（if/else 节点）
  v5.2.0 - 轻量协作（填平 iOS CollaborationManager stub）
```

**战略意义**：工作流是从"聊天工具"升级为"生产力平台"的分水岭。可视化编辑器是用户感知最强的差异化。

### 主线 C：端侧能力（纵深）— 从控制到推理

```
现状: LocalModel 仅发现端点，无端侧推理
缺口: DEV_PLAN v2.3.0 P1 列了 llama.cpp/MLX/MediaPipe 但未实现
下一步:
  - iOS: 接入 MLX Swift（Apple Silicon 优化，iPhone 可跑 4B 量化模型）
  - Android: 接入 MediaPipe LLM Inference API（官方支持，兼容性好）
  - 统一为"LocalModel v2"— 从"转发请求到本地服务"升级为"App 内直接推理"
```

**战略意义**：端侧推理是隐私 + 离线场景的杀手锏，也是与云端 AI 客户端的本质差异。工程量大，作为 v5.0+ 重点。

## 四、版本路线图

### v4.9.0 — 编排深化（P0：补高价值缺口）

| 优先级 | 项目 | 理由 |
|--------|------|------|
| **P0** | Workflow 持久化 + 执行历史 | 当前执行完即丢，无法复用，是"可用"到"好用"的关键 |
| **P0** | 填平 iOS CollaborationManager stub | 双端功能对齐是产品承诺 |
| **P1** | 证书锁定填真实 pin | 框架已建，pin 为空=形同虚设，安全是差异化卖点 |
| **P1** | Marketplace 从只读升级为可订阅 | 加"收藏/订阅更新"提升留存 |
| **P2** | 文档大扫除 | architecture.md 仍写 v2.1.3，DEV_PLAN Sprint 标 v2.2.0 |

### v5.0.0 — 编排可视化 + 端侧推理 MVP

| 优先级 | 项目 | 理由 |
|--------|------|------|
| **P0** | Workflow 可视化拖拽编辑器 | 用户感知最强的差异化，分水岭特性 |
| **P1** | 端侧推理 MVP（Android MediaPipe + iOS MLX） | 隐私 + 离线杀手锏 |
| **P2** | RAG Agent 类型（接向量库） | 从"聊天"走向"知识工作" |

### v5.1.0+ — 生态扩展

| 优先级 | 项目 | 理由 |
|--------|------|------|
| **P1** | Workflow 并行分支 + 条件节点 | 编排能力完整化 |
| **P1** | MCP 工具市场 | Agent 能力扩展机制 |
| **P2** | 轻量团队协作 | 从个人工具走向团队工具 |
| **P2** | 语音 Agent | 复用 VoiceChatScreen 骨架 |

## 五、工程化策略

### 1. 用户研究
- 补齐 `docs/personas.md`，定义 2-3 个核心 persona
- 后续功能优先级用 persona 痛点驱动，而非技术债驱动

### 2. 测试策略
- 当前 Android 16 测试文件、iOS 13 文件，ViewModel 层几乎无测试
- 优先给 ChatViewModel / AgentsViewModel / WorkflowViewModel 补关键路径测试
- 引入 instrumented 测试（UI Automator / XCUITest）— 当前完全空白

### 3. 协议一致性 CI
- 现有 `check-version-sync.sh` 解决版本号漂移
- 新增 `check-protocol-sync.sh`：对比 `protocol/schemas/*.json` 与双端 model 定义的字段一致性
- 双原生架构最大的长期风险，值得 CI 卡死

### 4. detekt baseline 收敛
- 当前 367 个存量违规被 baseline 接受，掩盖质量信号
- 每版本清理 20-30 个，半年内清空
- 优先清理 LongMethod / CyclomaticComplexity（可读性债）

### 5. 发布节奏
- 改为双周发布：奇数周发 patch（hotfix + 小改进），偶数周发 minor（新功能）
- 配合 FeatureFlag，主功能可暗发布到 main，tag 时再点亮

## 六、商业化路径（可选）

| 阶段 | 模式 | 触发条件 |
|------|------|---------|
| 现在 | 完全免费开源 | 个人项目，积累口碑 |
| v5.0 后 | freemium | 本地推理/基础工作流免费；云端同步、团队协作、高级 Marketplace 为 Pro |
| 端侧推理上线后 | Pro 订阅 | 端侧模型下载、高性能推理引擎为付费特性 |

**原则**：不过早商业化。先用 v5.0 的可视化工作流 + 端侧推理建立差异化壁垒，再谈付费。

## 七、一句话总结

**技术基建已经扎实（双原生 + 协议层 + 安全 + 8 种 Agent），下一步重心应从"扩 Agent 类型"转向"编排深度 + 用户研究 + 工程化收敛"，用 Workflow 可视化编辑器和端侧推理建立真正的产品差异化。**
