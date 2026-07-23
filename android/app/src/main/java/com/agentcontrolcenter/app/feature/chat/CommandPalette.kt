package com.agentcontrolcenter.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agentcontrolcenter.app.ui.theme.ShapeM16
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.agentcontrolcenter.app.R

/**
 * Represents a slash command available in the chat input.
 */
data class Command(
    val name: String,
    val descriptionResId: Int,
    val icon: ImageVector
)

/**
 * List of all available slash commands.
 */
val availableCommands = listOf(
    Command("/clear", R.string.cmd_clear, Icons.Default.DeleteSweep),
    Command("/new", R.string.cmd_new, Icons.Default.Add),
    Command("/model", R.string.cmd_model, Icons.Default.SwapHoriz),
    Command("/theme", R.string.cmd_theme, Icons.Default.Palette),
    Command("/export", R.string.cmd_export, Icons.Default.Download),
    Command("/search", R.string.cmd_search, Icons.Default.Search),
    Command("/reconnect", R.string.cmd_reconnect, Icons.Default.Refresh),
    Command("/help", R.string.cmd_help, Icons.Default.Help),
    Command("/compare", R.string.cmd_compare, Icons.Default.CompareArrows)
)

/**
 * Floating command palette that appears when user types `/` in the chat input.
 * Shows matching commands, supports click selection.
 */
@Composable
fun CommandPalette(
    query: String,
    onCommandSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredCommands = remember(query) {
        if (query.isEmpty()) {
            availableCommands
        } else {
            availableCommands.filter {
                it.name.startsWith(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true)
            }
        }
    }

    AnimatedVisibility(
        visible = filteredCommands.isNotEmpty(),
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
    ) {
        val paletteShape = ShapeM16
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = paletteShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 8.dp,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.cmd_palette_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                filteredCommands.forEach { command ->
                    CommandItem(
                        command = command,
                        onClick = {
                            onCommandSelected(command.name)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandItem(
    command: Command,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = command.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = command.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(command.descriptionResId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
