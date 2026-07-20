package com.agentcontrolcenter.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.ui.adaptive.WindowSize
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.data.update.UpdateManager
import com.agentcontrolcenter.app.ui.theme.GlassTopAppBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToAgents: () -> Unit = {},
    onNavigateToMarketplace: () -> Unit = {},
    onNavigateToDeviceSync: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToInsights: () -> Unit = {},
    onNavigateToTasks: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {}
) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val adaptive = currentAdaptiveConfig()
    val context = LocalContext.current
    val useDualPane = adaptive.windowSize == WindowSize.Expanded

    // Agent-config count sourced from the Hilt-injected repository via the ViewModel
    // (previously fetched directly via the now-removed AppModule singleton).
    val agentConfigs by settingsViewModel.agentConfigs.collectAsStateWithLifecycle()

    // Refresh performance metrics only while the Settings screen is visible.
    // This replaces the permanent `while (isActive)` loop that previously ran in
    // SettingsViewModel.init for the entire app lifetime.
    LaunchedEffect(Unit) {
        while (true) {
            settingsViewModel.refreshPerformanceMetrics()
            kotlinx.coroutines.delay(3000)
        }
    }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showE2EDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("appearance") }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { settingsViewModel.importChatHistory(context, it) }
    }

    // Backup message snackbar
    LaunchedEffect(uiState.backupMessage) {
        uiState.backupMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            settingsViewModel.clearBackupMessage()
        }
    }

    val performanceMetrics by settingsViewModel.getPerformanceMetrics().collectAsStateWithLifecycle()

    // --- In-app update check ---
    val currentVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) { "0.0.0" }
    }
    val updateManager = remember { UpdateManager() }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var triggerCheck by remember { mutableIntStateOf(0) }

    LaunchedEffect(triggerCheck) {
        if (triggerCheck == 0) return@LaunchedEffect
        isChecking = true
        updateCheckResult = null
        showUpdateDialog = false
        val result = updateManager.checkForUpdates(currentVersion)
        updateCheckResult = when {
            result.isFailure ->
                UpdateCheckResult.Error(result.exceptionOrNull()?.message ?: "Network error")
            result.getOrNull() != null ->
                UpdateCheckResult.Available(result.getOrNull()!!)
            else -> UpdateCheckResult.UpToDate
        }
        isChecking = false
        showUpdateDialog = true
    }

    if (showUpdateDialog && updateCheckResult != null) {
        UpdateCheckDialog(
            result = updateCheckResult!!,
            currentVersion = currentVersion,
            onDownload = { info -> updateManager.downloadUpdate(context, info) },
            onDismiss = { showUpdateDialog = false }
        )
    }

    if (showThemeDialog) {
        ThemePickerDialog(
            current = uiState.themeMode,
            onSelect = { settingsViewModel.setThemeMode(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false }
        )
    }
    if (showFontDialog) {
        FontSizePickerDialog(
            current = uiState.fontSize,
            onSelect = { settingsViewModel.setFontSize(it); showFontDialog = false },
            onDismiss = { showFontDialog = false }
        )
    }
    if (showE2EDialog) {
        E2EPasswordDialog(
            uiState = uiState,
            viewModel = settingsViewModel,
            onDismiss = { showE2EDialog = false }
        )
    }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        if (useDualPane) {
            // Dual-pane: left = category list, right = settings detail
            Row(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                // Left pane - category navigation
                Surface(
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                    tonalElevation = 1.dp
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        item {
                            CategoryItem(
                                title = stringResource(R.string.appearance),
                                icon = Icons.Default.Palette,
                                isSelected = selectedCategory == "appearance",
                                onClick = { selectedCategory = "appearance" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.connection),
                                icon = Icons.Default.Hub,
                                isSelected = selectedCategory == "connection",
                                onClick = { selectedCategory = "connection" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.nav_marketplace),
                                icon = Icons.Default.Storefront,
                                isSelected = selectedCategory == "marketplace",
                                onClick = { selectedCategory = "marketplace" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.e2e_security),
                                icon = Icons.Default.Lock,
                                isSelected = selectedCategory == "security",
                                onClick = { selectedCategory = "security" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.data_backup),
                                icon = Icons.Default.Backup,
                                isSelected = selectedCategory == "data",
                                onClick = { selectedCategory = "data" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.insights_title),
                                icon = Icons.Default.BarChart,
                                isSelected = selectedCategory == "insights",
                                onClick = { selectedCategory = "insights" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.performance),
                                icon = Icons.Default.Speed,
                                isSelected = selectedCategory == "performance",
                                onClick = { selectedCategory = "performance" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.device_sync_title),
                                icon = Icons.Default.Sync,
                                isSelected = selectedCategory == "sync",
                                onClick = { selectedCategory = "sync" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.plugin_title),
                                icon = Icons.Default.Extension,
                                isSelected = selectedCategory == "plugins",
                                onClick = { selectedCategory = "plugins" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.smart_notif_title),
                                icon = Icons.Default.NotificationsActive,
                                isSelected = selectedCategory == "notifications",
                                onClick = { selectedCategory = "notifications" }
                            )
                        }
                        item {
                            CategoryItem(
                                title = stringResource(R.string.about),
                                icon = Icons.Default.Info,
                                isSelected = selectedCategory == "about",
                                onClick = { selectedCategory = "about" }
                            )
                        }
                    }
                }

                // Right pane - settings detail
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        when (selectedCategory) {
                            "appearance" -> {
                                item { SettingsHeader(stringResource(R.string.appearance)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.theme),
                                        subtitle = themeLabel(uiState.themeMode, context),
                                        icon = Icons.Default.Palette,
                                        onClick = { showThemeDialog = true }
                                    )
                                }
                                item {
                                    SettingsToggleItem(
                                        title = stringResource(R.string.dynamic_color),
                                        subtitle = stringResource(R.string.dynamic_color_desc),
                                        icon = Icons.Default.AutoAwesome,
                                        checked = uiState.dynamicColor,
                                        onCheckedChange = { settingsViewModel.setDynamicColor(it) }
                                    )
                                }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.font_size),
                                        subtitle = fontSizeLabel(uiState.fontSize, context),
                                        icon = Icons.Default.TextFields,
                                        onClick = { showFontDialog = true }
                                    )
                                }
                            }
                            "connection" -> {
                                item { SettingsHeader(stringResource(R.string.connection)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.manage_agents),
                                        subtitle = stringResource(R.string.manage_agents_subtitle, agentConfigs.size),
                                        icon = Icons.Default.Hub,
                                        onClick = onNavigateToAgents
                                    )
                                }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.nav_tasks),
                                        subtitle = stringResource(R.string.no_tasks_subtitle),
                                        icon = Icons.Default.TaskAlt,
                                        onClick = onNavigateToTasks
                                    )
                                }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.nav_mcp),
                                        subtitle = stringResource(R.string.mcp_no_servers_subtitle),
                                        icon = Icons.Default.Dns,
                                        onClick = onNavigateToMcp
                                    )
                                }
                            }
                            "marketplace" -> {
                                item { SettingsHeader(stringResource(R.string.nav_marketplace)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.marketplace_title),
                                        subtitle = stringResource(R.string.marketplace_subtitle),
                                        icon = Icons.Default.Storefront,
                                        onClick = onNavigateToMarketplace
                                    )
                                }
                            }
                            "security" -> {
                                item { SettingsHeader(stringResource(R.string.e2e_security)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.e2e_title),
                                        subtitle = if (uiState.e2eEnabled) context.getString(R.string.e2e_enabled)
                                                   else context.getString(R.string.e2e_disabled),
                                        icon = Icons.Default.Lock,
                                        onClick = { showE2EDialog = true }
                                    )
                                }
                            }
                            "data" -> {
                                item { SettingsHeader(stringResource(R.string.data_backup)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.export_chat_history),
                                        subtitle = stringResource(R.string.export_chat_history_subtitle),
                                        icon = Icons.Default.FileDownload,
                                        onClick = { settingsViewModel.exportChatHistory(context) }
                                    )
                                }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.import_chat_history),
                                        subtitle = stringResource(R.string.import_chat_history_subtitle),
                                        icon = Icons.Default.FileUpload,
                                        onClick = { importLauncher.launch(arrayOf("application/json")) }
                                    )
                                }
                            }
                            "insights" -> {
                                item { SettingsHeader(stringResource(R.string.insights_title)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.insights_title),
                                        subtitle = stringResource(R.string.insights_subtitle),
                                        icon = Icons.Default.BarChart,
                                        onClick = onNavigateToInsights
                                    )
                                }
                            }
                            "performance" -> {
                                item { SettingsHeader(stringResource(R.string.performance)) }
                                item { PerformanceMetricItem(
                                    title = stringResource(R.string.perf_avg_latency),
                                    value = "${performanceMetrics.avgMessageLatency} ms",
                                    icon = Icons.Default.Timer
                                ) }
                                item { PerformanceMetricItem(
                                    title = stringResource(R.string.perf_connection_quality),
                                    value = performanceMetrics.connectionQuality,
                                    icon = Icons.Default.Wifi
                                ) }
                                item { PerformanceMetricItem(
                                    title = stringResource(R.string.perf_memory_usage),
                                    value = "${performanceMetrics.memoryUsageMB} MB",
                                    icon = Icons.Default.Memory
                                ) }
                                item { PerformanceMetricItem(
                                    title = stringResource(R.string.perf_total_messages),
                                    value = "${performanceMetrics.totalMessages}",
                                    icon = Icons.Default.Message
                                ) }
                                item { PerformanceMetricItem(
                                    title = stringResource(R.string.perf_uptime),
                                    value = "${performanceMetrics.uptimeMinutes} min",
                                    icon = Icons.Default.AccessTime
                                ) }
                            }
                            "sync" -> {
                                item { SettingsHeader(stringResource(R.string.device_sync_title)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.device_sync_title),
                                        subtitle = stringResource(R.string.device_sync_enabled_desc),
                                        icon = Icons.Default.Sync,
                                        onClick = onNavigateToDeviceSync
                                    )
                                }
                            }
                            "plugins" -> {
                                item { SettingsHeader(stringResource(R.string.plugin_title)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.plugin_title),
                                        subtitle = stringResource(R.string.plugin_installed),
                                        icon = Icons.Default.Extension,
                                        onClick = onNavigateToPlugins
                                    )
                                }
                            }
                            "notifications" -> {
                                item { SettingsHeader(stringResource(R.string.smart_notif_title)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.smart_notif_high),
                                        subtitle = stringResource(R.string.smart_notif_high_desc),
                                        icon = Icons.Default.PriorityHigh,
                                        onClick = { }
                                    )
                                }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.smart_notif_medium),
                                        subtitle = stringResource(R.string.smart_notif_medium_desc),
                                        icon = Icons.Default.Notifications,
                                        onClick = { }
                                    )
                                }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.smart_notif_low),
                                        subtitle = stringResource(R.string.smart_notif_low_desc),
                                        icon = Icons.Default.NotificationsNone,
                                        onClick = { }
                                    )
                                }
                            }
                            "about" -> {
                                item { SettingsHeader(stringResource(R.string.about)) }
                                item {
                                    SettingsItem(
                                        title = stringResource(R.string.check_update),
                                        subtitle = if (isChecking) stringResource(R.string.checking_update) else "v$currentVersion",
                                        icon = Icons.Default.SystemUpdate,
                                        onClick = { triggerCheck++ }
                                    )
                                }
                                item {
                                    VersionSettingsItem()
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Single-pane layout
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        item { SettingsHeader(stringResource(R.string.appearance)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.theme),
                                subtitle = themeLabel(uiState.themeMode, context),
                                icon = Icons.Default.Palette,
                                onClick = { showThemeDialog = true }
                            )
                        }
                        item {
                            SettingsToggleItem(
                                title = stringResource(R.string.dynamic_color),
                                subtitle = stringResource(R.string.dynamic_color_desc),
                                icon = Icons.Default.AutoAwesome,
                                checked = uiState.dynamicColor,
                                onCheckedChange = { settingsViewModel.setDynamicColor(it) }
                            )
                        }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.font_size),
                                subtitle = fontSizeLabel(uiState.fontSize, context),
                                icon = Icons.Default.TextFields,
                                onClick = { showFontDialog = true }
                            )
                        }

                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.connection)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.manage_agents),
                                subtitle = stringResource(R.string.manage_agents_subtitle, agentConfigs.size),
                                icon = Icons.Default.Hub,
                                onClick = onNavigateToAgents
                            )
                        }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.nav_tasks),
                                subtitle = stringResource(R.string.no_tasks_subtitle),
                                icon = Icons.Default.TaskAlt,
                                onClick = onNavigateToTasks
                            )
                        }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.nav_mcp),
                                subtitle = stringResource(R.string.mcp_no_servers_subtitle),
                                icon = Icons.Default.Dns,
                                onClick = onNavigateToMcp
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.nav_marketplace)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.marketplace_title),
                                subtitle = stringResource(R.string.marketplace_subtitle),
                                icon = Icons.Default.Storefront,
                                onClick = onNavigateToMarketplace
                            )
                        }

                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.e2e_security)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.e2e_title),
                                subtitle = if (uiState.e2eEnabled) context.getString(R.string.e2e_enabled)
                                           else context.getString(R.string.e2e_disabled),
                                icon = Icons.Default.Lock,
                                onClick = { showE2EDialog = true }
                            )
                        }

                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.device_sync_title)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.device_sync_title),
                                subtitle = stringResource(R.string.device_sync_enabled_desc),
                                icon = Icons.Default.Sync,
                                onClick = onNavigateToDeviceSync
                            )
                        }

                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.plugin_title)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.plugin_title),
                                subtitle = stringResource(R.string.plugin_installed),
                                icon = Icons.Default.Extension,
                                onClick = onNavigateToPlugins
                            )
                        }

                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.smart_notif_title)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.smart_notif_title),
                                subtitle = stringResource(R.string.smart_notif_desc),
                                icon = Icons.Default.NotificationsActive,
                                onClick = { }
                            )
                        }

                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.about)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.check_update),
                                subtitle = if (isChecking) stringResource(R.string.checking_update) else "v$currentVersion",
                                icon = Icons.Default.SystemUpdate,
                                onClick = { triggerCheck++ }
                            )
                        }
                        item {
                            VersionSettingsItem()
                        }

                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.insights_title)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.insights_title),
                                subtitle = stringResource(R.string.insights_subtitle),
                                icon = Icons.Default.BarChart,
                                onClick = onNavigateToInsights
                            )
                        }

                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.data_backup)) }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.export_chat_history),
                                subtitle = stringResource(R.string.export_chat_history_subtitle),
                                icon = Icons.Default.FileDownload,
                                onClick = { settingsViewModel.exportChatHistory(context) }
                            )
                        }
                        item {
                            SettingsItem(
                                title = stringResource(R.string.import_chat_history),
                                subtitle = stringResource(R.string.import_chat_history_subtitle),
                                icon = Icons.Default.FileUpload,
                                onClick = { importLauncher.launch(arrayOf("application/json")) }
                            )
                        }

                        item { Spacer(Modifier.height(8.dp)); SettingsHeader(stringResource(R.string.performance)) }
                        item { PerformanceMetricItem(
                            title = stringResource(R.string.perf_avg_latency),
                            value = "${performanceMetrics.avgMessageLatency} ms",
                            icon = Icons.Default.Timer
                        ) }
                        item { PerformanceMetricItem(
                            title = stringResource(R.string.perf_connection_quality),
                            value = performanceMetrics.connectionQuality,
                            icon = Icons.Default.Wifi
                        ) }
                        item { PerformanceMetricItem(
                            title = stringResource(R.string.perf_memory_usage),
                            value = "${performanceMetrics.memoryUsageMB} MB",
                            icon = Icons.Default.Memory
                        ) }
                        item { PerformanceMetricItem(
                            title = stringResource(R.string.perf_total_messages),
                            value = "${performanceMetrics.totalMessages}",
                            icon = Icons.Default.Message
                        ) }
                        item { PerformanceMetricItem(
                            title = stringResource(R.string.perf_uptime),
                            value = "${performanceMetrics.uptimeMinutes} min",
                            icon = Icons.Default.AccessTime
                        ) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else
            MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SettingsHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

/**
 * 带开关的设置项 — 用于布尔值切换（如动态取色、E2E 加密等）。
 */
@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun ThemePickerDialog(
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

@Composable
private fun FontSizePickerDialog(
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

private fun themeLabel(mode: String, context: android.content.Context): String = when (mode) {
    "system" -> context.getString(R.string.theme_system)
    "light" -> context.getString(R.string.theme_light)
    "dark" -> context.getString(R.string.theme_dark)
    else -> mode
}

/**
 * Version item with 5-tap easter egg that shows developer info.
 */
@Composable
private fun VersionSettingsItem() {
    val context = LocalContext.current
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

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
                    Toast.makeText(
                        context,
                        "\uD83D\uDD27 Agent Control Center v2.0.0\nDeveloped with \u2764\uFE0F by Agent Control Center Team\nBuilt with Kotlin + Jetpack Compose",
                        Toast.LENGTH_LONG
                    ).show()
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
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

// ── In-app update check ──

private sealed class UpdateCheckResult {
    data class Available(val info: UpdateManager.UpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

@Composable
private fun UpdateCheckDialog(
    result: UpdateCheckResult,
    currentVersion: String,
    onDownload: (UpdateManager.UpdateInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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

@Composable
private fun E2EPasswordDialog(
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
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
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

@Composable
private fun PerformanceMetricItem(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun fontSizeLabel(size: String, context: android.content.Context): String = when (size) {
    "small" -> context.getString(R.string.font_small)
    "medium" -> context.getString(R.string.font_medium)
    "large" -> context.getString(R.string.font_large)
    else -> size
}
