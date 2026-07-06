package com.agenthub.app.data.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Plugin(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val isEnabled: Boolean,
    val permissions: List<String>,
    val version: String = "1.0.0"
)

class PluginManager {

    private val _plugins = MutableStateFlow(builtInPlugins())
    val plugins: StateFlow<List<Plugin>> = _plugins

    fun enablePlugin(pluginId: String) {
        _plugins.value = _plugins.value.map { plugin ->
            if (plugin.id == pluginId) plugin.copy(isEnabled = true) else plugin
        }
    }

    fun disablePlugin(pluginId: String) {
        _plugins.value = _plugins.value.map { plugin ->
            if (plugin.id == pluginId) plugin.copy(isEnabled = false) else plugin
        }
    }

    fun togglePlugin(pluginId: String) {
        _plugins.value = _plugins.value.map { plugin ->
            if (plugin.id == pluginId) plugin.copy(isEnabled = !plugin.isEnabled) else plugin
        }
    }

    fun getPlugin(pluginId: String): Plugin? {
        return _plugins.value.find { it.id == pluginId }
    }

    fun getEnabledPlugins(): List<Plugin> {
        return _plugins.value.filter { it.isEnabled }
    }

    private fun builtInPlugins(): List<Plugin> {
        return listOf(
            Plugin(
                id = "weather",
                name = "Weather",
                description = "Get weather information for any location worldwide",
                icon = "\uD83C\uDF24\uFE0F",
                isEnabled = true,
                permissions = listOf("network"),
                version = "1.2.0"
            ),
            Plugin(
                id = "search",
                name = "Web Search",
                description = "Search the internet and get real-time results",
                icon = "\uD83D\uDD0D",
                isEnabled = true,
                permissions = listOf("network"),
                version = "2.0.1"
            ),
            Plugin(
                id = "calculator",
                name = "Calculator",
                description = "Perform mathematical calculations and unit conversions",
                icon = "\uD83D\uDD22",
                isEnabled = true,
                permissions = emptyList(),
                version = "1.0.0"
            ),
            Plugin(
                id = "translator",
                name = "Translator",
                description = "Real-time translation between 100+ languages",
                icon = "\uD83C\uDF10",
                isEnabled = true,
                permissions = listOf("network"),
                version = "1.5.0"
            ),
            Plugin(
                id = "code_runner",
                name = "Code Runner",
                description = "Execute code snippets in Python, JavaScript, and more",
                icon = "\uD83D\uDCBB",
                isEnabled = false,
                permissions = listOf("network"),
                version = "0.9.0"
            ),
            Plugin(
                id = "image_gen",
                name = "Image Generator",
                description = "Generate images from text descriptions using AI",
                icon = "\uD83C\uDFA8",
                isEnabled = false,
                permissions = listOf("network", "storage"),
                version = "0.8.0"
            ),
            Plugin(
                id = "calendar",
                name = "Calendar",
                description = "Manage events, set reminders, and check your schedule",
                icon = "\uD83D\uDCC5",
                isEnabled = false,
                permissions = emptyList(),
                version = "1.1.0"
            ),
            Plugin(
                id = "notes",
                name = "Quick Notes",
                description = "Create and manage quick notes and to-do lists",
                icon = "\uD83D\uDCDD",
                isEnabled = true,
                permissions = emptyList(),
                version = "1.3.0"
            )
        )
    }
}
