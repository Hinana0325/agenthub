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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.ui.adaptive.WindowWidthClass
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.data.update.UpdateManager
import com.agentcontrolcenter.app.ui.components.LocalSnackbarHost
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
    val useDualPane = adaptive.widthClass == WindowWidthClass.Expanded

    // P2: 通过根 Scaffold 提供的全局 SnackbarHostState 展示消息，替代 Toast。
    val snackbarHostState = LocalSnackbarHost.current

    // Agent-config count sourced from the Hilt-injected repository via the ViewModel
    // (previously fetched directly via the now-removed AppModule singleton).
    val agentConfigs by settingsViewModel.agentConfigs.collectAsStateWithLifecycle()

    // 实验性功能 ViewModel：双栏 / 单栏布局的「实验性功能」分类共用同一实例，
    // 在 @Composable 顶层收集一次 flags 后传给 LazyListScope 扩展。
    val featureFlagViewModel: FeatureFlagSettingsViewModel = hiltViewModel()
    val experimentalFlags by featureFlagViewModel.flags.collectAsStateWithLifecycle()

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
    // 设置页搜索：searchText 非空时按分类标题过滤（双栏过滤左侧列表；单栏隐藏不匹配 Section）。
    // 与 iOS SettingsView.searchable 行为对齐。
    var searchText by remember { mutableStateOf("") }

    val appearanceTitle = stringResource(R.string.appearance)
    val connectionTitle = stringResource(R.string.connection)
    val marketplaceTitle = stringResource(R.string.nav_marketplace)
    val securityTitle = stringResource(R.string.e2e_security)
    val dataTitle = stringResource(R.string.data_backup)
    val insightsTitle = stringResource(R.string.insights_title)
    val performanceTitle = stringResource(R.string.performance)
    val syncTitle = stringResource(R.string.device_sync_title)
    val pluginsTitle = stringResource(R.string.plugin_title)
    val notificationsTitle = stringResource(R.string.smart_notif_title)
    val aboutTitle = stringResource(R.string.about)
    val experimentalTitle = "实验性功能"

    val allCategories = remember(
        appearanceTitle, connectionTitle, marketplaceTitle, securityTitle,
        dataTitle, insightsTitle, performanceTitle, syncTitle, pluginsTitle,
        notificationsTitle, aboutTitle, experimentalTitle
    ) {
        listOf(
            SettingsCategory("appearance", appearanceTitle, Icons.Default.Palette),
            SettingsCategory("connection", connectionTitle, Icons.Default.Hub),
            SettingsCategory("marketplace", marketplaceTitle, Icons.Default.Storefront),
            SettingsCategory("security", securityTitle, Icons.Default.Lock),
            SettingsCategory("data", dataTitle, Icons.Default.Backup),
            SettingsCategory("insights", insightsTitle, Icons.Default.BarChart),
            SettingsCategory("performance", performanceTitle, Icons.Default.Speed),
            SettingsCategory("sync", syncTitle, Icons.Default.Sync),
            SettingsCategory("plugins", pluginsTitle, Icons.Default.Extension),
            SettingsCategory("notifications", notificationsTitle, Icons.Default.NotificationsActive),
            SettingsCategory("about", aboutTitle, Icons.Default.Info),
            SettingsCategory("experimental", experimentalTitle, Icons.Default.Bolt)
        )
    }
    val filteredCategories = if (searchText.isBlank()) allCategories
        else allCategories.filter { it.title.contains(searchText, ignoreCase = true) }

    fun sectionMatches(title: String) =
        searchText.isBlank() || title.contains(searchText, ignoreCase = true)

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { settingsViewModel.importChatHistory(context, it) }
    }

    // Backup message snackbar
    LaunchedEffect(uiState.backupMessage) {
        uiState.backupMessage?.let {
            snackbarHostState.showSnackbar(it)
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
                            SettingsSearchField(
                                query = searchText,
                                onQueryChange = { searchText = it },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                        if (filteredCategories.isEmpty()) {
                            item {
                                Text(
                                    text = "无匹配项",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            filteredCategories.forEach { cat ->
                                item {
                                    CategoryItem(
                                        title = cat.title,
                                        icon = cat.icon,
                                        isSelected = selectedCategory == cat.key,
                                        onClick = { selectedCategory = cat.key }
                                    )
                                }
                            }
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
                                experimentalFeaturesSection(
                                    flags = experimentalFlags,
                                    viewModel = featureFlagViewModel
                                )
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
                        // 搜索框：单栏布局下也提供搜索，按 Section 标题过滤
                        item {
                            SettingsSearchField(
                                query = searchText,
                                onQueryChange = { searchText = it },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }

                        if (sectionMatches(appearanceTitle)) {
                            item { SettingsHeader(appearanceTitle) }
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

                        if (sectionMatches(connectionTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(connectionTitle) }
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

                        if (sectionMatches(marketplaceTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(marketplaceTitle) }
                            item {
                                SettingsItem(
                                    title = stringResource(R.string.marketplace_title),
                                    subtitle = stringResource(R.string.marketplace_subtitle),
                                    icon = Icons.Default.Storefront,
                                    onClick = onNavigateToMarketplace
                                )
                            }
                        }

                        if (sectionMatches(securityTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(securityTitle) }
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

                        if (sectionMatches(syncTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(syncTitle) }
                            item {
                                SettingsItem(
                                    title = stringResource(R.string.device_sync_title),
                                    subtitle = stringResource(R.string.device_sync_enabled_desc),
                                    icon = Icons.Default.Sync,
                                    onClick = onNavigateToDeviceSync
                                )
                            }
                        }

                        if (sectionMatches(pluginsTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(pluginsTitle) }
                            item {
                                SettingsItem(
                                    title = stringResource(R.string.plugin_title),
                                    subtitle = stringResource(R.string.plugin_installed),
                                    icon = Icons.Default.Extension,
                                    onClick = onNavigateToPlugins
                                )
                            }
                        }

                        if (sectionMatches(notificationsTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(notificationsTitle) }
                            item {
                                SettingsItem(
                                    title = stringResource(R.string.smart_notif_title),
                                    subtitle = stringResource(R.string.smart_notif_desc),
                                    icon = Icons.Default.NotificationsActive,
                                    onClick = { }
                                )
                            }
                        }

                        if (sectionMatches(aboutTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(aboutTitle) }
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

                        // 实验性功能 — 单栏布局下直接展开 FeatureFlag 列表
                        // （experimentalFeaturesSection 内部已含 Header，无需再添加）
                        if (sectionMatches(experimentalTitle)) {
                            item { Spacer(Modifier.height(8.dp)) }
                            experimentalFeaturesSection(
                                flags = experimentalFlags,
                                viewModel = featureFlagViewModel
                            )
                        }

                        if (sectionMatches(insightsTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(insightsTitle) }
                            item {
                                SettingsItem(
                                    title = stringResource(R.string.insights_title),
                                    subtitle = stringResource(R.string.insights_subtitle),
                                    icon = Icons.Default.BarChart,
                                    onClick = onNavigateToInsights
                                )
                            }
                        }

                        if (sectionMatches(dataTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(dataTitle) }
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

                        if (sectionMatches(performanceTitle)) {
                            item { Spacer(Modifier.height(8.dp)); SettingsHeader(performanceTitle) }
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

                        // 搜索无匹配时的提示
                        if (searchText.isNotBlank() && filteredCategories.isEmpty()) {
                            item {
                                Text(
                                    text = "无匹配项",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 设置页搜索相关组件 ──

/**
 * 设置分类元数据，用于搜索过滤与左侧导航列表渲染。
 */
private data class SettingsCategory(
    val key: String,
    val title: String,
    val icon: ImageVector
)

/**
 * 设置页搜索框。与 iOS SettingsView.searchable 对齐：
 * 输入文本即按分类标题过滤（双栏过滤左侧列表，单栏隐藏不匹配 Section）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("搜索设置项") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        // 清空按钮：非空时显示，便于一键重置搜索
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "清除")
                }
            }
        } else null
    )
}
