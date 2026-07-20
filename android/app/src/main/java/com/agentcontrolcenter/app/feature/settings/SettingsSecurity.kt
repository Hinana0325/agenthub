package com.agentcontrolcenter.app.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.app.R

/**
 * 安全相关设置：E2E 加密对话框及密钥管理（显示 / 复制 / 重新生成 / 导入）。
 *
 * 提取自 `SettingsScreen.kt`，可见性由 `private` 改为 `internal`。
 */
@Composable
internal fun E2EPasswordDialog(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    var showImportField by remember { mutableStateOf(false) }
    var importKeyValue by remember { mutableStateOf("") }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showRegenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text(stringResource(R.string.e2e_confirm_regenerate)) },
            text = { Text(stringResource(R.string.e2e_regenerate_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.regenerateKey()
                    showRegenerateConfirm = false
                    Toast.makeText(context, context.getString(R.string.e2e_key_regenerated), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.e2e_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.e2e_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.e2e_toggle),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = uiState.e2eEnabled,
                        onCheckedChange = { viewModel.toggleE2E(it) }
                    )
                }

                if (uiState.e2eEnabled) {
                    HorizontalDivider()

                    // Key display
                    Text(
                        text = stringResource(R.string.e2e_key_label),
                        style = MaterialTheme.typography.titleSmall
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (uiState.e2eKey.isNotEmpty()) {
                                val key = uiState.e2eKey
                                if (key.length > 16) {
                                    key.substring(0, 8) + "…" + key.substring(key.length - 8)
                                } else {
                                    key
                                }
                            } else {
                                stringResource(R.string.e2e_key_hidden)
                            },
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.copyKey(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.e2e_copy_key), style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { showRegenerateConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.e2e_regenerate_key), style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    HorizontalDivider()

                    // Import key
                    if (showImportField) {
                        OutlinedTextField(
                            value = importKeyValue,
                            onValueChange = { importKeyValue = it },
                            label = { Text(stringResource(R.string.e2e_import_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showImportField = false }) {
                                Text(stringResource(R.string.btn_cancel))
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    if (importKeyValue.isNotBlank()) {
                                        viewModel.importKey(importKeyValue)
                                        importKeyValue = ""
                                        showImportField = false
                                        Toast.makeText(context, context.getString(R.string.e2e_key_imported), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.e2e_confirm))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showImportField = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.e2e_import_key))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_dismiss))
            }
        }
    )
}
