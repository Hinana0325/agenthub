package com.agenthub.app.ui.marketplace

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agenthub.app.R
import com.agenthub.app.data.model.AgentType
import com.agenthub.app.data.model.MarketplaceAgent
import com.agenthub.app.ui.adaptive.currentAdaptiveConfig
import com.agenthub.app.ui.theme.GlassCard
import com.agenthub.app.ui.theme.GlassTopAppBar
import org.json.JSONArray
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMarketScreen(
    onInstall: (MarketplaceAgent) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val adaptive = currentAdaptiveConfig()

    var agents by remember { mutableStateOf<List<MarketplaceAgent>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var installedIds by remember { mutableStateOf(setOf<String>()) }

    // Load agents from raw resource
    LaunchedEffect(Unit) {
        agents = loadMarketplaceAgents(context)
    }

    val allTags = remember(agents) {
        agents.flatMap { it.tags }.distinct().sorted()
    }

    val filteredAgents = remember(agents, searchQuery, selectedTag) {
        agents.filter { agent ->
            val matchesSearch = searchQuery.isBlank() ||
                agent.name.contains(searchQuery, ignoreCase = true) ||
                agent.description.contains(searchQuery, ignoreCase = true) ||
                agent.author.contains(searchQuery, ignoreCase = true)
            val matchesTag = selectedTag == null || agent.tags.contains(selectedTag)
            matchesSearch && matchesTag
        }
    }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text(stringResource(R.string.marketplace_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.marketplace_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Tag filter chips
            if (allTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Show limited tags in a scrollable-like manner
                    FilterChip(
                        selected = selectedTag == null,
                        onClick = { selectedTag = null },
                        label = { Text(stringResource(R.string.marketplace_all)) }
                    )
                    allTags.take(4).forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { selectedTag = if (selectedTag == tag) null else tag },
                            label = { Text(tag) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Results count
            Text(
                text = stringResource(R.string.marketplace_results, filteredAgents.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Agent list
            if (filteredAgents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Storefront,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.marketplace_no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredAgents, key = { it.id }) { agent ->
                        MarketplaceAgentCard(
                            agent = agent,
                            isInstalled = installedIds.contains(agent.id),
                            onInstall = {
                                installedIds = installedIds + agent.id
                                onInstall(agent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketplaceAgentCard(
    agent: MarketplaceAgent,
    isInstalled: Boolean,
    onInstall: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
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
                // Agent type icon
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${agent.type.displayName} · ${agent.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Install button
                if (isInstalled) {
                    FilledTonalButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.marketplace_installed), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.marketplace_install), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            Text(
                text = agent.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Stats row: rating + downloads + tags
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Rating
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = String.format("%.1f", agent.rating),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                // Downloads
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = NumberFormat.getIntegerInstance().format(agent.downloads),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // Tags
                agent.tags.take(3).forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun loadMarketplaceAgents(context: android.content.Context): List<MarketplaceAgent> {
    return try {
        val inputStream = context.resources.openRawResource(R.raw.marketplace_agents)
        val json = inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val tagsArray = obj.getJSONArray("tags")
            val tags = (0 until tagsArray.length()).map { tagsArray.getString(it) }
            MarketplaceAgent(
                id = obj.getString("id"),
                name = obj.getString("name"),
                description = obj.getString("description"),
                type = try { AgentType.valueOf(obj.getString("type")) } catch (_: Exception) { AgentType.Hermes },
                serverUrl = obj.getString("serverUrl"),
                author = obj.getString("author"),
                downloads = obj.getInt("downloads"),
                rating = obj.getDouble("rating").toFloat(),
                tags = tags
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
