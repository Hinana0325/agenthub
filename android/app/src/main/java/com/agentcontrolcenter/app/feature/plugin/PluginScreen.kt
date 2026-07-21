package com.agentcontrolcenter.app.feature.plugin

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.plugin.api.Plugin
import com.agentcontrolcenter.app.plugin.runtime.PluginExecutor
import com.agentcontrolcenter.app.plugin.runtime.PluginManager
import com.agentcontrolcenter.app.ui.theme.AppCard
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    // Obtain the Hilt-provided singleton PluginManager via an EntryPoint instead of the
    // removed AppModule singleton. PluginManager is @Singleton-scoped and provided by Hilt
    // (PluginDao is in DatabaseModule, PluginManager uses @Inject constructor).
    val entryPoint = remember {
        EntryPointAccessors.fromApplication<PluginManagerEntryPoint>(
            context.applicationContext
        )
    }
    val pluginManager = remember { entryPoint.pluginManager() }
    val executor = remember { entryPoint.pluginExecutor() }
    val scope = rememberCoroutineScope()
    val plugins: List<Plugin> by pluginManager.plugins.collectAsStateWithLifecycle()
    var selectedPlugin by remember { mutableStateOf<Plugin?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var runInput by remember { mutableStateOf("") }
    var runResult by remember { mutableStateOf<PluginExecutor.PluginResult?>(null) }

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = { Text(stringResource(R.string.plugin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                PluginStatsCard(
                    total = plugins.size,
                    enabled = plugins.count { it.isEnabled }
                )
            }

            item {
                Text(
                    text = stringResource(R.string.plugin_installed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(plugins, key = { it.id }) { plugin ->
                PluginCard(
                    plugin = plugin,
                    onToggle = { pluginManager.togglePlugin(plugin.id) },
                    onClick = {
                        selectedPlugin = plugin
                        runInput = ""
                        showDetailDialog = true
                    }
                )
            }
        }
    }

    // selectedPlugin 是 by remember { mutableStateOf } 委托属性，smart-cast 无法传播到
    // 闭包内，且重复读取可能拿到不同的值。这里用局部 val 捕获当前快照，消除所有 !!。
    val selected = selectedPlugin
    if (showDetailDialog && selected != null) {
        PluginDetailDialog(
            plugin = selected,
            runInput = runInput,
            onRunInputChange = { runInput = it },
            onRun = {
                scope.launch {
                    runResult = executor.execute(selected, runInput)
                }
            },
            onToggle = { pluginManager.togglePlugin(selected.id) },
            onDismiss = { showDetailDialog = false }
        )
    }

    // Execution result dialog
    // runResult 同样是委托属性，用局部 val 捕获快照后在非空分支中使用，消除 !!。
    val result = runResult
    if (result != null) {
        AlertDialog(
            onDismissRequest = { runResult = null },
            title = { Text(if (result.sendToAgent) stringResource(R.string.plugin_send_to_agent) else stringResource(R.string.plugin_result)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (result.sendToAgent) {
                        Text(
                            "This plugin produced a prompt. Copy it and paste it into your agent chat:",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = result.content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (result.sendToAgent) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Agent Control Center Plugin Prompt", result.content))
                        runResult = null
                    }) {
                        Text(stringResource(R.string.action_copy))
                    }
                } else {
                    TextButton(onClick = { runResult = null }) {
                        Text(stringResource(R.string.btn_dismiss))
                    }
                }
            },
            dismissButton = {
                if (result.sendToAgent) {
                    TextButton(onClick = { runResult = null }) {
                        Text(stringResource(R.string.btn_dismiss))
                    }
                }
            }
        )
    }
}

@Composable
private fun PluginStatsCard(total: Int, enabled: Int) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$total",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.plugin_total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$enabled",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.plugin_enabled),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: Plugin,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (plugin.isEnabled)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = plugin.icon,
                        fontSize = 24.sp
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2
                )
                if (plugin.permissions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        plugin.permissions.forEach { perm ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = perm,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Switch(
                checked = plugin.isEnabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

@Composable
private fun PluginDetailDialog(
    plugin: Plugin,
    runInput: String,
    onRunInputChange: (String) -> Unit,
    onRun: () -> Unit,
    onToggle: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = plugin.icon, fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Text(plugin.name)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodyMedium
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.plugin_version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = plugin.version,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (plugin.permissions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.plugin_permissions),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    plugin.permissions.forEach { permission ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (permission) {
                                     "network" -> Icons.Filled.Wifi
                                     "storage" -> Icons.Filled.Storage
                                     else -> Icons.Filled.Security
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when (permission) {
                                    "network" -> stringResource(R.string.plugin_perm_network)
                                    "storage" -> stringResource(R.string.plugin_perm_storage)
                                    else -> permission
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Run section (only when the plugin has an executable action)
                if (plugin.action != null) {
                    Text(
                        text = stringResource(R.string.plugin_run),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = runInput,
                        onValueChange = onRunInputChange,
                        label = { Text(stringResource(R.string.plugin_input_hint)) },
                        placeholder = { Text(stringResource(R.string.plugin_prompt_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onRun,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.plugin_run_action))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.plugin_no_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.plugin_status),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = plugin.isEnabled,
                        onCheckedChange = { onToggle() }
                    )
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

/**
 * Hilt EntryPoint that exposes the singleton [PluginManager] to non-injected callers
 * (i.e. this Composable). Replaces the former `AppModule.getPluginManager(context)` singleton.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface PluginManagerEntryPoint {
    fun pluginManager(): PluginManager
    fun pluginExecutor(): PluginExecutor
}
