package com.agenthub.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.ui.graphics.vector.ImageVector
import com.agenthub.app.R

sealed class Screen(
    val route: String,
    @StringRes val stringResId: Int,
    val icon: ImageVector
) {
    data object Chat : Screen("chat", R.string.nav_chat, Icons.Default.Chat)
    data object Sessions : Screen("sessions", R.string.nav_sessions, Icons.Default.History)
    data object Activity : Screen("activity", R.string.nav_activity, Icons.Default.Schedule)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
    data object Agents : Screen("agents", R.string.nav_agents, Icons.Default.Hub)
    data object Marketplace : Screen("marketplace", R.string.nav_marketplace, Icons.Default.Storefront)
    data object Insights : Screen("insights", R.string.insights_title, Icons.Default.BarChart)
    data object Workflow : Screen("workflow", R.string.nav_workflow, Icons.Default.AccountTree)

    companion object {
        val tabs = listOf(Chat, Sessions, Activity, Settings)
    }
}
