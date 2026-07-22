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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
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
                        item {
                            CategoryItem(
                                title = "实验性功能",
                                icon = Icons.Default.Bolt,
                                isSelected = selectedCategory == "experimental",
                                onClick = { selectedCategory = "experimental" }
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
                            "experimental" -> {
                                // 「实验性功能」分类 — 复用 FeatureFlagSettingsViewModel
                                // 与 iOS SettingsView.experimentalFeaturesSection 对齐
                                experimentalFeaturesSection()
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

                        // 实验性功能 — 单栏布局下直接展开 FeatureFlag 列表
                        // （experimentalFeaturesSection 内部已含 Header，无需再添加）
                        item { Spacer(Modifier.height(8.dp)) }
                        experimentalFeaturesSection()

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
