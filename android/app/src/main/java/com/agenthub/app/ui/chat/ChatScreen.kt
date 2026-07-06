package com.agenthub.app.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agenthub.app.R
import com.agenthub.app.data.model.Message
import com.agenthub.app.data.model.MessageRole
import com.agenthub.app.data.model.MessageStatus
import com.agenthub.app.ui.adaptive.AdaptiveConfig
import android.net.Uri
import com.agenthub.app.ui.adaptive.currentAdaptiveConfig
import com.agenthub.app.ui.adaptive.shouldShowSidebar
import com.agenthub.app.ui.components.ErrorSnackbar
import kotlinx.coroutines.launch
import com.agenthub.app.ui.components.scaleOnPress
import com.agenthub.app.ui.theme.GlassTopAppBar
import com.agenthub.app.ui.theme.LocalIsGlass
import com.agenthub.app.ui.theme.glassBackground
import com.agenthub.app.ui.theme.GlassShapeLg
import androidx.compose.ui.graphics.Color
import com.agenthub.app.util.HapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val adaptive = currentAdaptiveConfig()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    val context = LocalContext.current
    LaunchedEffect(uiState.connectionState.isConnected) {
        if (uiState.connectionState.isConnected) {
            HapticFeedback.medium(context)
        }
    }
    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            HapticFeedback.error(context)
        }
    }

    // Voice Chat Mode overlay
    val voiceChatManager = viewModel.getVoiceChatManager()
    if (uiState.isVoiceChatMode && voiceChatManager != null) {
        VoiceChatOverlay(
            voiceManager = voiceChatManager,
            lastUserText = uiState.voiceChatLastUserText,
            lastAgentText = uiState.voiceChatLastAgentText,
            onExit = { viewModel.exitVoiceChatMode() },
            onSendMessage = { text ->
                viewModel.updateInput(text)
                viewModel.sendMessage()
            }
        )
    } else if (adaptive.shouldShowSidebar) {
        TabletChatLayout(uiState, listState, viewModel, onNavigateToSettings, adaptive)
    } else {
        PhoneChatLayout(uiState, listState, viewModel, onNavigateToSettings, adaptive)
    }

    // Search overlay
    if (uiState.isSearchActive) {
        SearchOverlay(
            query = uiState.searchQuery,
            results = uiState.searchResults,
            onQueryChange = { viewModel.searchMessages(it) },
            onResultClick = { msg ->
                viewModel.closeSearch()
            },
            onClose = { viewModel.closeSearch() }
        )
    }
}

@Composable
private fun PhoneChatLayout(
    uiState: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
    adaptive: AdaptiveConfig
) {
    // 向导模式：全屏覆盖，不显示输入栏
    if (uiState.showWizard) {
        WizardOverlay(
            viewModel = viewModel,
            onDismiss = { viewModel.dismissWizard() },
            onConnect = { url, key, type ->
                viewModel.connectToServer(url, key, type)
            }
        )
        return
    }

    // Voice + Attachment integration
    val context = LocalContext.current
    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setPendingAttachment(context, it, isImage = true) }
    }
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setPendingAttachment(context, it, isImage = false) }
    }

    LaunchedEffect(Unit) {
        viewModel.initVoiceInput(context)
        viewModel.initVoiceChatMode(context)
    }

    val voiceManager = viewModel.getVoiceInputManager()
    if (voiceManager != null) {
        LaunchedEffect(voiceManager) {
            voiceManager.recognizedText.collect { text ->
                if (text.isNotEmpty()) {
                    val current = uiState.inputText
                    viewModel.updateInput(if (current.isEmpty()) text else "$current $text")
                }
            }
        }
    }

    // 正常聊天模式
    Scaffold(
        topBar = { ChatTopBar(uiState, viewModel, onNavigateToSettings) },
        bottomBar = {
            Column {
                // Command palette
                val inputText = uiState.inputText
                if (inputText.startsWith("/")) {
                    CommandPalette(
                        query = inputText,
                        onCommandSelected = { cmd ->
                            if (!viewModel.executeCommand(cmd)) {
                                viewModel.updateInput("")
                            } else {
                                viewModel.updateInput("")
                            }
                        },
                        onDismiss = { viewModel.updateInput("") }
                    )
                }
                ChatInputBar(
                    inputText = uiState.inputText,
                    isStreaming = uiState.isStreaming,
                    onInputChange = { viewModel.updateInput(it) },
                    onSend = { viewModel.sendMessage() },
                    adaptive = adaptive,
                    isVoiceListening = uiState.isVoiceListening,
                    onVoiceToggle = { viewModel.toggleVoiceInput() },
                    onAttachImage = { imagePickerLauncher.launch("image/*") },
                    onAttachFile = { filePickerLauncher.launch("*/*") },
                    pendingAttachmentType = uiState.pendingAttachmentType,
                    pendingAttachmentName = uiState.pendingAttachmentName,
                    onClearAttachment = { viewModel.clearPendingAttachment() }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        if (dragAmount < -80) {
                            viewModel.switchToNextSession()
                        } else if (dragAmount > 80) {
                            viewModel.switchToPreviousSession()
                        }
                    }
                }
        ) {
            ChatContent(uiState, listState, viewModel, adaptive)
        }
    }
}

@Composable
private fun TabletChatLayout(
    uiState: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
    adaptive: AdaptiveConfig
) {
    // 向导模式：全屏覆盖
    if (uiState.showWizard) {
        WizardOverlay(
            viewModel = viewModel,
            onDismiss = { viewModel.dismissWizard() },
            onConnect = { url, key, type ->
                viewModel.connectToServer(url, key, type)
            }
        )
        return
    }

    val sidebarWidth = adaptive.panelConfig.sidebarWidth

    Row(modifier = Modifier.fillMaxSize()) {
        // Sessions sidebar — dynamic width from adaptive config
        Surface(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.nav_sessions),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(
                        onClick = { viewModel.createNewSession() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat), modifier = Modifier.size(18.dp))
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(uiState.sessions, key = { it.id }) { session ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.switchToSession(session.id) },
                            color = if (session.id == uiState.currentSessionId)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (session.isPinned) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = session.title.ifEmpty { stringResource(R.string.untitled) },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Chat area
        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = { ChatTopBar(uiState, viewModel, onNavigateToSettings) },
            bottomBar = {
                Column {
                    // Command palette
                    val inputText = uiState.inputText
                    if (inputText.startsWith("/")) {
                        CommandPalette(
                            query = inputText,
                            onCommandSelected = { cmd ->
                                if (!viewModel.executeCommand(cmd)) {
                                    viewModel.updateInput("")
                                } else {
                                    viewModel.updateInput("")
                                }
                            },
                            onDismiss = { viewModel.updateInput("") }
                        )
                    }
                    ChatInputBar(
                        inputText = uiState.inputText,
                        isStreaming = uiState.isStreaming,
                        onInputChange = { viewModel.updateInput(it) },
                        onSend = { viewModel.sendMessage() },
                        adaptive = adaptive
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
            ChatContent(uiState, listState, viewModel, adaptive)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit
) {
    GlassTopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        // 连接状态指示器
                        val statusColor = when {
                            uiState.connectionState.isConnected -> MaterialTheme.colorScheme.primary
                            uiState.isConnecting -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        }

                        val statusText = when {
                            uiState.connectionState.isConnected -> stringResource(R.string.status_connected)
                            uiState.isConnecting -> stringResource(R.string.btn_connecting)
                            else -> stringResource(R.string.status_disconnected)
                        }

                        // 脉冲动画（仅连接中时）
                        val infiniteTransition = rememberInfiniteTransition(label = "status-pulse")
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = EaseInOut),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulse-alpha"
                        )

                        // 状态指示器
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .semantics {
                                    contentDescription = statusText
                                }
                                .clickable { }
                        ) {
                            Surface(
                                modifier = Modifier.size(10.dp),
                                shape = CircleShape,
                                color = statusColor.copy(alpha = if (uiState.isConnecting) pulseAlpha else 1f)
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    if (uiState.isStreaming) {
                    Text(
                        stringResource(R.string.streaming),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Collaboration indicator
                val collabManager = remember { com.agenthub.app.data.collab.CollaborationManager() }
                val collabState by collabManager.collabState.collectAsState()
                if (collabState.isInSession && collabState.session != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiary
                        ) {}
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.collab_indicator, collabState.session!!.participants.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
        },
        actions = {
            IconButton(onClick = { viewModel.openSearch() }) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
            }
            IconButton(onClick = { viewModel.createNewSession() }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat))
            }
            IconButton(onClick = { viewModel.enterVoiceChatMode() }) {
                Icon(Icons.Default.Headset, contentDescription = stringResource(R.string.voice_mode))
            }
            IconButton(onClick = { viewModel.clearMessages() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
            }
        },
    )
}



@Composable
private fun ChatContent(
    uiState: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: ChatViewModel,
    adaptive: AdaptiveConfig
) {
    val context = LocalContext.current
    val contentMaxWidth = adaptive.panelConfig.contentMaxWidth
    val horizontalPadding = if (adaptive.isTablet) 48.dp else 12.dp

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.messages.isEmpty()) {
            EmptyChatPlaceholder(
                connectionState = uiState.connectionState,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding)
                    .widthIn(max = contentMaxWidth)
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    MessageBubble(
                        message = message,
                        onDelete = { viewModel.deleteMessage(message.id) },
                        onCopy = { viewModel.copyToClipboard(context, message.content) },
                        onReaction = { viewModel.toggleMessageReaction(message.id) }
                    )
                }
            }
        }

        uiState.errorMessage?.let { error ->
            ErrorSnackbar(
                message = error,
                onDismiss = { viewModel.dismissError() },
                onRetry = { viewModel.dismissError() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    isStreaming: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    adaptive: AdaptiveConfig,
    isVoiceListening: Boolean = false,
    onVoiceToggle: () -> Unit = {},
    onAttachImage: () -> Unit = {},
    onAttachFile: () -> Unit = {},
    pendingAttachmentType: String? = null,
    pendingAttachmentName: String? = null,
    onClearAttachment: () -> Unit = {}
) {
    val inputMaxWidth = adaptive.panelConfig.inputMaxWidth
    val context = LocalContext.current

    // Voice pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "voice-pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-scale"
    )

    val isGlassBar = LocalIsGlass.current
    Surface(
        tonalElevation = if (isGlassBar) 0.dp else 3.dp,
        shadowElevation = if (isGlassBar) 0.dp else 8.dp,
        color = if (isGlassBar) Color.Transparent else MaterialTheme.colorScheme.surface,
        modifier = if (isGlassBar) Modifier.glassBackground(shape = GlassShapeLg) else Modifier
    ) {
        Column {
            // Pending attachment preview
            if (pendingAttachmentType != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (pendingAttachmentType == "image") Icons.Default.Image else Icons.Default.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pendingAttachmentName ?: stringResource(R.string.attachment_preview),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = onClearAttachment,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = inputMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Attachment button
                    var showAttachMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showAttachMenu = true },
                            enabled = !isStreaming && !isVoiceListening
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = stringResource(R.string.attach_file),
                                tint = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (!isStreaming && !isVoiceListening) 0.6f else 0.2f
                                )
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_image)) },
                                onClick = { showAttachMenu = false; onAttachImage() },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_document)) },
                                onClick = { showAttachMenu = false; onAttachFile() },
                                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(20.dp)) }
                            )
                        }
                    }

                    // Voice button
                    IconButton(
                        onClick = { HapticFeedback.light(context); onVoiceToggle() },
                        modifier = if (isVoiceListening) Modifier.scale(pulseScale) else Modifier
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = if (isVoiceListening)
                                stringResource(R.string.voice_input_stop)
                            else
                                stringResource(R.string.voice_input_start),
                            tint = if (isVoiceListening)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (isVoiceListening) stringResource(R.string.voice_listening)
                                else stringResource(R.string.hint_type_message)
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        ),
                        enabled = !isStreaming,
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // 发送按钮 - 有输入时变色 + 动画
                    val buttonEnabled = inputText.isNotBlank() || isStreaming || pendingAttachmentType != null
                    val buttonColor by animateColorAsState(
                        targetValue = if (buttonEnabled) MaterialTheme.colorScheme.primary
                                      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        animationSpec = tween(200),
                        label = "send-button-color"
                    )

                    FilledIconButton(
                        onClick = {
                            HapticFeedback.light(context)
                            onSend()
                        },
                        enabled = buttonEnabled,
                        modifier = Modifier
                            .size(48.dp)
                            .scaleOnPress(enabled = buttonEnabled),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = buttonColor,
                            contentColor = if (buttonEnabled) MaterialTheme.colorScheme.onPrimary
                                          else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    ) {
                        if (isStreaming) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(R.string.action_stop),
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = stringResource(R.string.action_send),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Message bubble with long-press context menu and status indicator.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    onDelete: () -> Unit = {},
    onCopy: () -> Unit = {},
    onReaction: () -> Unit = {}
) {
    val isUser = message.role == MessageRole.User
    val isStreaming = message.status == MessageStatus.Sending
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    var showContextMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 })
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box {
            val bubbleShape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            )
            val isGlassBubble = LocalIsGlass.current
            Surface(
                shape = bubbleShape,
                color = if (isGlassBubble) Color.Transparent
                        else if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (isGlassBubble) 0.dp else if (isUser) 0.dp else 1.dp,
                modifier = Modifier
                    .then(
                        if (isGlassBubble) Modifier.glassBackground(
                            tintColor = if (isUser) MaterialTheme.colorScheme.primary else Color.White,
                            shape = bubbleShape
                        ) else Modifier
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showContextMenu = true
                        },
                        onDoubleClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReaction()
                        }
                    )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (isStreaming) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        MarkdownText(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isUser)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Timestamp + Status indicator row
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = formatTimestamp(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser)
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        // Message status indicator (only for user messages)
                        if (isUser) {
                            MessageStatusIndicator(status = message.status)
                        }
                        // Reaction emoji
                        if (message.reaction.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = message.reaction,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // Context menu
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    onClick = {
                        showContextMenu = false
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("AgentHub Message", message.content)
                        clipboard.setPrimaryClip(clip)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.a11y_copy_message), modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDelete()
                    },
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

/**
 * Message status indicator icon with animations.
 */
@Composable
fun MessageStatusIndicator(status: MessageStatus) {
    when (status) {
        MessageStatus.Sending -> {
            // Rotating clock icon
            val infiniteTransition = rememberInfiniteTransition(label = "sending-rotation")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "clock-rotation"
            )
            Icon(
                Icons.Default.Schedule,
                contentDescription = "Sending",
                modifier = Modifier
                    .size(12.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
        MessageStatus.Sent -> {
            // Single check
            Icon(
                Icons.Default.Done,
                contentDescription = "Sent",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
        MessageStatus.Received -> {
            // Double check
            Icon(
                Icons.Default.DoneAll,
                contentDescription = "Received",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        MessageStatus.Failed -> {
            // Red exclamation with retry
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = "Failed",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Full Markdown renderer using Compose Column + AnnotatedString.
 * Parses the text into [MarkdownBlock]s and renders each one.
 */
@Composable
fun MarkdownText(
    text: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            MarkdownBlockView(block, style, color, uriHandler)
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    baseStyle: androidx.compose.ui.text.TextStyle,
    baseColor: androidx.compose.ui.graphics.Color,
    uriHandler: androidx.compose.ui.platform.UriHandler
) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val headingStyle = when (block.level) {
                1 -> MaterialTheme.typography.headlineLarge
                2 -> MaterialTheme.typography.headlineMedium
                3 -> MaterialTheme.typography.headlineSmall
                else -> MaterialTheme.typography.titleLarge
            }
            Text(
                text = block.text,
                style = headingStyle,
                fontWeight = FontWeight.Bold,
                color = baseColor
            )
        }

        is MarkdownBlock.Paragraph -> {
            val annotated = remember(block.spans, baseColor) {
                buildSpanAnnotatedString(block.spans, baseColor)
            }
            ClickableAnnotatedText(
                text = annotated,
                style = baseStyle,
                color = baseColor,
                uriHandler = uriHandler
            )
        }

        is MarkdownBlock.CodeBlock -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    if (block.language.isNotEmpty()) {
                        Text(
                            text = block.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = baseColor.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        text = block.code,
                        style = baseStyle.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseStyle.fontSize.value * 0.9).sp
                        ),
                        color = baseColor
                    )
                }
            }
        }

        is MarkdownBlock.BlockQuote -> {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                Surface(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(2.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                val annotated = remember(block.spans, baseColor) {
                    buildSpanAnnotatedString(block.spans, baseColor)
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        ClickableAnnotatedText(
                            text = annotated,
                            style = baseStyle,
                            color = baseColor.copy(alpha = 0.8f),
                            uriHandler = uriHandler
                        )
                    }
                }
            }
        }

        is MarkdownBlock.UnorderedList -> {
            Column(modifier = Modifier.padding(start = 8.dp)) {
                block.items.forEach { spans ->
                    Row {
                        Text(text = "\u2022  ", style = baseStyle, color = baseColor)
                        val annotated = remember(spans, baseColor) {
                            buildSpanAnnotatedString(spans, baseColor)
                        }
                        ClickableAnnotatedText(
                            text = annotated,
                            style = baseStyle,
                            color = baseColor,
                            uriHandler = uriHandler
                        )
                    }
                }
            }
        }

        is MarkdownBlock.OrderedList -> {
            Column(modifier = Modifier.padding(start = 8.dp)) {
                block.items.forEachIndexed { idx, spans ->
                    Row {
                        Text(text = "${idx + 1}.  ", style = baseStyle, color = baseColor)
                        val annotated = remember(spans, baseColor) {
                            buildSpanAnnotatedString(spans, baseColor)
                        }
                        ClickableAnnotatedText(
                            text = annotated,
                            style = baseStyle,
                            color = baseColor,
                            uriHandler = uriHandler
                        )
                    }
                }
            }
        }

        is MarkdownBlock.Table -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        block.headers.forEach { header ->
                            Text(
                                text = header,
                                style = baseStyle.copy(fontWeight = FontWeight.Bold),
                                color = baseColor,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    block.rows.forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            row.forEach { cell ->
                                Text(
                                    text = cell,
                                    style = baseStyle,
                                    color = baseColor,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        is MarkdownBlock.Divider -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = baseColor.copy(alpha = 0.2f)
            )
        }
    }
}

/** Build an AnnotatedString from a list of MarkdownSpan with proper styling. */
private fun buildSpanAnnotatedString(
    spans: List<MarkdownSpan>,
    baseColor: androidx.compose.ui.graphics.Color
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when (span) {
            is MarkdownSpan.Text -> {
                withStyle(SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null
                )) {
                    append(span.text)
                }
            }
            is MarkdownSpan.Code -> {
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = baseColor.copy(alpha = 0.1f),
                    fontSize = 13.sp
                )) {
                    append(" ${span.text} ")
                }
            }
            is MarkdownSpan.Link -> {
                val tag = "link_${span.url.hashCode()}"
                pushStringAnnotation(tag = tag, annotation = span.url)
                withStyle(SpanStyle(
                    color = androidx.compose.ui.graphics.Color(0xFF1976D2),
                    textDecoration = TextDecoration.Underline
                )) {
                    append(span.text)
                }
                pop()
            }
        }
    }
}

/**
 * Renders an AnnotatedString and handles link click annotations via [uriHandler].
 */
@Composable
private fun ClickableAnnotatedText(
    text: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    uriHandler: androidx.compose.ui.platform.UriHandler
) {
    val layoutResult = remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val uriHandlerRef = rememberUpdatedState(uriHandler)

    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { layoutResult.value = it },
        modifier = Modifier.clickable {
            layoutResult.value?.let {
                text.getStringAnnotations(start = 0, end = text.length)
                    .firstOrNull()
                    ?.let { annotation ->
                        try { uriHandlerRef.value.openUri(annotation.item) } catch (_: Exception) {}
                    }
            }
        }
    )
}

@Composable
fun EmptyChatPlaceholder(
    connectionState: com.agenthub.app.data.model.ConnectionState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
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
    onConnect: (String, String, com.agenthub.app.data.model.AgentType) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var serverUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(com.agenthub.app.data.model.AgentType.Hermes) }
    var showSaved by remember { mutableStateOf(false) }
    val isConnecting = uiState.isConnecting

    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) {
            return "ws://$trimmed"
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
                        shape = RoundedCornerShape(12.dp),
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
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.SmartToy,
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
                    shape = RoundedCornerShape(12.dp)
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
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Protocol selector — dropdown menu (no text overflow)
                var expanded by remember { mutableStateOf(false) }
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        com.agenthub.app.data.model.AgentType.entries.forEach { type ->
                            DropdownMenuItem(
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
                        shape = RoundedCornerShape(8.dp),
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

/**
 * Search overlay with results list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchOverlay(
    query: String,
    results: List<Message>,
    onQueryChange: (String) -> Unit,
    onResultClick: (Message) -> Unit,
    onClose: () -> Unit
) {
    val isGlassSearch = LocalIsGlass.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (isGlassSearch) Color.Transparent else MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                }
            )

            if (query.isNotBlank() && results.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.search_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.id }) { message ->
                        SearchResultItem(
                            message = message,
                            query = query,
                            onClick = { onResultClick(message) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    message: Message,
    query: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (message.role == MessageRole.User) stringResource(R.string.search_role_user) else stringResource(R.string.search_role_agent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val snippet = message.content.take(200)
            val highlightedText = remember(snippet, query) {
                buildAnnotatedString {
                    val lowerSnippet = snippet.lowercase()
                    val lowerQuery = query.lowercase()
                    var start = 0
                    while (true) {
                        val idx = lowerSnippet.indexOf(lowerQuery, start)
                        if (idx < 0) {
                            append(snippet.substring(start))
                            break
                        }
                        append(snippet.substring(start, idx))
                        pushStyle(SpanStyle(
                            background = androidx.compose.ui.graphics.Color(0xFFFFF176).copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold
                        ))
                        append(snippet.substring(idx, idx + query.length))
                        pop()
                        start = idx + query.length
                    }
                }
            }
            Text(
                text = highlightedText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
