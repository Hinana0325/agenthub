package com.agentcontrolcenter.app.feature.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.navigation.Screen
import com.agentcontrolcenter.app.ui.theme.AppCard
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar

/**
 * P0 信息架构重组（阶段 1）：More 主 Tab 内容页。
 *
 * 收敛原埋在 Settings 子页或隐藏入口的 9 个次级 Screen：
 * Settings / Activity / Marketplace / Workflow / Insights / Plugins /
 * Mcp / Compare / DeviceSync。
 *
 * - 顶部 [AppTopAppBar] 标题「更多」
 * - [LazyColumn] 列出 9 个入口，每行复用 [AppCard] + Row（图标 + 标题/副标题 + trailing 箭头）
 * - 点击调用 [onNavigate] 回调，由 [com.agentcontrolcenter.app.navigation.AppNavigation]
 *   执行 `navController.navigate(route)`，不破坏现有 NavController 路由与参数传递
 *
 * 图标、标题、route 全部直接复用 [Screen] 上对应的字段，保证与各 Screen 自身定义一致，
 * 后续若调整图标只需改 [Screen] 一处。
 *
 * 本地化阶段 3 再做完整 i18n；本阶段标题复用既有 strings.xml key，副标题使用新增的
 * `more_entry_*_subtitle` key（中英文均已提供）。
 *
 * @param onNavigate 接收目标 Screen 的 route 字符串，由外层执行导航
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onNavigate: (String) -> Unit
) {
    val entries = moreEntries()

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = { Text(stringResource(R.string.more_screen_title)) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(entries, key = { it.route }) { entry ->
                AppCard(
                    onClick = { onNavigate(entry.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(entry.titleResId),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(entry.subtitleResId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * More 次级入口的数据描述。
 *
 * @property route 目标 [Screen] 的 route
 * @property icon 入口图标（复用对应 [Screen.icon]）
 * @property titleResId 入口标题（复用对应 [Screen.stringResId]）
 * @property subtitleResId 入口副标题（新增的 `more_entry_*_subtitle` 字符串）
 */
private data class MoreEntry(
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val titleResId: Int,
    val subtitleResId: Int
)

/**
 * 构造 More 页 9 个次级入口列表。
 *
 * 顺序：Settings 置顶（按方案要求），其后为 Activity / Marketplace / Workflow /
 * Insights / Plugins / MCP / Compare / DeviceSync。
 *
 * 直接引用各 [Screen] 的 `route` / `icon` / `stringResId`，避免重复定义导致的不一致。
 */
private fun moreEntries(): List<MoreEntry> = listOf(
    MoreEntry(
        route = Screen.Settings.route,
        icon = Screen.Settings.icon,
        titleResId = Screen.Settings.stringResId,
        subtitleResId = R.string.more_entry_settings_subtitle
    ),
    MoreEntry(
        route = Screen.Activity.route,
        icon = Screen.Activity.icon,
        titleResId = Screen.Activity.stringResId,
        subtitleResId = R.string.more_entry_activity_subtitle
    ),
    MoreEntry(
        route = Screen.Marketplace.route,
        icon = Screen.Marketplace.icon,
        titleResId = Screen.Marketplace.stringResId,
        subtitleResId = R.string.more_entry_marketplace_subtitle
    ),
    MoreEntry(
        route = Screen.Workflow.route,
        icon = Screen.Workflow.icon,
        titleResId = Screen.Workflow.stringResId,
        subtitleResId = R.string.more_entry_workflow_subtitle
    ),
    MoreEntry(
        route = Screen.Insights.route,
        icon = Screen.Insights.icon,
        titleResId = Screen.Insights.stringResId,
        subtitleResId = R.string.more_entry_insights_subtitle
    ),
    MoreEntry(
        route = Screen.Plugins.route,
        icon = Screen.Plugins.icon,
        titleResId = Screen.Plugins.stringResId,
        subtitleResId = R.string.more_entry_plugins_subtitle
    ),
    MoreEntry(
        route = Screen.Mcp.route,
        icon = Screen.Mcp.icon,
        titleResId = Screen.Mcp.stringResId,
        subtitleResId = R.string.more_entry_mcp_subtitle
    ),
    MoreEntry(
        route = Screen.Compare.route,
        icon = Screen.Compare.icon,
        titleResId = Screen.Compare.stringResId,
        subtitleResId = R.string.more_entry_compare_subtitle
    ),
    MoreEntry(
        route = Screen.DeviceSync.route,
        icon = Screen.DeviceSync.icon,
        titleResId = Screen.DeviceSync.stringResId,
        subtitleResId = R.string.more_entry_devicesync_subtitle
    )
)
