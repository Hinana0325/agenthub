package com.agentcontrolcenter.app.navigation

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
    fun `tabs contain exactly 5 screens`() {
        // P0 IA 重组：主 Tab 由 4 个（Chat/Sessions/Activity/Settings）
        // 改为 5 个（Chat/Sessions/Agents/Tasks/More），Activity 与 Settings
        // 下沉为 More 的次级入口，Agents 与 Tasks 上提为主 Tab。
        val expectedRoutes = setOf("chat", "sessions", "agents", "tasks", "more")
        val actualRoutes = Screen.getTabs().map { it.route }.toSet()
        assertEquals("tabs should have 5 routes", 5, actualRoutes.size)
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
