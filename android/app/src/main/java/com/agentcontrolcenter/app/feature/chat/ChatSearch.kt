package com.agentcontrolcenter.app.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeS12
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.data.model.Message
import com.agentcontrolcenter.app.data.model.MessageRole
import com.agentcontrolcenter.app.ui.theme.AppTopAppBar

/**
 * Search overlay with results list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchOverlay(
    query: String,
    results: List<Message>,
    onQueryChange: (String) -> Unit,
    onResultClick: (Message) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = ShapeS12,
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
        shape = ShapeS12,
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
            val highlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
            val highlightedText = remember(snippet, query, highlightColor) {
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
                            background = highlightColor,
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
