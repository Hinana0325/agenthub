package com.agentcontrolcenter.app.feature.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeS12
import com.agentcontrolcenter.app.ui.theme.ShapeS8
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.agent.model.AgentTypeUi
import com.agentcontrolcenter.app.agent.model.ConnectionState
import com.agentcontrolcenter.app.ui.theme.AppDropdownMenuItem

@Composable
internal fun OfflineBanner(onConnect: () -> Unit) {
    val bannerText = stringResource(R.string.a11y_offline_banner)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = bannerText
            },
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.offline_banner_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.offline_banner_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                )
            }
            TextButton(onClick = onConnect) {
                Text(
                    text = stringResource(R.string.offline_banner_action),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun EmptyChatPlaceholder(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    val emptyChatText = stringResource(R.string.a11y_empty_chat)
    Column(
        modifier = modifier.semantics {
            contentDescription = emptyChatText
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Chat,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.empty_chat_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_chat_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardOverlay(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onConnect: (String, String, AgentType) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var serverUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(AgentType.Hermes) }
    var showSaved by remember { mutableStateOf(false) }
    val isConnecting = uiState.isConnecting

    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        // Only add ws:// prefix for WebSocket-based agents (Hermes/OpenClaw/OpenCode)
        // HTTP-based agents (OpenAI/LocalModel/XiaomiMiMo/ComfyUI/OpenWebUI) keep their http(s):// URL
        if (selectedType in setOf(
                AgentType.Hermes,
                AgentType.OpenClaw,
                AgentType.OpenCode
            )
        ) {
            if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) {
                return "ws://$trimmed"
            }
        }
        return trimmed
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .systemBarsPadding(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // WiFi icon with pulse animation
                val infiniteTransition = rememberInfiniteTransition(label = "wifi-pulse")
                val iconAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = EaseInOut),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "wifi-alpha"
                )
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).alpha(iconAlpha),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                // Main title — bold and prominent
                Text(
                    stringResource(R.string.wizard_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Guidance subtitle
                Text(
                    stringResource(R.string.wizard_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Saved agents quick pick
                if (uiState.agentConfigs.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { showSaved = !showSaved },
                        shape = ShapeS12,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.saved_agents, uiState.agentConfigs.size), style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                if (showSaved) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }
                    if (showSaved) {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                            uiState.agentConfigs.forEach { agent ->
                                val isSelected = serverUrl == agent.serverUrl && selectedType == agent.type
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        serverUrl = agent.serverUrl
                                        apiKey = agent.apiKey
                                        selectedType = agent.type
                                        showSaved = false
                                    },
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else
                                        MaterialTheme.colorScheme.surface
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Agent type icon
                                        Surface(
                                            modifier = Modifier.size(32.dp),
                                            shape = ShapeS8,
                                            color = if (isSelected)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    AgentTypeUi.icon(agent.type),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isSelected)
                                                        MaterialTheme.colorScheme.onPrimary
                                                    else
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(agent.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${agent.type.displayName} · ${agent.serverUrl}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (agent.apiKey.isNotBlank()) {
                                                Text(
                                                    stringResource(R.string.api_key_saved_masked),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                                )
                                            }
                                        }
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = "Selected",
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Server URL — floating label + rounded shape
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(R.string.label_server_url)) },
                    placeholder = { Text("192.168.1.100:8080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = ShapeS12
                )
                Spacer(modifier = Modifier.height(12.dp))

                // API Key — floating label + password keyboard
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.label_api_key)) },
                    placeholder = { Text(stringResource(R.string.optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    shape = ShapeS12
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Protocol selector — dropdown menu (no text overflow)
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
                Text(stringResource(R.string.label_agent_protocol), style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedType.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_protocol)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = ShapeS12
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        AgentType.entries.forEach { type ->
                            AppDropdownMenuItem(
                                text = { Text(type.displayName) },
                                onClick = { selectedType = type; expanded = false }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { onConnect(normalizeUrl(serverUrl), apiKey, selectedType) },
                    enabled = serverUrl.isNotBlank() && !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isConnecting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_connecting))
                        }
                    } else {
                        Text(stringResource(R.string.btn_connect))
                    }
                }

                // 连接错误提示
                uiState.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ShapeS8,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = onDismiss, enabled = !isConnecting) {
                    Text(stringResource(R.string.btn_skip))
                }
            }
        }
    }
}
