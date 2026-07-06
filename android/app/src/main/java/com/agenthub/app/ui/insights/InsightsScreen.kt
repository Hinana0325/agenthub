package com.agenthub.app.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agenthub.app.R
import com.agenthub.app.data.insights.DataInsightsManager
import com.agenthub.app.ui.theme.GlassCard
import com.agenthub.app.ui.theme.GlassTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    onBack: () -> Unit = {},
    viewModel: InsightsViewModel = viewModel()
) {
    val insights by viewModel.insights.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text(stringResource(R.string.insights_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.exportReport(context) }) {
                        Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.insights_export))
                    }
                    IconButton(onClick = { viewModel.loadInsights() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_search))
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Overview Stats Cards ──
                item {
                    Text(
                        text = stringResource(R.string.insights_overview),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.insights_total_messages),
                            value = "${insights.totalMessages}",
                            icon = Icons.Default.Message,
                            color = MaterialTheme.colorScheme.primary
                        )
                        StatCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.insights_total_sessions),
                            value = "${insights.totalSessions}",
                            icon = Icons.Default.History,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.insights_avg_response),
                            value = formatDuration(insights.avgResponseTime),
                            icon = Icons.Default.Timer,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        StatCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.insights_streak),
                            value = "${insights.longestStreak} ${stringResource(R.string.insights_days)}",
                            icon = Icons.Default.LocalFireDepartment,
                            color = Color(0xFFFF6B35)
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.insights_avg_length),
                            value = "${insights.avgMessageLength} ${stringResource(R.string.insights_chars)}",
                            icon = Icons.Default.TextFields,
                            color = Color(0xFF10B981)
                        )
                        StatCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.insights_active_hour),
                            value = "${insights.mostActiveHour}:00",
                            icon = Icons.Default.Schedule,
                            color = Color(0xFFF59E0B)
                        )
                    }
                }

                // ── Messages by Day Chart ──
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.insights_message_trend),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (insights.messagesByDay.isNotEmpty()) {
                            BarChart(
                                data = insights.messagesByDay,
                                modifier = Modifier.fillMaxWidth().height(200.dp).padding(16.dp),
                                barColor = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.insights_no_data),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                // ── Hourly Heatmap ──
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.insights_hourly_activity),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        HourlyHeatmap(
                            data = insights.messagesByHour,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        )
                    }
                }

                // ── Agent Usage Pie Chart ──
                if (insights.messagesByAgent.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.insights_agent_distribution),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            SimplePieChart(
                                data = insights.messagesByAgent,
                                modifier = Modifier.fillMaxWidth().height(220.dp).padding(16.dp)
                            )
                        }
                    }
                }

                // ── Bottom spacer ──
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

// ── Composable Components ──

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun BarChart(
    data: Map<String, Long>,
    modifier: Modifier = Modifier,
    barColor: Color
) {
    val entries = data.entries.toList()
    val maxValue = (entries.maxOfOrNull { it.value } ?: 1L).toFloat()

    Canvas(modifier = modifier) {
        val barWidth = size.width / (entries.size * 1.5f)
        val chartHeight = size.height - 30f

        entries.forEachIndexed { index, entry ->
            val barHeight = (entry.value / maxValue) * chartHeight
            val x = index * (barWidth * 1.5f) + barWidth * 0.25f
            val y = chartHeight - barHeight

            // Bar
            drawRoundRect(
                color = barColor.copy(alpha = 0.8f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )

            // Label
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    textSize = 22f
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText(
                    entry.key,
                    x + barWidth / 2,
                    size.height - 5f,
                    paint
                )
            }
        }
    }
}

@Composable
private fun HourlyHeatmap(
    data: Map<Int, Long>,
    modifier: Modifier = Modifier
) {
    val maxValue = (data.values.maxOrNull() ?: 1L).toFloat()
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier) {
        // 4 rows x 6 columns
        for (row in 0 until 4) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (col in 0 until 6) {
                    val hour = row * 6 + col
                    val count = data[hour] ?: 0L
                    val intensity = if (maxValue > 0) count / maxValue else 0f

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.8f)
                            .padding(2.dp)
                            .background(
                                color = primaryColor.copy(alpha = 0.1f + intensity * 0.8f),
                                shape = RoundedCornerShape(4.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "%02d".format(hour),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (intensity > 0.5f) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.insights_less),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 1..5) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = primaryColor.copy(alpha = 0.1f + (i / 5f) * 0.8f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
            Text(
                text = stringResource(R.string.insights_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun SimplePieChart(
    data: Map<String, Long>,
    modifier: Modifier = Modifier
) {
    val total = data.values.sum().toFloat()
    if (total == 0f) return

    val colors = listOf(
        Color(0xFF3B82F6),
        Color(0xFF10B981),
        Color(0xFFF59E0B),
        Color(0xFFEF4444),
        Color(0xFF8B5CF6),
        Color(0xFFEC4899),
        Color(0xFF06B6D4),
        Color(0xFF84CC16)
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(
            modifier = Modifier.size(140.dp)
        ) {
            var startAngle = -90f
            data.entries.forEachIndexed { index, entry ->
                val sweepAngle = (entry.value / total) * 360f
                drawArc(
                    color = colors[index % colors.size],
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height)
                )
                startAngle += sweepAngle
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            data.entries.forEachIndexed { index, entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(colors[index % colors.size], RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${entry.key} (${entry.value})",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ── Utility ──

private fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }
}
