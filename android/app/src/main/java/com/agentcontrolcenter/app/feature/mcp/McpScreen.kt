package com.agentcontrolcenter.app.feature.mcp

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.mcp.bridge.McpBridge
import com.agentcontrolcenter.app.mcp.model.McpServer
import com.agentcontrolcenter.app.mcp.model.McpTransportType
import com.agentcontrolcenter.app.ui.theme.AppCard
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar

/**
 * MCP 服务器管理页面 — 对齐 iOS McpView。
 *
 * 功能：
 * - 服务器列表（LazyColumn），每项显示名称、URL、传输类型、连接状态
 * - 添加/编辑服务器对话框（名称、URL、传输类型、API Key）
 * - 删除服务器（带确认）
 * - 连接/断开/测试连接按钮
 * - 已注册工具列表
 * - 空状态提示
 * - 顶部 TopAppBar 带返回按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    onBack: () -> Unit = {},
    viewModel: McpViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val servers by viewModel.serversWithState.collectAsStateWithLifecycle()
    val tools by viewModel.availableTools.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 消费 ViewModel 发射的 message（连接/测试结果）
    LaunchedEffect(uiState.message) {
        val msg = uiState.message ?: return@LaunchedEffect
        val text = when (msg) {
            "MCP_TEST_SUCCESS" -> context.getString(R.string.mcp_test_success)
            "MCP_TEST_FAILED" -> context.getString(R.string.mcp_test_failed)
            else -> msg
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
    }

    // 添加/编辑对话框
    uiState.editingServer?.let { editing ->
        if (uiState.showForm) {
            McpServerFormDialog(
                server = editing,
                onSave = { viewModel.saveServer(it) },
                onDismiss = { viewModel.dismissForm() }
            )
        }
    }

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = { Text(stringResource(R.string.mcp_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showAddForm() }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.mcp_add_server))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 服务器列表 section
            item {
                Text(
                    text = stringResource(R.string.mcp_servers),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (servers.isEmpty()) {
                item { EmptyServersState() }
            } else {
                items(servers, key = { it.server.id }) { rowState ->
                    McpServerCard(
                        rowState = rowState,
                        onEdit = { viewModel.showEditForm(rowState.server) },
                        onDelete = { viewModel.deleteServer(rowState.server.id) },
                        onConnect = { viewModel.connectServer(rowState.server) },
                        onDisconnect = { viewModel.disconnectServer(rowState.server.id) },
                        onTest = { viewModel.testConnection(rowState.server) }
                    )
                }
            }

            // 已注册工具 section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.mcp_tools_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (tools.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.mcp_no_tools),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            } else {
                items(tools, key = { it }) { toolName ->
                    ToolRow(name = toolName)
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerCard(
    rowState: McpServerRowState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTest: () -> Unit
) {
    val server = rowState.server
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.mcp_delete_server_title)) },
            text = { Text(stringResource(R.string.mcp_delete_server_message, server.name)) },
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

    val statusColor = connectionStatusColor(rowState.connectionState)
    val isConnected = rowState.connectionState == McpBridge.ConnectionState.Connected
    val isConnecting = rowState.connectionState == McpBridge.ConnectionState.Connecting ||
        rowState.isTesting

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 连接状态指示灯
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = server.transportUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = transportTypeDisplayName(server.transportType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }

                // 状态徽章
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = connectionStateDisplayName(rowState.connectionState),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 能力标签（如果已协商）
            if (server.capabilities.tools || server.capabilities.resources ||
                server.capabilities.prompts || server.capabilities.logging
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (server.capabilities.tools) {
                        CapabilityTag(label = "Tools", icon = Icons.Default.Build)
                    }
                    if (server.capabilities.resources) {
                        CapabilityTag(label = "Resources", icon = Icons.Default.Folder)
                    }
                    if (server.capabilities.prompts) {
                        CapabilityTag(label = "Prompts", icon = Icons.Default.Chat)
                    }
                    if (server.capabilities.logging) {
                        CapabilityTag(label = "Logging", icon = Icons.Default.Notifications)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 操作按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.mcp_connecting),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.weight(1f))

                // 测试连接按钮
                OutlinedButton(
                    onClick = onTest,
                    enabled = !isConnecting && server.transportUrl.isNotBlank()
                ) {
                    Text(stringResource(R.string.mcp_test_connection))
                }

                // 连接/断开按钮
                if (isConnected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = !isConnecting
                    ) {
                        Text(stringResource(R.string.mcp_disconnect))
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = !isConnecting && server.transportUrl.isNotBlank()
                    ) {
                        Text(stringResource(R.string.mcp_connect))
                    }
                }

                // 更多操作菜单
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.mcp_more_actions),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.btn_edit)) },
                            onClick = { showMenu = false; onEdit() },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.btn_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { showMenu = false; showDeleteConfirm = true },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityTag(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToolRow(name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Build,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyServersState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.mcp_no_servers),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.mcp_no_servers_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

// ── Add/Edit Dialog ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerFormDialog(
    server: McpServer,
    onSave: (McpServer) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(server.name) }
    var url by remember { mutableStateOf(server.transportUrl) }
    var apiKey by remember { mutableStateOf(server.apiKey ?: "") }
    var transportType by remember { mutableStateOf(server.transportType) }
    var expanded by remember { mutableStateOf(false) }

    val isNew = server.transportUrl.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) stringResource(R.string.mcp_add_server)
                else stringResource(R.string.mcp_edit_server)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.mcp_label_name)) },
                    placeholder = { Text(stringResource(R.string.mcp_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = transportTypeDisplayName(transportType),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.mcp_label_transport_type)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        McpTransportType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(transportTypeDisplayName(type)) },
                                onClick = { transportType = type; expanded = false }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.mcp_label_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.mcp_label_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    // H5: 视觉掩码，避免 apiKey 明文显示被肩窥/录屏泄漏
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        server.copy(
                            name = name,
                            transportUrl = url,
                            apiKey = apiKey.ifBlank { null },
                            transportType = transportType
                        )
                    )
                },
                enabled = url.isNotBlank()
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

// ── Display helpers ──

@Composable
private fun connectionStateDisplayName(state: McpBridge.ConnectionState): String = when (state) {
    McpBridge.ConnectionState.Disconnected -> stringResource(R.string.mcp_disconnected)
    McpBridge.ConnectionState.Connecting -> stringResource(R.string.mcp_connecting)
    McpBridge.ConnectionState.Connected -> stringResource(R.string.mcp_connected)
    McpBridge.ConnectionState.Failed -> stringResource(R.string.mcp_connection_failed)
}

@Composable
private fun connectionStatusColor(state: McpBridge.ConnectionState): androidx.compose.ui.graphics.Color = when (state) {
    McpBridge.ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    McpBridge.ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
    McpBridge.ConnectionState.Connected -> androidx.compose.ui.graphics.Color(0xFF10B981)
    McpBridge.ConnectionState.Failed -> MaterialTheme.colorScheme.error
}

@Composable
private fun transportTypeDisplayName(type: McpTransportType): String = when (type) {
    McpTransportType.STDIO -> stringResource(R.string.mcp_transport_stdio)
    McpTransportType.SSE -> stringResource(R.string.mcp_transport_sse)
    McpTransportType.HTTP -> stringResource(R.string.mcp_transport_http)
}
