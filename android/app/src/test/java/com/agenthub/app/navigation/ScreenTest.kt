package com.agenthub.app.navigation

import org.junit.Assert.*
import org.junit.Test

class ScreenTest {
    @Test
    fun `all screens have unique routes`() {
        val screens = listOf(
            Screen.Chat, Screen.Sessions, Screen.Activity, Screen.Settings,
            Screen.Agents, Screen.Marketplace, Screen.Insights, Screen.Workflow, Screen.Compare
        )
        val routes = screens.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `tabs contain exactly 4 screens`() {
        assertEquals(4, Screen.tabs.size)
        assertTrue(Screen.tabs.contains(Screen.Chat))
        assertTrue(Screen.tabs.contains(Screen.Sessions))
        assertTrue(Screen.tabs.contains(Screen.Activity))
        assertTrue(Screen.tabs.contains(Screen.Settings))
    }

    @Test
    fun `all screens have string resource ids`() {
        val screens = listOf(
            Screen.Chat, Screen.Sessions, Screen.Activity, Screen.Settings,
            Screen.Agents, Screen.Marketplace, Screen.Insights, Screen.Workflow, Screen.Compare
        )
        screens.forEach { screen ->
            assertTrue("Screen ${screen.route} should have stringResId > 0", screen.stringResId > 0)
        }
    }
}
