# AgentHub — Android 17 液态玻璃全面升级计划（Compose 版）

> 基于 Android 17 系统级模糊设计 + Material 3 Expressive 设计语言
> **适配新版架构**：Kotlin + Jetpack Compose 原生 Android（非旧版 JS/PWA）
> 目标：打造令人印象深刻的液态玻璃 UI，完整对标 Android 17 设计规范

---

## 架构变更说明

> ⚠️ **重要**：原升级计划针对旧版 `liquid-glass.css` + `glass-components.js`（JavaScript/PWA）。
> 用户已将整个项目重写为 **Kotlin 2.1 + Jetpack Compose**（提交 `c1a82b8`），旧版文件已废弃。
> 本计划为 **Compose 重写版**，聚焦 `ui/theme/` 下的玻璃系统增强。

### 新版玻璃系统现状

| 文件 | 现状 | 差距 |
|------|------|------|
| `ui/theme/Theme.kt` | 已有 `ThemeMode.LiquidGlass` + `LocalIsGlass` | 缺少玻璃设计令牌系统 |
| `ui/theme/GlassModifier.kt` | `glassBackground()`（blur + tint + border）、`GlassBox`/`GlassTopAppBar`/`GlassNavigationBar`/`GlassCard` | 无**色散/折射**、无**动态光泽**、无**深度阴影**、无**边缘光** |
| `ui/theme/Color.kt` | 8 套强调色 + 玻璃专用色（`GlassSurface`/`GlassBorder`/`GlassHighlight`/`GlassBackdrop`） | 缺**模糊级别**、**光晕**、**形状**令牌 |
| `ui/chat/ChatScreen.kt` | 消息气泡/输入栏用**不透明 Surface**（玻璃模式未生效） | 气泡、输入栏、侧边栏需玻璃化 |
| 动画 | `tween`/`infiniteRepeatable`（脉冲、旋转） | 无 **`spring()`** 弹簧物理、无形状变形、无微浮动 |

### Android 17 设计核心要素（升级目标）

1. **系统级模糊** — 所有组件采用 frosted glass，透出背景色彩
2. **半透明毛玻璃** — 气泡、卡片、输入栏全部玻璃化
3. **色散/折射** — 边缘色散是液态玻璃灵魂（`RenderEffect` + `OffsetEffect`，API 31+）
4. **动态光泽** — 玻璃表面高光随交互/时间流动
5. **深度阴影** — 多层 `shadowElevation` 营造悬浮层次
6. **边缘光** — 模拟玻璃边缘折射
7. **弹簧物理** — 所有交互动画用 `spring()` 而非 `tween`
8. **形状变形** — squircle/pill/rounded 平滑过渡
9. **浮动药丸** — 录制/FAB 控件采用浮动 pill
10. **Material 3 Expressive** — 更重标题字重、丰富色彩层次

---

## 实施方案

### 阶段一：增强 `ui/theme/GlassModifier.kt` — Android 17 玻璃引擎

**文件**：`android/app/src/main/java/com/agenthub/app/ui/theme/GlassModifier.kt`（重写增强）

#### 1.1 设计令牌 CompositionLocal
```kotlin
val LocalGlassBlurRadius    // 已有，扩展为多级别
val LocalGlassTintAlpha     // 已有
val LocalGlassBorderAlpha   // 已有
val LocalGlassHighlightAlpha // 新增：动态光泽强度
val LocalGlassDispersion    // 新增：色散强度（API 31+）
val LocalGlassShadowElevation // 新增：深度阴影
val LocalGlassShape         // 新增：形状令牌（squircle/rounded/pill）
```

#### 1.2 增强 `glassBackground()` — 多层玻璃
- **多层模糊合成**：`blur(radius)` + 饱和度提升（用 `graphicsLayer` 或叠加）
- **动态光泽层**：`drawBehind` + `Brush.linearGradient` 动画高光
- **边缘光**：4 边 `drawRect` 细线（已有，增强为渐变折射）
- **深度阴影**：`shadow()` 多层叠加（ambient + diffuse）
- **壁纸透色**：tint 颜色从 `GlassBackdrop` 取，非纯白

#### 1.3 色散/折射（Android 17 灵魂）
```kotlin
// API 31+ 用 RenderEffect
if (Build.VERSION.SDK_INT >= 31) {
    val renderEffect = RenderEffect.createBlurEffect(...)
        .asComposeRenderEffect()
    graphicsLayer { this.renderEffect = ... }
}
// 低版本降级为 blur()
```
- 用 `OffsetEffect` 做 RGB 分离色散（边缘彩色折射）
- 低版本（<31）降级为简单 `blur()`，保持玻璃感

#### 1.4 新增组件
- `GlassSurfaceBox` — 带动态光泽 + 深度阴影的玻璃容器
- `GlassFloatingActionButton` — 液态浮动 FAB（微浮动 + 弹簧）
- `GlassPill` — 浮动药丸控件（录制/FAB）
- `GlassDropdownMenu` — 玻璃下拉菜单
- `GlassModalBottomSheet` — 玻璃底部弹窗

### 阶段二：增强 `ui/theme/Color.kt` — 设计令牌

**文件**：`android/app/src/main/java/com/agenthub/app/ui/theme/Color.kt`（增加令牌）

```kotlin
// 玻璃模糊级别（对应 Android 17 5 级模糊）
val GlassBlurXs = 8.dp
val GlassBlurSm = 16.dp
val GlassBlurMd = 24.dp
val GlassBlurLg = 40.dp
val GlassBlurXl = 60.dp

// 动态光泽色
val GlassShineLight = Color(0x66FFFFFF)
val GlassShineDark = Color(0x22FFFFFF)

// 深度阴影（ambient + diffuse 多层）
val GlassShadowAmbient = ...
val GlassShadowDiffuse = ...

// 形状令牌（Material 3 Expressive 子集）
val GlassShapeXs = RoundedCornerShape(8.dp)
val GlassShapeSm = RoundedCornerShape(12.dp)
val GlassShapeMd = RoundedCornerShape(16.dp)
val GlassShapeLg = RoundedCornerShape(24.dp)
val GlassShapePill = RoundedCornerShape(100.dp)
val GlassShapeSquircle = RoundedCornerShape(28.dp) // 近似超椭圆

// 色散强度
val GlassDispersionLight = 1.5f
val GlassDispersionDark = 2.0f
```

### 阶段三：新增 `ui/theme/GlassMotion.kt` — 动画系统

**文件**：`android/app/src/main/java/com/agenthub/app/ui/theme/GlassMotion.kt`（新建）

```kotlin
// 弹簧物理参数（Android 17 风格）
val SpringBounce = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
val SpringSmooth = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
val SpringExit = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)

// 形状变形（squircle -> pill）
fun morphShape(from: Shape, to: Shape, progress: Float): Shape

// 微浮动动画
@Composable
fun rememberFloatingOffset(): State<Offset>  // 持续缓慢上下浮动

// 波纹反馈（替代 scaleOnPress）
@Composable
fun Modifier.glassRipple(): Modifier

// 弹性入场
val GlassEnterTransition = fadeIn() + scaleIn(initialScale = 0.9f, animationSpec = SpringBounce)
```

### 阶段四：新增 `ui/theme/GlassBackdrop.kt` — 背景层

**文件**：`android/app/src/main/java/com/agenthub/app/ui/theme/GlassBackdrop.kt`（新建）

```kotlin
// Android 17 风格动态背景：多层径向渐变 + 缓慢漂移光晕
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme()
) {
    // 用 GlassBackdropGradientTop/Bottom 颜色构建氛围背景
    // 跟随时间缓慢漂移（rememberInfiniteTransition）
}
```

### 阶段五：应用升级到关键组件

#### 5.1 `ChatScreen.kt` — 核心升级
- **MessageBubble**：玻璃模式下用 `GlassSurfaceBox`（半透明 + 色散 + 光泽），用户气泡用 `primary` 色调玻璃
- **ChatInputBar**：玻璃输入栏（`GlassSurfaceBox` + 聚焦光晕），发送按钮改用 `GlassFloatingActionButton`
- **TabletChatLayout 侧边栏**：`GlassSurfaceBox` 替代 `Surface`
- **SearchOverlay**：玻璃搜索栏 + 玻璃结果项

#### 5.2 其他屏幕
- `SessionsScreen.kt`：会话项玻璃卡片
- `SettingsScreen.kt`：设置卡片玻璃化
- `ActivityScreen.kt`：活动项玻璃卡片
- `AgentsScreen.kt`：代理卡片玻璃化
- `ThemeCustomizerScreen.kt`：实时玻璃预览

#### 5.3 导航/全局
- `GlassNavigationBar`：活跃指示器弹簧动画 + 玻璃凸起
- `AppNavigation.kt`：屏幕切换弹性过渡

### 阶段六：Theme.kt 增强

**文件**：`ui/theme/Theme.kt`（增强）

- `AgentHubTheme` 内提供玻璃设计令牌（`LocalGlassBlurRadius` 分级、`LocalGlassDispersion` 等）
- `LiquidGlass` 模式自动设置 `GlassBackdrop` 为根背景
- 玻璃模式下的 `ColorScheme` 增强（表面更透明、边框更亮）

---

## 实施顺序

| 步骤 | 内容 | 文件 | 预估行数 |
|------|------|------|----------|
| 1 | 增强 `GlassModifier.kt` — 设计令牌 + 多层玻璃 + 色散 + 动态光泽 + 深度阴影 | `GlassModifier.kt` | ~300 行 |
| 2 | 增强 `Color.kt` — 模糊级别/光泽/阴影/形状/色散令牌 | `Color.kt` | ~60 行 |
| 3 | 新建 `GlassMotion.kt` — 弹簧/形状变形/微浮动/波纹 | `GlassMotion.kt` | ~180 行 |
| 4 | 新建 `GlassBackdrop.kt` — 动态背景层 | `GlassBackdrop.kt` | ~80 行 |
| 5 | 应用 `ChatScreen.kt` — 气泡/输入栏/侧边栏玻璃化 | `ChatScreen.kt` | ~150 行修改 |
| 6 | 应用其他屏幕 — Sessions/Settings/Activity/Agents 玻璃化 | 4 个文件 | ~200 行修改 |
| 7 | `Theme.kt` 增强 — 玻璃令牌 + GlassBackdrop 根背景 | `Theme.kt` | ~40 行修改 |

**总预估**：~1000 行（核心玻璃引擎 ~620 行 + 应用 ~390 行）

---

## 设计原则

1. **深度感优先** — 模糊深度 + 多层阴影 + 光泽层次
2. **动态生命力** — 光泽流动 + 微浮动 + 触控反馈
3. **色散真实感** — 边缘色散折射是液态玻璃的灵魂（API 31+ 用 RenderEffect，低版本降级）
4. **弹簧自然感** — 所有交互动画使用 `spring()` 而非 `tween`
5. **壁纸透色** — 玻璃透出 `GlassBackdrop` 背景色彩
6. **一致性** — 所有组件遵循统一的设计令牌系统
7. **性能** — 色散/渲染效果仅在 API 31+ 启用，低版本降级为 `blur()`

---

## 兼容性

| API 级别 | 玻璃实现 | 色散 |
|----------|----------|------|
| ≥ 31 (Android 12+) | `blur()` + 光泽 + 深度阴影 | ✅ `RenderEffect` + `OffsetEffect` |
| 24–30 | `blur()` + 光泽 + 深度阴影 | ❌ 降级为纯 blur（保持玻璃感） |

> 用户确认：应用不会安装在低端设备，可放心使用 API 31+ 效果。

---

## 实现进度与已知限制（开发记录）

### 已落地（代码侧）

| 阶段 | 文件 | 状态 |
|------|------|------|
| 一 | `GlassModifier.kt` | ✅ 增强 `glassBackground()`（tint + 色散 + 动态光泽 + 边缘光 + 深度阴影）；新增 `GlassBox`/`GlassTopAppBar`/`GlassNavigationBar`/`GlassNavigationRail`/`GlassCard(2)`/`GlassFloatingActionButton`/`GlassPill` |
| 二 | `Color.kt` | ✅ 模糊级别、光晕、深度阴影、形状、色散、背景渐变令牌 |
| 三 | `GlassMotion.kt` | ✅ `SpringBounce/Smooth/Exit`、`GlassEnter/ExitTransition`、`rememberFloatingOffset`、`rememberMorphCornerShape`、`Modifier.glassPress/glassClickable`、`lerpDp` |
| 四 | `GlassBackdrop.kt` | ✅ 多层径向渐变 + `rememberInfiniteTransition` 缓慢漂移 |
| 五 | `ChatScreen.kt` | ✅ `MessageBubble` / `ChatInputBar` 玻璃化；搜索浮层 `TopAppBar` → `GlassTopAppBar` |
| 五 | `WorkflowScreen.kt` | ✅ 两处原生 `TopAppBar` → `GlassTopAppBar` |
| 五 | 其余屏幕 | ✅ `Sessions/Settings/Activity/Agents/Insights/Market/Sync/Plugin/ThemeCustomizer` 顶部栏已为 `GlassTopAppBar`；`AppNavigation` 已用 `GlassNavigationBar`/`GlassNavigationRail` |
| 六 | `Theme.kt` | ✅ `AgentHubTheme` 提供全部玻璃令牌 + `LiquidGlass` 模式根背景 `GlassBackdrop` |

### 关键技术修正（实现中发现）

1. **内容模糊 Bug（已修复）**
   原设计把 `BlurEffect` 的 `renderEffect` 直接挂在 `glassBackground` 的**内容子树上**，
   而 Compose 的 `RenderEffect`/`Modifier.blur` 模糊的是**自身子树**（含文字），
   会导致气泡文字、顶部栏标题被糊掉。
   **修正**：`glassBackground` 不再模糊内容，仅做半透明 tint + 色散 + 光泽 + 边缘光 + 阴影；
   可选模糊作为**独立背景层**（在 `GlassBox` 内 `matchParentSize()` 置于内容之后）承载。

2. **真实背景模糊的架构限制**
   纯 Compose 无法对"应用背后的内容"做真正的 backdrop blur（`RenderEffect` 只能模糊本层内容，
   而背景层是透明的，模糊后不可见）。
   本项目的磨砂观感由**半透明叠加 `GlassBackdrop`** 实现（这是 M3 Expressive 的标准做法）。
   若需对滚动内容做真正背景模糊，未来可引入 View 级快照（截图 + `RenderEffect`）或
   `graphicsLayer` 捕获背景副本的方案。

3. **Scaffold 透明度修正（已修复）**
   `buildColorScheme` 在玻璃模式原把 `background`/`surface` 设为约 90% 不透明白
   （`GlassSurfaceLight`/`GlassSurfaceDark`），会被 `Scaffold` 的容器色盖住 `GlassBackdrop`。
   **修正**：玻璃模式下 `background`/`surface` 设为 `Color.Transparent`，使动态背景透出，
   各玻璃组件再以自身半透明 tint 叠加。

### 待办 / 可选打磨

- [x] `GlassDropdownMenu` / `GlassDropdownMenuItem` 已新建（`GlassModifier.kt`），并迁移
      `ChatScreen`、`AgentsScreen` 的全部 `DropdownMenu` 调用点；共享 `MutableInteractionSource`
      使 `glassPress` 弹簧缩放真正响应指针按压。
- [x] `GlassModalBottomSheet` 已新建（`GlassModifier.kt`）—— 磨砂半透明底部弹窗，含玻璃 drag handle。
- [x] `GlassEnterTransition`（fade + spring scale）已接入 `MessageBubble` 入场动画。
- [x] **`MessageBubble` 滚动重播修复**：`ChatContent` 持有 `seenMessageIds` 集合（首次组合即把当前所有消息 id 收入），`items` 调用点传入该集合；`MessageBubble` 用
      `enter = if (hasEntered) EnterTransition.None else GlassEnterTransition` 驱动**入场过渡**（而非 visibility），
      只有真正新增的消息才播放弹簧入场，滚动回头不再重播（且不会因标记已见而误触发退出动画）。
- [x] **玻璃组件双重阴影修复**：`GlassCard` 在 glass 模式下将 `Card` 的 `elevation` 归零（`CardDefaults.cardElevation(0.dp)`），
      只保留 `glassBackground()` 的单一深度阴影；`GlassDropdownMenu` 在 glass 模式下将 `DropdownMenu` 的
      `tonalElevation` / `shadowElevation` 归零，避免与玻璃阴影叠加成厚重双影。
- [ ] 可选：将 `GlassMotion` 的 `glassPress` / `glassClickable` 进一步接入发送按钮与卡片点击
      （发送按钮当前用 `scaleOnPress`，已有按压反馈，可后续统一为 `glassPress`）。
- [x] `assembleDebug` 已通过编译验证（`BUILD SUCCESSFUL`，commit `eccb3f2`），0 个 Java 文件，纯 Kotlin + Compose。
- [x] 打磨批次（滚动重播 + 双重阴影）：`assembleDebug` 再次通过（`BUILD SUCCESSFUL`，commit 见下）。
