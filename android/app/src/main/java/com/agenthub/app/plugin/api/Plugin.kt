package com.agenthub.app.plugin.api

data class Plugin(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val isEnabled: Boolean,
    val permissions: List<String>,
    val version: String = "1.0.0",
    /** 可执行动作；为 null 表示「仅展示、无执行逻辑」。 */
    val action: PluginAction? = null
)
