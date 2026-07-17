package com.agenthub.app.navigation

import com.agenthub.app.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.agenthub.app.ui.theme.GlassNavigationBar
import com.agenthub.app.ui.theme.GlassNavigationRail
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agenthub.app.ui.activity.ActivityScreen
import com.agenthub.app.ui.adaptive.currentAdaptiveConfig
import com.agenthub.app.ui.adaptive.shouldShowRail
import com.agenthub.app.ui.agents.AgentsScreen
import com.agenthub.app.ui.chat.ChatScreen
import com.agenthub.app.ui.sessions.SessionsScreen
import com.agenthub.app.ui.settings.SettingsScreen
import com.agenthub.app.ui.marketplace.AgentMarketScreen
import com.agenthub.app.ui.sync.DeviceSyncScreen
import com.agenthub.app.ui.plugin.PluginScreen
import com.agenthub.app.ui.insights.InsightsScreen
import com.agenthub.app.data.model.MarketplaceAgent
import com.agenthub.app.data.model.AgentConfig
import com.agenthub.app.data.model.AgentType
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val adaptive = currentAdaptiveConfig()
    val chatViewModel: com.agenthub.app.ui.chat.ChatViewModel = hiltViewModel()

    if (adaptive.shouldShowRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            GlassNavigationRail(
                header = { Spacer(modifier = Modifier.height(24.dp)) }
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Screen.tabs.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == screen.route
                    } == true
                    NavigationRailItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(imageVector = screen.icon, contentDescription = stringResource(screen.stringResId)) },
                        label = { Text(text = stringResource(screen.stringResId)) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top)
            ) { paddingValues ->
                NavHost(
                    navController = navController,
                    startDestination = Screen.Chat.route,
                    modifier = Modifier.padding(paddingValues)
                ) {
                    composable(Screen.Chat.route) { ChatScreen(chatViewModel) }
                    composable(Screen.Sessions.route) { SessionsScreen(chatViewModel) }
                    composable(Screen.Activity.route) { ActivityScreen() }
                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            onNavigateToAgents = { navController.navigate(Screen.Agents.route) },
                            onNavigateToMarketplace = { navController.navigate(Screen.Marketplace.route) },
                            onNavigateToInsights = { navController.navigate(Screen.Insights.route) },
                            onNavigateToDeviceSync = { navController.navigate("device_sync") },
                            onNavigateToPlugins = { navController.navigate("plugins") }
                        )
                    }
                    composable(Screen.Agents.route) { AgentsScreen() }
                    composable(Screen.Marketplace.route) {
                        AgentMarketScreen(
                            onInstall = { agent ->
                                val config = AgentConfig(
                                    id = agent.id,
                                    name = agent.name,
                                    type = agent.type,
                                    serverUrl = agent.serverUrl
                                )
                                chatViewModel.installMarketplaceAgent(config)
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(Screen.Insights.route) {
                        InsightsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("device_sync") {
                        DeviceSyncScreen(onBack = { navController.popBackStack() })
                    }
                    composable("plugins") {
                        PluginScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
            bottomBar = {
                GlassNavigationBar {
                    Screen.tabs.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(imageVector = screen.icon, contentDescription = stringResource(screen.stringResId)) },
                            label = { Text(text = stringResource(screen.stringResId), style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = Screen.Chat.route,
                modifier = Modifier.padding(paddingValues)
            ) {
                composable(Screen.Chat.route) { ChatScreen(chatViewModel) }
                composable(Screen.Sessions.route) { SessionsScreen(chatViewModel) }
                composable(Screen.Activity.route) { ActivityScreen() }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onNavigateToAgents = { navController.navigate(Screen.Agents.route) },
                        onNavigateToMarketplace = { navController.navigate(Screen.Marketplace.route) },
                        onNavigateToInsights = { navController.navigate(Screen.Insights.route) },
                        onNavigateToDeviceSync = { navController.navigate("device_sync") },
                        onNavigateToPlugins = { navController.navigate("plugins") }
                    )
                }
                composable(Screen.Agents.route) { AgentsScreen() }
                composable(Screen.Marketplace.route) {
                    AgentMarketScreen(
                        onInstall = { agent ->
                            val config = AgentConfig(
                                id = agent.id,
                                name = agent.name,
                                type = agent.type,
                                serverUrl = agent.serverUrl
                            )
                            chatViewModel.installMarketplaceAgent(config)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.Insights.route) {
                    InsightsScreen(onBack = { navController.popBackStack() })
                }
                composable("device_sync") {
                    DeviceSyncScreen(onBack = { navController.popBackStack() })
                }
                composable("plugins") {
                    PluginScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
