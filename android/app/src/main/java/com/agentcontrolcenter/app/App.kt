package com.agentcontrolcenter.app

import androidx.compose.runtime.*
import com.agentcontrolcenter.app.navigation.AppNavigation
import com.agentcontrolcenter.app.ui.theme.AgentControlCenterTheme

@Composable
fun AgentControlCenterApp() {
    AgentControlCenterTheme {
        AppNavigation()
    }
}
