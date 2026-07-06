package com.agenthub.app

import androidx.compose.runtime.*
import com.agenthub.app.navigation.AppNavigation
import com.agenthub.app.ui.theme.AgentHubTheme

@Composable
fun AgentHubApp() {
    AgentHubTheme {
        AppNavigation()
    }
}
