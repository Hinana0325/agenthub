package com.agentcontrolcenter.app.feature.agents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.ui.adaptive.WindowSize
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.ui.components.EmptyStateView
import com.agentcontrolcenter.app.ui.components.AgentCardSkeletonItem
import com.agentcontrolcenter.app.ui.theme.GlassCard
import com.agentcontrolcenter.app.ui.theme.GlassTopAppBar
import com.agentcontrolcenter.app.ui.theme.GlassDropdownMenu
import com.agentcontrolcenter.app.ui.theme.GlassDropdownMenuItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    agentsViewModel: AgentsViewModel = hiltViewModel()
) {
    val uiState by agentsViewModel.uiState.collectAsStateWithLifecycle()
    val adaptive = currentAdaptiveConfig()
    val useGrid = adaptive.windowSize == WindowSize.Expanded
    val context = LocalContext.current
    var showFabMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Import file launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { agentsViewModel.importConfigs(context, it) }
    }

    // Observe export message
    LaunchedEffect(uiState.exportMessage) {
        uiState.exportMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            agentsViewModel.clearExportMessage()
        }
    }

    // Render the form dialog only when an agent is actually being edited.
    // Do NOT use an early `return` here: that would skip the entire Scaffold
    // and render a blank screen whenever showForm is true but editingAgent is
    // momentarily null (e.g. during a transient state update). Instead we
    // conditionally render the dialog on top of the normal list UI.
    uiState.editingAgent?.let { agent ->
        if (uiState.showForm) {
            AgentFormDialog(
                agent = agent,
                onSave = { agentsViewModel.saveAgent(it) },
                onDismiss = { agentsViewModel.dismissForm() }
            )
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassTopAppBar(
                title = { Text(stringResource(R.string.nav_agents)) },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // FAB menu items
                GlassDropdownMenu(
                    expanded = showFabMenu,
                    onDismissRequest = { showFabMenu = false }
                ) {
                    GlassDropdownMenuItem(
                        text = { Text(stringResource(R.string.new_agent)) },
                        onClick = {
                            showFabMenu = false
                            agentsViewModel.showNewForm()
                        },
                        leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                    GlassDropdownMenuItem(
                        text = { Text(stringResource(R.string.import_configs)) },
                        onClick = {
                            showFabMenu = false
                            importLauncher.launch(arrayOf("application/json"))
                        },
                        leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                    GlassDropdownMenuItem(
                        text = { Text(stringResource(R.string.export_configs)) },
                        onClick = {
                            showFabMenu = false
                            agentsViewModel.exportConfigs(context)
                        },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                FloatingActionButton(
                    onClick = { showFabMenu = !showFabMenu },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = stringResource(R.string.new_agent)
                    )
                }
            }
        }
    ) { padding ->
        if (uiState.agents.isEmpty() && uiState.isLoading) {
            // 首屏加载：显示骨架屏占位
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
                        items(4) {
                            AgentCardSkeletonItem()
                        }
                    }
                }
            }
        } else if (uiState.agents.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.SmartToy,
                title = stringResource(R.string.no_agents),
                description = stringResource(R.string.no_agents_subtitle),
                actionText = stringResource(R.string.new_agent),
                onAction = { agentsViewModel.showNewForm() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else if (useGrid) {
            // Grid layout for Expanded: 2-3 columns
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.agents, key = { it.id }) { agent ->
                    AgentGridCard(
                        agent = agent,
                        onEdit = { agentsViewModel.showEditForm(agent) },
                        onDelete = { agentsViewModel.deleteAgent(agent.id) }
                    )
                }
            }
        } else {
            // Single column list for Compact
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
                        items(uiState.agents, key = { it.id }) { agent ->
                            AgentCard(
                                agent = agent,
                                onEdit = { agentsViewModel.showEditForm(agent) },
                                onDelete = { agentsViewModel.deleteAgent(agent.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentGridCard(
    agent: AgentConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_agent_title)) },
            text = { Text(stringResource(R.string.delete_agent_message, agent.name)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    Box {
    GlassCard(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "Agent: ${agent.name}, type: ${agent.type.displayName}"
        }.combinedClickable(
            onClick = onEdit,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showContextMenu = true
            }
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = agent.name.take(2).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = agent.type.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
            if (agent.serverUrl.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = agent.serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    // Context menu
    GlassDropdownMenu(
        expanded = showContextMenu,
        onDismissRequest = { showContextMenu = false }
    ) {
        GlassDropdownMenuItem(
            text = { Text(stringResource(R.string.btn_edit)) },
            onClick = { showContextMenu = false; onEdit() },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        GlassDropdownMenuItem(
            text = { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) },
            onClick = { showContextMenu = false; showDeleteConfirm = true },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
        GlassDropdownMenuItem(
            text = { Text(stringResource(R.string.copy_config)) },
            onClick = { showContextMenu = false },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
    }
    } // Box
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentCard(
    agent: AgentConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_agent_title)) },
            text = { Text(stringResource(R.string.delete_agent_message, agent.name)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    Box {
    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics {
            contentDescription = "Agent: ${agent.name}, type: ${agent.type.displayName}"
        }.combinedClickable(
            onClick = onEdit,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showContextMenu = true
            }
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = agent.name.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = agent.type.displayName + if (agent.serverUrl.isNotEmpty()) " · ${agent.serverUrl}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }

    // Context menu
    GlassDropdownMenu(
        expanded = showContextMenu,
        onDismissRequest = { showContextMenu = false }
    ) {
        GlassDropdownMenuItem(
            text = { Text(stringResource(R.string.btn_edit)) },
            onClick = { showContextMenu = false; onEdit() },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        GlassDropdownMenuItem(
            text = { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) },
            onClick = { showContextMenu = false; showDeleteConfirm = true },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
        GlassDropdownMenuItem(
            text = { Text(stringResource(R.string.copy_config)) },
            onClick = { showContextMenu = false },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
    }
    } // Box
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentFormDialog(
    agent: AgentConfig,
    onSave: (AgentConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(agent.name) }
    var serverUrl by remember { mutableStateOf(agent.serverUrl) }
    var apiKey by remember { mutableStateOf(agent.apiKey) }
    var type by remember { mutableStateOf(agent.type) }
    var model by remember { mutableStateOf(agent.model) }
    var systemPrompt by remember { mutableStateOf(agent.systemPrompt) }
    var temperature by remember { mutableStateOf(agent.temperature.toString()) }
    var maxTokens by remember { mutableStateOf(agent.maxTokens.toString()) }

    // ── 实时字段验证：每次输入变化时重新计算，错误状态即时反馈 ──
    val isNameError = name.isBlank()
    val isServerUrlError = serverUrl.isBlank() ||
        (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://"))
    val isApiKeyError = apiKey.length < 10
    // 所有字段验证通过时才允许保存
    val isFormValid = !isNameError && !isServerUrlError && !isApiKeyError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (agent.serverUrl.isBlank()) stringResource(R.string.new_agent) else stringResource(R.string.btn_edit) + " " + agent.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = isNameError,
                    supportingText = if (isNameError) {
                        { Text(stringResource(R.string.error_name_required)) }
                    } else null
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(R.string.label_server_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = isServerUrlError,
                    supportingText = if (isServerUrlError) {
                        {
                            Text(
                                if (serverUrl.isBlank()) stringResource(R.string.error_server_url_required)
                                else stringResource(R.string.error_server_url_invalid_scheme)
                            )
                        }
                    } else null
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.label_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = isApiKeyError,
                    supportingText = if (isApiKeyError) {
                        { Text(stringResource(R.string.error_api_key_too_short)) }
                    } else null
                )
                Spacer(modifier = Modifier.height(8.dp))
                TypeSelector(
                    selected = type,
                    onSelect = { type = it }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.label_model)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.label_system_prompt)) },
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text(stringResource(R.string.label_temperature)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text(stringResource(R.string.label_max_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(agent.copy(
                        name = name,
                        serverUrl = serverUrl,
                        apiKey = apiKey,
                        type = type,
                        model = model,
                        systemPrompt = systemPrompt,
                        temperature = temperature.toFloatOrNull() ?: 0.7f,
                        maxTokens = maxTokens.toIntOrNull() ?: 4096
                    ))
                },
                enabled = isFormValid
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(
    selected: AgentType,
    onSelect: (AgentType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    // Listen for click events on the text field to toggle dropdown
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                expanded = !expanded
            }
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.label_protocol)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AgentType.entries.forEach { agentType ->
                GlassDropdownMenuItem(
                    text = { Text(agentType.displayName) },
                    onClick = { onSelect(agentType); expanded = false }
                )
            }
        }
    }
}
