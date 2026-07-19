package com.agenthub.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CompareArrows
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
    data object Compare : Screen("compare", R.string.compare_title, Icons.Default.CompareArrows)
    data object DeviceSync : Screen("device_sync", R.string.device_sync_title, Icons.Default.Sync)
    data object Plugins : Screen("plugins", R.string.plugin_title, Icons.Default.Extension)

    companion object {
        /** Returns the list of primary tab screens shown in the bottom bar / navigation rail. */
        fun getTabs(): List<Screen> = listOf(Chat, Sessions, Activity, Settings)
    }
}
