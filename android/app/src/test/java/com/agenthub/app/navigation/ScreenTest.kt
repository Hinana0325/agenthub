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
        val expectedRoutes = setOf("chat", "sessions", "activity", "settings")
        val actualRoutes = Screen.getTabs().map { it.route }.toSet()
        assertEquals("tabs should have 4 routes", 4, actualRoutes.size)
        assertEquals("tabs routes should match expected", expectedRoutes, actualRoutes)
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
