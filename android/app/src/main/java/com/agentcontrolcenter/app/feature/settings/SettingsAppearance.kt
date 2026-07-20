package com.agentcontrolcenter.app.feature.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.app.R

/**
 * 外观相关设置：主题选择、动态取色开关（开关本身仍在 [SettingsScreen] 中通过
 * [SettingsToggleItem] 渲染）、字体大小选择，以及对应的本地化标签工具函数。
 *
 * 提取自 `SettingsScreen.kt`，可见性由 `private` 改为 `internal`。
 */

/**
 * 主题选择对话框（system / light / dark）。
 */
@Composable
internal fun ThemePickerDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        "system" to stringResource(R.string.theme_system),
        "light" to stringResource(R.string.theme_light),
        "dark" to stringResource(R.string.theme_dark)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme)) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == value, onClick = { onSelect(value) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } }
    )
}

/**
 * 字体大小选择对话框（small / medium / large）。
 */
@Composable
internal fun FontSizePickerDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        "small" to stringResource(R.string.font_small),
        "medium" to stringResource(R.string.font_medium),
        "large" to stringResource(R.string.font_large)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.font_size)) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == value, onClick = { onSelect(value) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } }
    )
}

/**
 * 将主题模式原始值映射为本地化文案。
 */
internal fun themeLabel(mode: String, context: Context): String = when (mode) {
    "system" -> context.getString(R.string.theme_system)
    "light" -> context.getString(R.string.theme_light)
    "dark" -> context.getString(R.string.theme_dark)
    else -> mode
}

/**
 * 将字体大小原始值映射为本地化文案。
 */
internal fun fontSizeLabel(size: String, context: Context): String = when (size) {
    "small" -> context.getString(R.string.font_small)
    "medium" -> context.getString(R.string.font_medium)
    "large" -> context.getString(R.string.font_large)
    else -> size
}
