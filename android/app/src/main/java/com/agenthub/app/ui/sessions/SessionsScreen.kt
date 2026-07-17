package com.agenthub.app.ui.sessions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agenthub.app.data.model.Session
import com.agenthub.app.ui.adaptive.currentAdaptiveConfig
import com.agenthub.app.ui.adaptive.shouldShowSidebar
import com.agenthub.app.util.HapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.agenthub.app.R
import com.agenthub.app.ui.chat.ChatViewModel
import com.agenthub.app.ui.chat.MessageBubble
import com.agenthub.app.ui.theme.GlassCard
import com.agenthub.app.ui.theme.GlassTopAppBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    chatViewModel: ChatViewModel? = null
) {
    val adaptive = currentAdaptiveConfig()
    val context = LocalContext.current
    val uiState = chatViewModel?.uiState?.collectAsState()?.value
    val sessionList = uiState?.sessions ?: emptyList()
    val currentSessionId = uiState?.currentSessionId
    val useDualPane = adaptive.shouldShowSidebar // Expanded or Medium+Landscape

    if (useDualPane && chatViewModel != null) {
        SessionsDualPaneLayout(
            sessionList = sessionList,
            currentSessionId = currentSessionId,
            chatViewModel = chatViewModel,
            adaptive = adaptive
        )
    } else {
        SessionsSinglePaneLayout(
            sessionList = sessionList,
            currentSessionId = currentSessionId,
            chatViewModel = chatViewModel,
            adaptive = adaptive
        )
    }
}

/**
 * Dual-pane layout for tablets: session list on the left, selected session messages on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsDualPaneLayout(
    sessionList: List<Session>,
    currentSessionId: String?,
    chatViewModel: ChatViewModel,
    adaptive: com.agenthub.app.ui.adaptive.AdaptiveConfig
) {
    val sidebarWidth = adaptive.panelConfig.sidebarWidth
    val uiState by chatViewModel.uiState.collectAsState()
    val context = LocalContext.current

    var isRefreshing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text(stringResource(R.string.nav_sessions)) }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Left panel — session list
            Surface(
                modifier = Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        chatViewModel.refreshSessions()
                        isRefreshing = false
                    }
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
                                onClick = { chatViewModel.createNewSession() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat), modifier = Modifier.size(18.dp))
                            }
                        }
                        if (sessionList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.no_sessions),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(sessionList, key = { it.id }) { session ->
                                    SwipeableSessionListItem(
                                        session = session,
                                        isSelected = session.id == currentSessionId,
                                        onSelect = { HapticFeedback.light(context); chatViewModel.switchToSession(session.id) },
                                        onDelete = { HapticFeedback.medium(context); chatViewModel.deleteSession(session.id) },
                                        onTogglePin = { HapticFeedback.light(context); chatViewModel.toggleSessionPin(session.id, !session.isPinned) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Right panel — session detail / message preview
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface
            ) {
                if (currentSessionId == null) {
                    // No session selected
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.select_session),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                } else {
                    // Show messages for the selected session
                    val messages = uiState.messages
                    val contentMaxWidth = adaptive.panelConfig.contentMaxWidth

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Session header
                        val selectedSession = sessionList.firstOrNull { it.id == currentSessionId }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedSession?.title?.ifEmpty { stringResource(R.string.untitled) } ?: stringResource(R.string.nav_sessions),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (selectedSession != null) {
                                        Text(
                                            text = "${selectedSession.messageCount} messages",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }

                        // Messages list
                        if (messages.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.no_sessions),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .widthIn(max = contentMaxWidth)
                                    .align(Alignment.CenterHorizontally),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(
                                    items = messages,
                                    key = { it.id }
                                ) { message ->
                                    MessageBubble(message)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single-pane layout for phones: just a session list with pull-to-refresh and swipe gestures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsSinglePaneLayout(
    sessionList: List<Session>,
    currentSessionId: String?,
    chatViewModel: ChatViewModel?,
    adaptive: com.agenthub.app.ui.adaptive.AdaptiveConfig
) {
    val maxContentWidth = if (adaptive.isTablet) 720.dp else 600.dp
    val context = LocalContext.current

    var isRefreshing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text(stringResource(R.string.nav_sessions)) }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier.widthIn(max = maxContentWidth).fillMaxSize()
            ) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        chatViewModel?.refreshSessions()
                        isRefreshing = false
                    }
                ) {
                    if (sessionList.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Forum,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.no_sessions),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.no_sessions_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(sessionList, key = { it.id }) { session ->
                                SwipeableSessionCard(
                                    session = session,
                                    isSelected = session.id == currentSessionId,
                                    onSelect = { HapticFeedback.light(context); chatViewModel?.switchToSession(session.id) },
                                    onDelete = { HapticFeedback.medium(context); chatViewModel?.deleteSession(session.id) },
                                    onTogglePin = { HapticFeedback.light(context); chatViewModel?.toggleSessionPin(session.id, !session.isPinned) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Swipeable session list item for the dual-pane sidebar.
 * Left swipe = delete (red), Right swipe = toggle pin (blue).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSessionListItem(
    session: Session,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onTogglePin()
                    false // Don't dismiss, just trigger the action
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection

            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                    else -> Color.Transparent
                },
                label = "swipe-bg-color"
            )

            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.PushPin
                else -> Icons.Default.Delete
            }

            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true
    ) {
        SessionListItem(
            session = session,
            isSelected = isSelected,
            onSelect = onSelect,
            onDelete = onDelete,
            onTogglePin = onTogglePin
        )
    }
}

/**
 * Swipeable session card for single-pane layout.
 * Left swipe = delete (red), Right swipe = toggle pin (blue).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSessionCard(
    session: Session,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onTogglePin()
                    false // Don't dismiss, just toggle pin
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection

            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                    else -> Color.Transparent
                },
                label = "swipe-card-bg-color"
            )

            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.PushPin
                else -> Icons.Default.Delete
            }

            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true
    ) {
        SessionCard(
            session = session,
            isSelected = isSelected,
            onSelect = onSelect,
            onDelete = onDelete,
            onTogglePin = onTogglePin
        )
    }
}

/**
 * Compact session list item for the dual-pane sidebar.
 */
@Composable
private fun SessionListItem(
    session: Session,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifEmpty { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSessionTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            // Inline actions
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (session.isPinned) "Unpin" else "Pin",
                    modifier = Modifier.size(14.dp),
                    tint = if (session.isPinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: Session,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = session.title.ifEmpty { stringResource(R.string.untitled) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatSessionTime(session.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (session.isPinned) "Unpin" else "Pin",
                    modifier = Modifier.size(20.dp),
                    tint = if (session.isPinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

private fun formatSessionTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
