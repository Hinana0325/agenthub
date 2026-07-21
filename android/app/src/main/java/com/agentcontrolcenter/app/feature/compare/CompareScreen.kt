package com.agentcontrolcenter.app.feature.compare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.feature.chat.MarkdownText
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar

private val WHITESPACE_REGEX = "\\s+".toRegex()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    viewModel: CompareViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val adaptive = currentAdaptiveConfig()

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = {
                    Text(stringResource(R.string.compare_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.cancelCompare()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.compare_cancel)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Error display
            AnimatedVisibility(visible = uiState.error != null) {
                uiState.error?.let { error ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            // Agent name labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                AgentLabel(
                    name = uiState.agentAName,
                    modifier = Modifier.weight(1f),
                    isComplete = uiState.isAComplete
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.compare_vs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                Spacer(modifier = Modifier.width(8.dp))
                AgentLabel(
                    name = uiState.agentBName,
                    modifier = Modifier.weight(1f),
                    isComplete = uiState.isBComplete
                )
            }

            // Response panels - horizontal on tablet, vertical on phone
            if (adaptive.isTablet && adaptive.isLandscape) {
                // Tablet landscape: side by side
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    ResponsePanel(
                        response = uiState.agentAResponse,
                        isStreaming = uiState.isComparing && !uiState.isAComplete,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 8.dp, end = 4.dp, bottom = 4.dp)
                    )
                    ResponsePanel(
                        response = uiState.agentBResponse,
                        isStreaming = uiState.isComparing && !uiState.isBComplete,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 4.dp, end = 8.dp, bottom = 4.dp)
                    )
                }
            } else {
                // Phone or tablet portrait: stacked vertically
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    ResponsePanel(
                        response = uiState.agentAResponse,
                        isStreaming = uiState.isComparing && !uiState.isAComplete,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    ResponsePanel(
                        response = uiState.agentBResponse,
                        isStreaming = uiState.isComparing && !uiState.isBComplete,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Compare preview (shown when both complete)
            AnimatedVisibility(
                visible = uiState.isAComplete && uiState.isBComplete
            ) {
                ComparePreview(
                    responseA = uiState.agentAResponse,
                    responseB = uiState.agentBResponse,
                    agentAName = uiState.agentAName,
                    agentBName = uiState.agentBName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Hint when not started
            if (!uiState.isComparing && !uiState.isAComplete && !uiState.isBComplete && uiState.agentAResponse.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.compare_select_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentLabel(
    name: String,
    modifier: Modifier = Modifier,
    isComplete: Boolean = false
) {
    Surface(
        modifier = modifier.semantics {
            contentDescription = "$name" + if (isComplete) ", completed" else ""
        },
        shape = RoundedCornerShape(8.dp),
        color = if (isComplete)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (isComplete) {
                Text(
                    text = " ✓",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ResponsePanel(
    response: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.semantics {
            contentDescription = "Response: ${response.take(100)}"
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (isStreaming) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }
            if (response.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    MarkdownText(text = response)
                }
            }
        }
    }
}

@Composable
private fun ComparePreview(
    responseA: String,
    responseB: String,
    agentAName: String,
    agentBName: String,
    modifier: Modifier = Modifier
) {
    val wordCountA = responseA.split(WHITESPACE_REGEX).filter { it.isNotBlank() }.size
    val wordCountB = responseB.split(WHITESPACE_REGEX).filter { it.isNotBlank() }.size

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.compare_summary),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CompareStat(
                    label = agentAName,
                    chars = responseA.length,
                    words = wordCountA
                )
                CompareStat(
                    label = agentBName,
                    chars = responseB.length,
                    words = wordCountB
                )
            }
        }
    }
}

@Composable
private fun CompareStat(
    label: String,
    chars: Int,
    words: Int
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.compare_chars, chars),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.compare_words, words),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
