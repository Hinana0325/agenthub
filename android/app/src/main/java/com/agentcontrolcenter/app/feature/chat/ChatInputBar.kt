package com.agentcontrolcenter.app.feature.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.core.ui.HapticFeedback
import com.agentcontrolcenter.app.ui.adaptive.AdaptiveConfig
import com.agentcontrolcenter.app.ui.theme.AppDropdownMenu
import com.agentcontrolcenter.app.ui.theme.AppDropdownMenuItem
import com.agentcontrolcenter.app.ui.theme.LocalIsGlass
import com.agentcontrolcenter.app.ui.theme.ShapePill
import com.agentcontrolcenter.app.ui.theme.glassBackground

@Composable
fun ChatInputBar(
    inputText: String,
    isStreaming: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    adaptive: AdaptiveConfig,
    isVoiceListening: Boolean = false,
    onVoiceToggle: () -> Unit = {},
    onAttachImage: () -> Unit = {},
    onAttachFile: () -> Unit = {},
    pendingAttachmentType: String? = null,
    pendingAttachmentName: String? = null,
    onClearAttachment: () -> Unit = {},
    isEditing: Boolean = false,
    onCancelEdit: () -> Unit = {},
    replyContent: String? = null,
    onCancelReply: () -> Unit = {}
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
        modifier = if (isGlassBar) Modifier.glassBackground(
            shape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart = 0.dp,
                bottomEnd = 0.dp
            )
        ) else Modifier
    ) {
        Column {
            // Editing indicator
            if (isEditing) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.editing_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onCancelEdit,
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
            // Reply indicator
            if (replyContent != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Reply,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            replyContent,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = onCancelReply,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
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
                    // M3 Expressive 风格：附件 + 语音合并为连接式按钮组
                    var showAttachMenu by remember { mutableStateOf(false) }
                    Surface(
                        shape = ShapePill,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 2.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Attachment button
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
                                AppDropdownMenu(
                                    expanded = showAttachMenu,
                                    onDismissRequest = { showAttachMenu = false }
                                ) {
                                    AppDropdownMenuItem(
                                        text = { Text(stringResource(R.string.attach_image)) },
                                        onClick = { showAttachMenu = false; onAttachImage() },
                                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp)) }
                                    )
                                    AppDropdownMenuItem(
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
                        }
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

                    // 发送按钮 - 有输入时变色 + M3 Expressive 弹性按压动画
                    val buttonEnabled = inputText.isNotBlank() || isStreaming || pendingAttachmentType != null
                    val buttonColor by animateColorAsState(
                        targetValue = if (buttonEnabled) MaterialTheme.colorScheme.primary
                                      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        animationSpec = tween(200),
                        label = "send-button-color"
                    )
                    // M3 Expressive: spring 弹性按压缩放（中弹性 + 中低刚度）
                    var isSendPressed by remember { mutableStateOf(false) }
                    val sendScale by animateFloatAsState(
                        targetValue = if (isSendPressed) 0.9f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "sendButtonScale"
                    )

                    FilledIconButton(
                        onClick = {
                            HapticFeedback.light(context)
                            // 流式响应中点击发送按钮（变身为 Stop）应取消流式，
                            // 而不是再次调用 sendMessage()（后者会因 isStreaming 直接 return）。
                            if (isStreaming) onStop() else onSend()
                        },
                        enabled = buttonEnabled,
                        modifier = Modifier
                            .size(48.dp)
                            .scale(sendScale)
                            .pointerInput(buttonEnabled) {
                                if (!buttonEnabled) return@pointerInput
                                detectTapGestures(
                                    onPress = {
                                        isSendPressed = true
                                        tryAwaitRelease()
                                        isSendPressed = false
                                    }
                                )
                            },
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
