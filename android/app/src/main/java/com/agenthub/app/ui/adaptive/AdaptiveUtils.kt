package com.agenthub.app.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── 保留旧枚举以保持向后兼容 ──────────────────────────────────────────

/** @deprecated Use [WindowWidthClass] instead. Retained for source compatibility. */
enum class WindowSize { Compact, Medium, Expanded }

/** @deprecated Use [WindowWidthClass] + height-based logic instead. */
enum class LegacyNavigationMode { BottomBar, Rail }

// ── 新增自适应基础设施 ─────────────────────────────────────────────────

/**
 * 窗口宽度级别，基于 Material Design 断点。
 * - **Compact**：< 600dp（手机竖屏）
 * - **Medium**：600-839dp（折叠屏、小平板）
 * - **Expanded**：≥ 840dp（大平板、桌面）
 */
enum class WindowWidthClass { Compact, Medium, Expanded }

/**
 * 窗口高度级别。
 * - **Compact**：< 480dp（极矮窗口 / 横屏小设备）
 * - **Medium**：480-899dp（常规竖屏设备）
 * - **Expanded**：≥ 900dp（高屏设备 / 平板竖屏）
 */
enum class WindowHeightClass { Compact, Medium, Expanded }

/**
 * 导航模式，由宽度级别与设备姿态共同决定。
 * - **BottomBar**：底部导航栏（手机竖屏 / 小屏）
 * - **Rail**：侧边导航栏（平板 / 折叠屏横屏）
 * - **Drawer**：抽屉导航（预留，暂未自动分配）
 */
enum class NavigationMode { BottomBar, Rail, Drawer }

/**
 * 内容布局模式，决定主内容区域的列数。
 * - **Single**：单列（手机竖屏 / 小屏竖屏）
 * - **Dual**：双列（平板 / 折叠屏横屏）
 * - **Triple**：三列（预留，超大屏场景）
 */
enum class ContentLayout { Single, Dual, Triple }

/**
 * 面板配置，描述各区域的可见性与尺寸。
 *
 * @property showSidebar 是否显示侧边栏（会话列表等）
 * @property sidebarWidth 侧边栏宽度
 * @property showDetailPanel 是否显示详情面板（预留）
 * @property detailPanelWidth 详情面板宽度
 * @property contentMaxWidth 主内容区域最大宽度，[Dp.Unspecified] 表示不限
 * @property inputMaxWidth 输入区域最大宽度
 */
@Immutable
data class PanelConfig(
    val showSidebar: Boolean = false,
    val sidebarWidth: Dp = 0.dp,
    val showDetailPanel: Boolean = false,
    val detailPanelWidth: Dp = 0.dp,
    val contentMaxWidth: Dp = Dp.Unspecified,
    val inputMaxWidth: Dp = Dp.Unspecified
)

/**
 * 完整的自适应配置，一次计算即可获取所有布局决策所需信息。
 *
 * @property widthClass 窗口宽度级别
 * @property heightClass 窗口高度级别
 * @property navMode 推荐的导航模式
 * @property contentLayout 推荐的内容布局模式
 * @property panelConfig 各面板的尺寸与可见性配置
 * @property isTablet 宽度 ≥ 600dp 即视为平板（含折叠屏展开态）
 * @property isLandscape 宽度 > 高度时为横屏
 * @property isFoldable 折叠屏判断：中等宽度 + 较矮高度，或宽度在 600-720 之间
 */
@Immutable
data class AdaptiveConfig(
    val widthClass: WindowWidthClass,
    val heightClass: WindowHeightClass,
    val navMode: NavigationMode,
    val contentLayout: ContentLayout,
    val panelConfig: PanelConfig,
    val isTablet: Boolean,
    val isLandscape: Boolean,
    val isFoldable: Boolean
) {
    // ── 向后兼容字段 ──────────────────────────────────────────────
    /** @deprecated Use [widthClass]. */
    @Suppress("DEPRECATION")
    val windowSize: WindowSize
        get() = when (widthClass) {
            WindowWidthClass.Compact -> WindowSize.Compact
            WindowWidthClass.Medium -> WindowSize.Medium
            WindowWidthClass.Expanded -> WindowSize.Expanded
        }

    companion object {
        /**
         * 默认手机配置，适用于 Compose Preview 或测试。
         */
        val Phone = AdaptiveConfig(
            widthClass = WindowWidthClass.Compact,
            heightClass = WindowHeightClass.Medium,
            navMode = NavigationMode.BottomBar,
            contentLayout = ContentLayout.Single,
            panelConfig = PanelConfig(contentMaxWidth = 600.dp, inputMaxWidth = 640.dp),
            isTablet = false,
            isLandscape = false,
            isFoldable = false
        )
    }
}

/**
 * 计算当前屏幕的自适应配置。
 *
 * 该函数使用纯 dp 判断（无额外 Gradle 依赖），在 Composable 上下文中
 * 读取 [LocalConfiguration] 并返回完整的 [AdaptiveConfig]。
 *
 * 断点规则：
 * | 级别 | 宽度 | 高度 |
 * |------|------|------|
 * | Compact | < 600dp | < 480dp |
 * | Medium | 600-839dp | 480-899dp |
 * | Expanded | ≥ 840dp | ≥ 900dp |
 */
@Composable
fun currentAdaptiveConfig(): AdaptiveConfig {
    val config = LocalConfiguration.current
    val widthDp = config.screenWidthDp
    val heightDp = config.screenHeightDp

    val widthClass = when {
        widthDp >= 840 -> WindowWidthClass.Expanded
        widthDp >= 600 -> WindowWidthClass.Medium
        else -> WindowWidthClass.Compact
    }

    val heightClass = when {
        heightDp >= 900 -> WindowHeightClass.Expanded
        heightDp >= 480 -> WindowHeightClass.Medium
        else -> WindowHeightClass.Compact
    }

    val isLandscape = widthDp > heightDp
    val isTablet = widthDp >= 600
    // 折叠屏判断：中等宽度 + 较矮高度，或宽度在 600-720 之间
    val isFoldable = widthClass == WindowWidthClass.Medium &&
            (heightDp < 600 || widthDp in 600..720)

    val navMode = when (widthClass) {
        WindowWidthClass.Expanded -> NavigationMode.Rail
        WindowWidthClass.Medium -> if (isLandscape) NavigationMode.Rail else NavigationMode.BottomBar
        WindowWidthClass.Compact -> NavigationMode.BottomBar
    }

    val contentLayout = when (widthClass) {
        WindowWidthClass.Expanded -> ContentLayout.Dual
        WindowWidthClass.Medium -> if (isLandscape) ContentLayout.Dual else ContentLayout.Single
        WindowWidthClass.Compact -> ContentLayout.Single
    }

    val panelConfig = when (widthClass) {
        WindowWidthClass.Expanded -> PanelConfig(
            showSidebar = true,
            sidebarWidth = 280.dp,
            contentMaxWidth = 720.dp,
            inputMaxWidth = 680.dp
        )
        WindowWidthClass.Medium -> if (isLandscape) PanelConfig(
            showSidebar = true,
            sidebarWidth = 240.dp,
            contentMaxWidth = 600.dp,
            inputMaxWidth = 600.dp
        ) else PanelConfig(
            contentMaxWidth = 600.dp,
            inputMaxWidth = 600.dp
        )
        WindowWidthClass.Compact -> PanelConfig(
            contentMaxWidth = 600.dp,
            inputMaxWidth = 640.dp
        )
    }

    return AdaptiveConfig(
        widthClass = widthClass,
        heightClass = heightClass,
        navMode = navMode,
        contentLayout = contentLayout,
        panelConfig = panelConfig,
        isTablet = isTablet,
        isLandscape = isLandscape,
        isFoldable = isFoldable
    )
}

// ── 便捷扩展属性 ────────────────────────────────────────────────────

/** 是否应显示侧边导航栏（Rail 模式）。 */
val AdaptiveConfig.shouldShowRail: Boolean
    get() = navMode == NavigationMode.Rail

/** 是否应显示侧边栏面板。 */
val AdaptiveConfig.shouldShowSidebar: Boolean
    get() = panelConfig.showSidebar

/** 主内容区域最大宽度。 */
val AdaptiveConfig.contentMaxWidth: Dp
    get() = panelConfig.contentMaxWidth

/** 输入区域最大宽度。 */
val AdaptiveConfig.inputMaxWidth: Dp
    get() = panelConfig.inputMaxWidth
