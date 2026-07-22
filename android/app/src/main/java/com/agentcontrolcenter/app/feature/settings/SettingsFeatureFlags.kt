package com.agentcontrolcenter.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcontrolcenter.app.core.featureflag.FeatureFlagManager.FeatureFlag

// MARK: - SettingsFeatureFlags
// 设置页「实验性功能」分类内容。补齐 Android 端缺失的 FeatureFlag 编辑入口，
// 与 iOS SettingsView.experimentalFeaturesSection 行为对齐。

/**
 * LazyListScope 扩展：向设置页追加「实验性功能」分类。
 *
 * 用 LazyListScope 而非 Composable 函数，便于直接嵌入 SettingsScreen 的 LazyColumn，
 * 避免在嵌套 Column 中嵌入 LazyColumn（detekt / Compose 性能反模式）。
 */
internal fun LazyListScope.experimentalFeaturesSection(
    viewModel: FeatureFlagSettingsViewModel = hiltViewModel()
) {
    val flags by viewModel.flags.collectAsStateWithLifecycle()

    item { SettingsHeader("实验性功能") }

    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "开启或关闭未稳定的功能。修改即时生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { viewModel.resetAll() }) {
                Icon(Icons.Default.Restore, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("全部恢复默认")
            }
        }
    }

    if (flags.isEmpty()) {
        item {
            Text(
                text = "加载中...",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    flags.forEach { state ->
        item {
            FeatureFlagRow(
                state = state,
                onToggle = { viewModel.toggle(state.flag, it) },
                onReset = { viewModel.resetToDefault(state.flag) }
            )
        }
    }
}

@Composable
private fun FeatureFlagRow(
    state: FeatureFlagSettingsViewModel.FlagUiState,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    val icon = iconForFlag(state.flag)
    val subtitle = subtitleForFlag(state.flag, state.isOverridden)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 16.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = displayNameForFlag(state.flag),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.isOverridden) {
                    Spacer(modifier = Modifier.width(4.dp))
                    AssistChip(
                        onClick = onReset,
                        label = { Text("已覆盖 · 点击恢复默认") },
                        leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
            Switch(
                checked = state.currentValue,
                onCheckedChange = onToggle
            )
        }
    }
}


// ── Flag 元数据（与 iOS FeatureFlagManager.FeatureFlag.allCases 行为对齐）──

private fun displayNameForFlag(flag: FeatureFlag): String = when (flag) {
    FeatureFlag.WORKFLOW_ENGINE -> "工作流引擎"
    FeatureFlag.MARKETPLACE -> "Agent 市场"
    FeatureFlag.DEVICE_SYNC -> "设备同步"
    FeatureFlag.INSIGHTS -> "数据洞察"
    FeatureFlag.COMPARE_MODE -> "对比模式"
    FeatureFlag.MCP_SERVERS -> "MCP 服务器"
    FeatureFlag.CUSTOM_THEME -> "自定义主题"
    FeatureFlag.VOICE_INPUT -> "语音输入"
    FeatureFlag.E2E_ENCRYPTION -> "端到端加密"
}

private fun subtitleForFlag(flag: FeatureFlag, isOverridden: Boolean): String {
    val desc = when (flag) {
        FeatureFlag.WORKFLOW_ENGINE -> "Agent 任务编排"
        FeatureFlag.MARKETPLACE -> "浏览和安装第三方 Agent"
        FeatureFlag.DEVICE_SYNC -> "P2P 跨设备数据同步（开发中）"
        FeatureFlag.INSIGHTS -> "使用统计与分析"
        FeatureFlag.COMPARE_MODE -> "多 Agent 输出对比"
        FeatureFlag.MCP_SERVERS -> "Model Context Protocol 集成"
        FeatureFlag.CUSTOM_THEME -> "用户自定义配色"
        FeatureFlag.VOICE_INPUT -> "Speech-to-Text"
        FeatureFlag.E2E_ENCRYPTION -> "消息加密传输"
    }
    val suffix = if (isOverridden) " · 已覆盖" else ""
    return desc + suffix
}

private fun iconForFlag(flag: FeatureFlag) = when (flag) {
    FeatureFlag.WORKFLOW_ENGINE -> Icons.Default.Bolt
    FeatureFlag.MARKETPLACE -> Icons.Default.Storefront
    FeatureFlag.DEVICE_SYNC -> Icons.Default.Sync
    FeatureFlag.INSIGHTS -> Icons.Default.Insights
    FeatureFlag.COMPARE_MODE -> Icons.Default.Compare
    FeatureFlag.MCP_SERVERS -> Icons.Default.Hub
    FeatureFlag.CUSTOM_THEME -> Icons.Default.Palette
    FeatureFlag.VOICE_INPUT -> Icons.Default.Mic
    FeatureFlag.E2E_ENCRYPTION -> Icons.Default.Lock
}
