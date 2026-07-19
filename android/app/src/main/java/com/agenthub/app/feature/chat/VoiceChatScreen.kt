package com.agenthub.app.feature.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agenthub.app.R
import com.agenthub.app.core.ui.VoiceChatManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 全屏语音对话界面
 *
 * 包含：
 * - 大圆麦克风按钮（带脉冲动画）
 * - 波形动画
 * - 文字显示（识别中 / Agent 回复）
 */
@Composable
fun VoiceChatOverlay(
    voiceManager: VoiceChatManager,
    lastUserText: String = "",
    lastAgentText: String = "",
    onExit: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    val state by voiceManager.state.collectAsStateWithLifecycle()

    // Auto-send when speech result arrives
    LaunchedEffect(state.recognizedText) {
        if (state.recognizedText.isNotEmpty()) {
            onSendMessage(state.recognizedText)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Background gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.voice_mode),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onExit) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(0.3f))

                // Status text
                Text(
                    text = when {
                        state.isSpeaking -> stringResource(R.string.voice_speaking)
                        state.isListening -> stringResource(R.string.voice_listening)
                        state.isVoiceMode -> stringResource(R.string.voice_waiting)
                        else -> stringResource(R.string.voice_tap_to_start)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Waveform / visualization
                if (state.isListening) {
                    VoiceWaveform(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                    )
                } else if (state.isSpeaking) {
                    SpeakingAnimation(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Main mic button
                VoiceMicButton(
                    isListening = state.isListening,
                    isSpeaking = state.isSpeaking,
                    onClick = {
                        if (state.isVoiceMode) {
                            if (state.isListening) {
                                voiceManager.stopListening()
                            } else if (state.isSpeaking) {
                                voiceManager.stopSpeaking()
                            } else {
                                voiceManager.startListening()
                            }
                        } else {
                            voiceManager.startVoiceMode()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Recognized text display
                if (state.partialText.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = state.partialText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Last recognized text
                if (lastUserText.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.search_role_user),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = lastUserText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Agent response
                if (lastAgentText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.search_role_agent),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = lastAgentText.take(300),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 5
                            )
                        }
                    }
                }

                // Error display
                state.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.weight(0.3f))
            }
        }
    }
}

@Composable
private fun VoiceMicButton(
    isListening: Boolean,
    isSpeaking: Boolean,
    onClick: () -> Unit
) {
    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "mic-pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic-pulse-scale"
    )

    val buttonColor = when {
        isListening -> MaterialTheme.colorScheme.error
        isSpeaking -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(140.dp)
    ) {
        // Outer ring (pulsing when active)
        if (isListening || isSpeaking) {
            Surface(
                modifier = Modifier
                    .size(140.dp)
                    .scale(pulseScale),
                shape = CircleShape,
                color = buttonColor.copy(alpha = 0.1f)
            ) {}
        }

        // Middle ring
        if (isListening) {
            Surface(
                modifier = Modifier
                    .size(120.dp)
                    .scale(pulseScale * 0.95f),
                shape = CircleShape,
                color = buttonColor.copy(alpha = 0.15f)
            ) {}
        }

        // Main button
        Surface(
            modifier = Modifier
                .size(100.dp)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = buttonColor,
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = when {
                        isListening -> Icons.Default.Mic
                        isSpeaking -> Icons.Default.Stop
                        else -> Icons.Default.Mic
                    },
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun VoiceWaveform(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(20) { index ->
            val delay = index * 50
            val height by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = delay, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "wave-$index"
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(4.dp)
                    .fillMaxHeight(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun SpeakingAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "speaking")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(5) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 150, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "speak-$index"
            )

            Surface(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size((12 * scale).dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f + index * 0.1f)
            ) {}
        }
    }
}
