# AgentHub — Android 17 液态玻璃升级规范（冻结归档版）

> **文档状态：已冻结（FROZEN）**
> 冻结时间：2026-07-17
> 最终提交：`9968db0`
> 本文档记录 Liquid Glass 升级的完整技术规范与实现结果，作为历史归档。
> 后续如需新增功能，请新建独立设计文档，不再追加至本文件。

---

## 1. 项目背景

基于 Android 17 系统级模糊设计 + Material 3 Expressive 设计语言，将 AgentHub（Kotlin 2.1 + Jetpack Compose 原生 Android）的 UI 全面升级为液态玻璃风格。

**工具链**：AGP 8.9.0 / compileSdk 36 / Kotlin 2.1.0 / Compose BOM 2025.01.01 / 0 Java 文件（纯 Kotlin + Compose）。

---

## 2. 最终架构概览

### 2.1 主题系统（简化后）

| 维度 | 设计 |
|------|------|
| 主题模式 | `ThemeMode { Light, Dark, System }` —— 仅决定深/浅色基底 |
| 视觉风格 | **液态玻璃常驻**（`isGlass = true`，不再作为独立模式） |
| 强调色 | 固定 `AccentBlue`（已移除多色选择器与 `AccentPalettes`） |
| 自定义主题 | **已移除**（取色器/自定义字号/圆角/`buildCustomColorScheme` 全部删除） |
| 全局字号 | 保留（`small/medium/large` → 0.875× / 1.0× / 1.15× 缩放 `Typography`） |
| `AgentHubTheme` 签名 | `(themeMode, fontSize, content)` —— 极简 |

### 2.2 玻璃引擎文件清单

| 文件 | 职责 |
|------|------|
| `ui/theme/GlassModifier.kt` | `glassBackground()`（tint + 色散 + 动态光泽 + 边缘光 + 深度阴影）、`GlassBox`/`GlassTopAppBar`/`GlassNavigationBar`/`GlassNavigationRail`/`GlassCard(2)`/`GlassFloatingActionButton`/`GlassPill`/`GlassDropdownMenu`/`GlassDropdownMenuItem`/`GlassModalBottomSheet` |
| `ui/theme/GlassMotion.kt` | `SpringBounce/Smooth/Exit`、`GlassEnter/ExitTransition`、`rememberFloatingOffset`、`rememberMorphCornerShape`、`glassPress`/`glassClickable`、`lerpDp` |
| `ui/theme/GlassBackdrop.kt` | 多层径向渐变 + `rememberInfiniteTransition` 缓慢漂移光晕 |
| `ui/theme/Color.kt` | 模糊级别/光晕/深度阴影/形状/色散/背景渐变令牌 |
| `ui/theme/Theme.kt` | `AgentHubTheme` 提供全部玻璃令牌（`CompositionLocal`）+ 根背景 `GlassBackdrop` |

### 2.3 数据持久化

| 存储 | 用途 | 状态 |
|------|------|------|
| DataStore (`settings`) | `themeMode` / `fontSize` / `e2eEnabled` / `e2eKey` | ✅ 正确读写 |
| Room (`agenthub.db` v4) | 会话/消息/Agent 配置/活动日志/插件 | ✅ 正确读写 |

---

## 3. 设计核心要素（已实现）

1. **半透明毛玻璃** —— 气泡、卡片、输入栏、顶部栏、导航栏、下拉菜单、底部弹窗全部玻璃化
2. **色散/折射** —— 边缘红/青偏移（`drawBehind` 内 `drawRect`，模拟色散）
3. **动态光泽** —— `Brush.linearGradient` 随 `rememberInfiniteTransition` 漂移高光
4. **深度阴影** —— `shadow(clip = false)` 绘制于 `clip(shape)` 之前，保留圆角外阴影
5. **边缘光** —— 4 边 `drawRect` 细线模拟玻璃边缘折射
6. **弹簧物理** —— `SpringBounce/Smooth/Exit` 驱动按压、入场、退出
7. **形状变形** —— `rememberMorphCornerShape` 弹性圆角过渡
8. **浮动药丸** —— `GlassPill` 用于录制/状态/快捷动作
9. **Material 3 Expressive** —— 更重标题字重、透明表面层次

---

## 4. 关键技术修正记录

### 4.1 玻璃 tint 方角溢出（已修复）
`glassBackground` 的 `drawBehind { drawRect(...) }` 画的是**完整矩形** tint，作为外层 modifier 不被子 `Surface` 的圆角 `clip` 裁剪，导致圆角表面溢出方角。
**修正**：`shadow()` → `clip(shape)` → `drawBehind` 的顺序（阴影在裁剪外，tint 在裁剪内）。

### 4.2 FAB 阴影被裁剪（已修复）
尾部 `Modifier.clip(shape)` 切掉了 FAB 自身的 `shadow(12.dp)`。
**修正**：`shadow()` 在 `glassBackground` 之前；内层 `Box(matchParentSize().clip(shape).clickable())` 承载波纹/内容。

### 4.3 全宽栏圆角缺口（已修复）
`clip(shape)` 圆角四角，导致 `GlassTopAppBar`/`GlassNavigationBar`/`ChatInputBar` 边到边时出现缺口。
**修正**：全宽栏用 `RoundedCornerShape(0.dp)`；`ChatInputBar` 用仅顶部圆角。

### 4.4 自定义主题不生效（已修复）
`MainActivity` 调 `AgentHubTheme` 时漏传自定义主题参数与 `fontSize`，导致存了不生效。
**修正（后简化）**：直接移除自定义主题体系，`AgentHubTheme` 只收 `themeMode + fontSize`。

### 4.5 内容模糊 Bug（已规避）
`RenderEffect`/`Modifier.blur` 模糊的是自身子树（含文字），会导致文字被糊掉。
**修正**：`glassBackground` 不模糊内容，仅做半透明叠加；磨砂观感由 `GlassBackdrop` 透色实现。

### 4.6 双重阴影（已修复）
`GlassCard` 传 `CardDefaults.cardElevation()` 给 `Card`，又叠加 `glassBackground` 的 8.dp 阴影。
**修正**：玻璃模式下 `Card`/`DropdownMenu` 的 `elevation`/`tonalElevation`/`shadowElevation` 归零。

### 4.7 MessageBubble 滚动重播（已修复）
`AnimatedVisibility(visible = true, enter = GlassEnterTransition)` 每次滚入视口都重播入场动画。
**修正**：`ChatContent` 持有 `seenMessageIds`；`enter = if (hasEntered) EnterTransition.None else GlassEnterTransition` 驱动入场过渡（非 visibility），仅新增消息动画。

---

## 5. 渲染热路径优化（已实现）

| 优化点 | 改动 | 收益 |
|--------|------|------|
| `glassBackground` 每帧分配 | 6 个常量色表/颜色 `remember` 到帧外 | 每个玻璃表面每帧少分配 6 对象 |
| 小表面光泽动画 | `animateShine` 开关；`MessageBubble`/`GlassPill` 传 `false` | 消除聊天列表数十个常驻无限动画循环 |
| `GlassBackdrop` 每帧色表 | 4 个渐变色表 `remember` 化 | 根背景 ~60fps 不再每帧新建 List |
| `bubbleShape` | `remember(isUser) { RoundedCornerShape(...) }` | 流式更新时不重分配圆角 Shape |
| `GlassEnter/ExitTransition` | `val =` 替代 `val get()` | 所有 `AnimatedVisibility` 共享实例 |

---

## 6. 应用内更新模块（已实现）

| 组件 | 说明 |
|------|------|
| `data/update/UpdateManager.kt` | GitHub Releases API 检查新版本 + `DownloadManager` 后台下载 APK |
| `SettingsScreen` | 「关于」区域「检查更新」入口，对话框展示 changelog + 下载按钮 |
| 版本来源 | `PackageManager.getPackageInfo().versionName`（不依赖 `BuildConfig`） |
| 权限 | `REQUEST_INSTALL_PACKAGES`（已加入 manifest） |
| 限制 | GitHub API 未认证限速 60 次/小时/IP |

---

## 7. 兼容性

| API 级别 | 玻璃实现 | 色散 |
|----------|----------|------|
| ≥ 31 (Android 12+) | `blur()` + 光泽 + 深度阴影 | ✅ 边缘红/青偏移 |
| 24–30 | `blur()` + 光泽 + 深度阴影 | ❌ 降级（保持玻璃感） |

> 用户确认：应用不会安装在低端设备，可放心使用 API 31+ 效果。

---

## 8. 已知限制与未实现项

| 项 | 状态 | 说明 |
|----|------|------|
| 真正的 backdrop blur | 限制 | 纯 Compose 无法模糊"应用背后的内容"；当前用 `GlassBackdrop` 半透明叠加模拟。未来可用 View 级快照 + `RenderEffect` |
| `customCornerRadius` 接入 Shape | 未实现 | 圆角自定义已随自定义主题一起移除 |
| `glassPress` 统一发送按钮 | 可选 | 发送按钮当前用 `scaleOnPress`，已有按压反馈 |
| 列表项内联 `RoundedCornerShape` | 可选 | 可提升为模块级常量（优先级低） |
| `GlassBox` | 死代码 | 已定义但无调用方，保留备用 |

---

## 9. 设计原则（冻结）

1. **深度感优先** —— 模糊深度 + 多层阴影 + 光泽层次
2. **动态生命力** —— 光泽流动 + 微浮动 + 触控反馈
3. **色散真实感** —— 边缘色散折射是液态玻璃灵魂
4. **弹簧自然感** —— 所有交互动画使用 `spring()` 而非 `tween`
5. **壁纸透色** —— 玻璃透出 `GlassBackdrop` 背景色彩
6. **一致性** —— 所有组件遵循统一设计令牌系统
7. **性能** —— 每帧分配最小化；小表面关闭常驻动画

---

## 10. 提交历史摘要

| 提交 | 内容 |
|------|------|
| `eccb3f2` | Liquid Glass 初始落地：玻璃引擎 + 全组件 + 色散/光泽/阴影 |
| `9ba9acc` | 渲染修复：clip tint to shape / FAB 阴影 / 透明主题 / 全宽栏 |
| `83c3201` | ChatInputBar 顶部圆角修复 |
| `3091b2e` | 打磨：MessageBubble 滚动重播 + 双重阴影 |
| `f8d1f76` | 渲染热路径优化：每帧分配消除 + 小表面关光泽 |
| `5157a16` | 持久化修复：自定义主题 + 字号在启动时应用 |
| `b18ad63` | 主题简化：液态玻璃常驻，移除强调色与自定义主题 |
| `9968db0` | 应用内更新模块：GitHub Releases 检查 + 下载 |

---

> **本文档已冻结。** 如需修改玻璃系统或新增功能，请新建独立设计文档（如 `UPDATE_MODULE_SPEC.md`、`THEME_V2_SPEC.md`），并在新文档中引用本归档作为背景。
