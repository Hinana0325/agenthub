package com.agentcontrolcenter.app

import androidx.compose.runtime.*
import com.agentcontrolcenter.app.navigation.AppNavigation
import com.agentcontrolcenter.app.ui.theme.Agent Control CenterTheme

@Composable
fun Agent Control CenterApp() {
    Agent Control CenterTheme {
        AppNavigation()
    }
}
