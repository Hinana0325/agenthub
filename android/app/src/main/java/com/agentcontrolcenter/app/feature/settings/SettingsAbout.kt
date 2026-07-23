package com.agentcontrolcenter.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.data.update.UpdateManager
import com.agentcontrolcenter.app.ui.components.LocalSnackbarHost
import kotlinx.coroutines.launch

/**
 * 关于 / 更新相关设置：版本号展示（含 5 次点击彩蛋）、应用内更新检查结果对话框，
 * 以及对应的 [UpdateCheckResult] 状态密封类。
 *
 * 提取自 `SettingsScreen.kt`，可见性由 `private` 改为 `internal`，
 * 以便 [SettingsScreen] 调用并共享状态类型。
 */

// ── In-app update check ──

internal sealed class UpdateCheckResult {
    data class Available(val info: UpdateManager.UpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * 应用内更新检查结果对话框。
 */
@Composable
internal fun UpdateCheckDialog(
    result: UpdateCheckResult,
    currentVersion: String,
    onDownload: (UpdateManager.UpdateInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (result) {
                    is UpdateCheckResult.UpToDate -> "✔ Up to date"
                    is UpdateCheckResult.Available -> "Update available"
                    is UpdateCheckResult.Error -> "Check failed"
                }
            )
        },
        text = {
            when (result) {
                is UpdateCheckResult.UpToDate ->
                    Text("You are on the latest version (v$currentVersion).")
                is UpdateCheckResult.Available -> {
                    val info = result.info
                    Column {
                        Text(
                            "v$currentVersion → v${info.version}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (info.changelog.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                info.changelog.take(500),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                is UpdateCheckResult.Error ->
                    Text(result.message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (result) {
                is UpdateCheckResult.Available ->
                    TextButton(onClick = { onDownload(result.info); onDismiss() }) {
                        Text("Download")
                    }
                else ->
                    TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = if (result is UpdateCheckResult.Available)
            ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } })
        else null
    )
}

/**
 * Version item with 5-tap easter egg that shows developer info.
 */
@Composable
internal fun VersionSettingsItem() {
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    // P2: 复用根 Scaffold 提供的全局 SnackbarHostState，替代 Toast。
    val snackbarHostState = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val now = System.currentTimeMillis()
                if (now - lastTapTime > 1000) {
                    tapCount = 0
                }
                lastTapTime = now
                tapCount++
                if (tapCount >= 5) {
                    tapCount = 0
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "\uD83D\uDD27 Agent Control Center v2.0.0\nDeveloped with \u2764\uFE0F by Agent Control Center Team\nBuilt with Kotlin + Jetpack Compose",
                            duration = SnackbarDuration.Long
                        )
                    }
                }
            },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.version), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "2.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
