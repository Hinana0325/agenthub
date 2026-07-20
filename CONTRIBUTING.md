# 贡献指南 · Agent Control Center

感谢你对 Agent Control Center 项目的关注！本文档描述如何参与双原生移动端（Android Kotlin + iOS Swift）开发。请阅读以下说明后再提交代码。

> 项目采用双原生架构，Android 与 iOS 各自独立实现，共享 `protocol/` 目录下的永久统一协议层。任何涉及线上格式的改动必须同步更新协议契约并保证双端一致。

---

## 1. 开发环境

### 通用要求

- Git 2.30+
- GitHub 账号（提交 Pull Request）
- 阅读 [`protocol/README.md`](protocol/README.md) 与 [`protocol/transport/auth.md`](protocol/transport/auth.md) 了解共享协议契约

### Android（Kotlin + Jetpack Compose）

| 依赖 | 最低版本 |
|:---|:---|
| JDK | 17 |
| Android SDK | compileSdk 36 |
| Android Studio | Ladybug 或更高（推荐） |
| Kotlin | 2.2.0 |
| Gradle | 8.9+（项目自带 wrapper） |

```bash
# 克隆仓库
git clone https://github.com/Hinana0325/Agent-Control-Center.git
cd Agent-Control-Center/android

# 构建 Debug APK
./gradlew assembleDebug

# 安装到已连接设备
./gradlew installDebug

# 运行单元测试
./gradlew testDebugUnitTest

# 运行插桩测试
./gradlew connectedDebugAndroidTest
```

> 发布构建需要本地 `agentcontrolcenter.keystore`。请参考 `android/keystore.properties.example` 创建凭据文件，该文件已被 `.gitignore` 忽略；CI 环境通过环境变量注入。

### iOS（Swift + SwiftUI）

| 依赖 | 最低版本 |
|:---|:---|
| macOS | 14.0 |
| Xcode | 15.0（含 Swift 5.9、iOS 17 SDK） |
| XcodeGen | 2.0+ |
| iOS 部署目标 | 17.0（SwiftData 要求） |

```bash
# 安装 XcodeGen
brew install xcodegen

# 生成 Xcode 工程（以 project.yml 为单一事实来源）
cd ios
xcodegen generate

# 打开工程
open AgentControlCenter.xcodeproj
```

在 Xcode 的 **Signing & Capabilities** 中配置开发者团队。Bundle Identifier 默认为 `com.agentcontrolcenter.app.ios`，与 Android 包名 `com.agentcontrolcenter.app` 对齐。新增或删除源文件后需重新执行 `xcodegen generate`。

---

## 2. 项目结构

```
Agent-Control-Center/
├── protocol/                         # 永久统一协议层（双端共享，单一事实来源）
│   ├── schemas/                      # 10 份 JSON Schema 契约
│   ├── transport/                    # 4 份传输协议文档（含 auth.md）
│   └── README.md
├── android/                          # Android 原生（Kotlin + Compose）
│   └── app/src/main/java/com/agentcontrolcenter/app/
│       ├── AgentControlCenterApplication.kt
│       ├── MainActivity.kt
│       ├── AgentConnectionService.kt # 前台保活服务
│       ├── AgentControlCenterWidget.kt
│       ├── data/                     # Repository + Room + DataStore
│       ├── provider/                 # Transport 抽象 + 工厂
│       ├── core/security/            # KeystoreManager + CryptoManager
│       ├── runtime/                  # AgentManager + WorkflowEngine
│       ├── mcp/                      # MCP 协议实现
│       ├── plugin/                   # 插件执行器
│       ├── feature/                  # Compose Screens + ViewModels
│       ├── ui/                       # Theme + Components + Adaptive
│       └── navigation/               # 导航图
├── ios/                              # iOS 原生（Swift + SwiftUI）
│   ├── project.yml                   # XcodeGen 配置
│   └── AgentControlCenter/
│       ├── AgentControlCenterApp.swift   # @main 入口
│       ├── AppState.swift                # @Observable 依赖容器
│       ├── Models/                       # 8 文件匹配 10 JSON Schema
│       ├── Security/                     # KeychainManager + CryptoManager
│       ├── Transport/                    # HTTP/SSE + WebSocket
│       ├── Runtime/                      # AgentManager + WorkflowEngine
│       ├── MCP/                          # McpRegistry + McpClient + McpBridge
│       ├── Plugin/                       # PluginExecutor
│       ├── Persistence/                  # SwiftData 5 实体
│       ├── Features/                     # 6 个 SwiftUI Views
│       └── Theme/                        # AppTheme
├── .github/workflows/                 # CI/CD
├── docs/                              # 架构与历史文档
├── CHANGELOG.md
├── CONTRIBUTING.md
└── SECURITY.md
```

---

## 3. 代码规范

### Kotlin（Android）

- 遵循 [Kotlin 编码约定](https://kotlinlang.org/docs/coding-conventions.html)
- 使用 Jetpack Compose Material 3 组件
- 所有界面必须支持自适应布局（手机 / 平板 / 折叠屏，三档断点）
- 使用 `StateFlow` + `collectAsState()` 实现响应式 UI
- 优先使用 `sealed interface` 表达 UI 状态
- 包路径统一为 `com.agentcontrolcenter.app.*`
- 加密相关代码位于 `core/security/`，密钥别名固定为 `agentcontrolcenter_master_key`

### Swift（iOS）

- 遵循 [Swift API Design Guidelines](https://swift.org/documentation/api-design-guidelines/)
- 使用 SwiftUI + `@Observable` 宏（iOS 17+）
- 异步任务使用 `async/await` 与 `AsyncStream`，并发检查设为 `minimal`
- 数据模型必须与 `protocol/schemas/` 下的 JSON Schema 一一对应
- 加密实现位于 `Security/KeychainManager.swift`，主密钥标签为 `com.agentcontrolcenter.app.master-key`
- 工程配置以 `project.yml` 为单一事实来源，禁止手动修改 `.xcodeproj`

### 协议层（双端共享）

- `protocol/` 目录是双端线上格式的唯一事实来源
- 修改任一 Schema 必须同步更新双端实现，并保证向后兼容
- `AKS:` 与 `AH1:` 前缀格式必须双端严格一致（详见 [`SECURITY.md`](SECURITY.md)）

---

## 4. 测试

### Android

| 类型 | 路径 | 框架 |
|:---|:---|:---|
| 单元测试 | `app/src/test/` | JUnit 4 + kotlinx-coroutines-test |
| 插桩测试 | `app/src/androidTest/` | Espresso + Compose Testing |

提交前必须运行 `./gradlew testDebugUnitTest` 并保证全部通过。

### iOS

- 单元测试位于 `AgentControlCenterTests/`，使用 `XCTest`
- 在 Xcode 中按 `Cmd + U` 运行测试
- 流式渲染、WebSocket 重连、加密解密等核心逻辑必须有对应测试覆盖

---

## 5. 提交流程

1. Fork 仓库 [Hinana0325/Agent-Control-Center](https://github.com/Hinana0325/Agent-Control-Center)
2. 创建功能分支：`git checkout -b feature/my-feature`
3. 提交时使用清晰的提交信息：`git commit -m "feat: add my feature"`
4. 推送到你的 Fork：`git push origin feature/my-feature`
5. 向 `main` 分支发起 Pull Request

### 提交信息规范

采用 [Conventional Commits](https://www.conventionalcommits.org/)：

| 前缀 | 用途 |
|:---|:---|
| `feat:` | 新功能 |
| `fix:` | 缺陷修复 |
| `docs:` | 仅文档变更 |
| `refactor:` | 既非修复也非新功能的重构 |
| `test:` | 新增或修复测试 |
| `chore:` | 构建流程、工具链等 |
| `proto:` | 协议层（`protocol/`）变更 |

> 涉及双端的改动建议拆分为两个提交（Android / iOS 各一），并在 PR 描述中说明协议层是否受影响。

---

## 6. 问题反馈

- **缺陷报告**：使用 [Bug Report](https://github.com/Hinana0325/Agent-Control-Center/issues/new?template=bug_report.md) 模板
- **功能建议**：使用 [Feature Request](https://github.com/Hinana0325/Agent-Control-Center/issues/new?template=feature_request.md) 模板
- **安全漏洞**：请勿公开 Issue，参见 [`SECURITY.md`](SECURITY.md)

---

## 7. 许可证

提交代码即表示你同意将其以 [MIT License](LICENSE) 授权。
