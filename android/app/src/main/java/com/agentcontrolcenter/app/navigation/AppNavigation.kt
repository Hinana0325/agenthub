package com.agentcontrolcenter.app.navigation

import com.agentcontrolcenter.app.R
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
import com.agentcontrolcenter.app.ui.theme.GlassNavigationBar
import com.agentcontrolcenter.app.ui.theme.GlassNavigationRail
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agentcontrolcenter.app.feature.activity.ActivityScreen
import com.agentcontrolcenter.app.ui.adaptive.currentAdaptiveConfig
import com.agentcontrolcenter.app.ui.adaptive.shouldShowRail
import com.agentcontrolcenter.app.feature.agents.AgentsScreen
import com.agentcontrolcenter.app.feature.chat.ChatScreen
import com.agentcontrolcenter.app.feature.sessions.SessionsScreen
import com.agentcontrolcenter.app.feature.settings.SettingsScreen
import com.agentcontrolcenter.app.feature.compare.CompareScreen
import com.agentcontrolcenter.app.feature.compare.CompareViewModel
import com.agentcontrolcenter.app.feature.marketplace.AgentMarketScreen
import com.agentcontrolcenter.app.feature.sync.DeviceSyncScreen
import com.agentcontrolcenter.app.feature.plugin.PluginScreen
import com.agentcontrolcenter.app.feature.insights.InsightsScreen
import com.agentcontrolcenter.app.feature.task.TasksScreen
import com.agentcontrolcenter.app.feature.mcp.McpScreen
import com.agentcontrolcenter.app.data.model.MarketplaceAgent
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentType
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val adaptive = currentAdaptiveConfig()
    val chatViewModel: com.agentcontrolcenter.app.feature.chat.ChatViewModel = hiltViewModel()

    // P3-5: 观察 Launcher 快捷方式请求，导航到对应目的地后消费
    val pendingShortcutAction by ShortcutRouter.pendingAction.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShortcutAction) {
        val action = pendingShortcutAction ?: return@LaunchedEffect
        val targetRoute = when (action) {
            ShortcutRouter.Action.NEW_CHAT -> Screen.Chat.route
            ShortcutRouter.Action.NEW_AGENT -> Screen.Agents.route
            ShortcutRouter.Action.SETTINGS -> Screen.Settings.route
        }
        navController.navigate(targetRoute) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        ShortcutRouter.consume()
    }

    if (adaptive.shouldShowRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            GlassNavigationRail(
                header = { Spacer(modifier = Modifier.height(24.dp)) }
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Screen.getTabs().forEach { screen ->
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
                AppNavHost(
                    navController = navController,
                    chatViewModel = chatViewModel,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
            bottomBar = {
                GlassNavigationBar {
                    Screen.getTabs().forEach { screen ->
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
            AppNavHost(
                navController = navController,
                chatViewModel = chatViewModel,
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

/**
 * Shared NavHost used by both the tablet (NavigationRail) and phone (BottomBar) layouts.
 * Extracted to avoid duplicating the entire destination graph between the two branches.
 *
 * 使用 Android 16 Material 3 Expressive Spring Motion 转场动画：
 * - 进入：spring(dampingRatio=0.8, stiffness=MediumLow) — 自然弹入
 * - 退出：spring(dampingRatio=0.9, stiffness=Medium) — 平滑退出
 * - popEnter/popExit 对称配置，保证返回手势一致
 */
@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    chatViewModel: com.agentcontrolcenter.app.feature.chat.ChatViewModel,
    modifier: Modifier = Modifier
) {
    // M3 Expressive Spring Motion specs
    val springEnter = androidx.compose.animation.EnterTransition(
        androidx.compose.animation.core.FadeIn(
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
            )
        ) + androidx.compose.animation.SlideInHorizontally(
            initialOffsetX = { it / 8 },
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
            )
        )
    )
    val springExit = androidx.compose.animation.ExitTransition(
        androidx.compose.animation.core.FadeOut(
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
            )
        ) + androidx.compose.animation.SlideOutHorizontally(
            targetOffsetX = { -it / 8 },
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
            )
        )
    )

    NavHost(
        navController = navController,
        startDestination = Screen.Chat.route,
        modifier = modifier,
        enterTransition = { springEnter },
        exitTransition = { springExit },
        popEnterTransition = { springEnter },
        popExitTransition = { springExit }
    ) {
        composable(Screen.Chat.route) {
            ChatScreen(
                viewModel = chatViewModel,
                navController = navController,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Sessions.route) { SessionsScreen(chatViewModel) }
        composable(Screen.Activity.route) { ActivityScreen() }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToAgents = { navController.navigate(Screen.Agents.route) },
                onNavigateToMarketplace = { navController.navigate(Screen.Marketplace.route) },
                onNavigateToInsights = { navController.navigate(Screen.Insights.route) },
                onNavigateToDeviceSync = { navController.navigate(Screen.DeviceSync.route) },
                onNavigateToPlugins = { navController.navigate(Screen.Plugins.route) },
                onNavigateToTasks = { navController.navigate(Screen.Tasks.route) },
                onNavigateToMcp = { navController.navigate(Screen.Mcp.route) }
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
        composable(Screen.DeviceSync.route) {
            DeviceSyncScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Plugins.route) {
            PluginScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Tasks.route) {
            TasksScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Mcp.route) {
            McpScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Compare.route) {
            val compareViewModel: CompareViewModel = hiltViewModel()
            CompareScreen(viewModel = compareViewModel, onBack = { navController.popBackStack() })
        }
    }
}
