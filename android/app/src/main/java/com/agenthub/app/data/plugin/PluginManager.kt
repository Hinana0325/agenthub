package com.agenthub.app.data.plugin

import com.agenthub.app.data.local.dao.PluginDao
import com.agenthub.app.data.local.entity.PluginEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 插件管理器（DB 驱动）。
 *  - 首次启动播种内置插件（含真实可执行的 [PluginAction]）。
 *  - toggle / 自定义插件的启用状态持久化到 Room。
 *  - 通过 [plugins] StateFlow 向 UI 暴露最新列表。
 *
 * 注：传入的 [pluginDao] 不可为空；如需无 DB 的纯内存实例（测试/降级），
 * 可使用伴生对象 [PluginManager.inMemory] 构造一个内置只读实例。
 */
class PluginManager(private val pluginDao: PluginDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _plugins = MutableStateFlow<List<Plugin>>(emptyList())
    val plugins: StateFlow<List<Plugin>> = _plugins.asStateFlow()

    init {
        scope.launch {
            if (pluginDao.getCount() == 0) {
                builtInPlugins().forEach { pluginDao.insert(it.toEntity()) }
            }
            refresh()
        }
    }

    fun refresh() {
        scope.launch {
            _plugins.value = pluginDao.getAllOnce().map { it.toPlugin() }
        }
    }

    fun togglePlugin(pluginId: String) {
        scope.launch {
            val current = pluginDao.getAllOnce().find { it.id == pluginId } ?: return@launch
            pluginDao.setEnabled(pluginId, !current.isEnabled)
            refresh()
        }
    }

    fun getPlugin(pluginId: String): Plugin? = _plugins.value.find { it.id == pluginId }

    fun getEnabledPlugins(): List<Plugin> = _plugins.value.filter { it.isEnabled }

    // ── 内置插件（含真实动作） ──

    private fun builtInPlugins(): List<Plugin> = listOf(
        Plugin(
            id = "weather",
            name = "Weather",
            description = "Get weather information for any location worldwide",
            icon = "\uD83C\uDF24\uFE0F",
            isEnabled = true,
            permissions = listOf("network"),
            version = "1.2.0",
            action = PluginAction.HttpCall(
                url = "https://wttr.in/{query}?format=j1",
                method = "GET"
            )
        ),
        Plugin(
            id = "search",
            name = "Web Search",
            description = "Search the internet and get real-time results",
            icon = "\uD83D\uDD0D",
            isEnabled = true,
            permissions = listOf("network"),
            version = "2.0.1",
            action = PluginAction.HttpCall(
                url = "https://api.duckduckgo.com/?q={query}&format=json",
                method = "GET"
            )
        ),
        Plugin(
            id = "calculator",
            name = "Calculator",
            description = "Evaluate math expressions via mathjs",
            icon = "\uD83D\uDD22",
            isEnabled = true,
            permissions = listOf("network"),
            version = "1.0.0",
            action = PluginAction.HttpCall(
                url = "https://api.mathjs.org/v4/?expr={query}",
                method = "GET"
            )
        ),
        Plugin(
            id = "translator",
            name = "Translator",
            description = "Real-time translation between 100+ languages",
            icon = "\uD83C\uDF10",
            isEnabled = true,
            permissions = listOf("network"),
            version = "1.5.0",
            action = PluginAction.Workflow("Translate the following text to English: {query}")
        ),
        Plugin(
            id = "code_runner",
            name = "Code Runner",
            description = "Ask the connected agent to run a code snippet",
            icon = "\uD83D\uDCBB",
            isEnabled = false,
            permissions = listOf("network"),
            version = "0.9.0",
            action = PluginAction.Workflow("Run the following code and return its output:\n{query}")
        ),
        Plugin(
            id = "image_gen",
            name = "Image Generator",
            description = "Generate images from text via the connected agent",
            icon = "\uD83C\uDFA8",
            isEnabled = false,
            permissions = listOf("network", "storage"),
            version = "0.8.0",
            action = PluginAction.Workflow("Generate an image of: {query}")
        ),
        Plugin(
            id = "calendar",
            name = "Calendar",
            description = "Broadcast a calendar intent (handled by a calendar app)",
            icon = "\uD83D\uDCC5",
            isEnabled = false,
            permissions = emptyList(),
            version = "1.1.0",
            action = PluginAction.Broadcast(
                action = "com.agenthub.app.ACTION_CALENDAR",
                extras = mapOf("title" to "{query}")
            )
        ),
        Plugin(
            id = "notes",
            name = "Quick Notes",
            description = "Send a quick note via system broadcast",
            icon = "\uD83D\uDCDD",
            isEnabled = true,
            permissions = emptyList(),
            version = "1.3.0",
            action = PluginAction.Broadcast(
                action = "com.agenthub.app.ACTION_QUICK_NOTE",
                extras = mapOf("text" to "{query}")
            )
        )
    )

    companion object {
        private val gson = Gson()

        /** 无 DB 的纯内存只读实例（降级/测试用）。 */
        fun inMemory(): PluginManager = object : PluginManager(
            object : PluginDao {
                private val mem = mutableListOf<PluginEntity>()
                override fun getAll(): Flow<List<PluginEntity>> =
                    kotlinx.coroutines.flow.flowOf(mem)
                override suspend fun getAllOnce(): List<PluginEntity> = mem
                override suspend fun getCount(): Int = mem.size
                override suspend fun insert(plugin: PluginEntity) { mem.add(plugin) }
                override suspend fun setEnabled(id: String, enabled: Boolean) {
                    mem.replaceAll { if (it.id == id) it.copy(isEnabled = enabled) else it }
                }
                override suspend fun delete(id: String) { mem.removeIf { it.id == id } }
            }
        ) {}
    }
}

// ── Plugin <-> PluginEntity 映射 ──

private val listType = object : TypeToken<List<String>>() {}.type

fun Plugin.toEntity(): PluginEntity = PluginEntity(
    id = id,
    name = name,
    description = description,
    icon = icon,
    isEnabled = isEnabled,
    permissionsJson = gson.toJson(permissions),
    version = version,
    actionType = action?.type ?: "none",
    actionConfig = action?.let { PluginAction.toConfig(it) } ?: ""
)

fun PluginEntity.toPlugin(): Plugin = Plugin(
    id = id,
    name = name,
    description = description,
    icon = icon,
    isEnabled = isEnabled,
    permissions = runCatching { gson.fromJson(permissionsJson, listType) }.getOrElse { emptyList() },
    version = version,
    action = if (actionType == "none") null else PluginAction.fromConfig(actionType, actionConfig)
)
