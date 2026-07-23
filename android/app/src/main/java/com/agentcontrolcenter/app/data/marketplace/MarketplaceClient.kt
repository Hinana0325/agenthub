package com.agentcontrolcenter.app.data.marketplace

import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.data.model.MarketplaceAgent
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real marketplace client that fetches live agent listings from:
 *  - OpenClaw Marketplace (openclaw.supplies) — Claude-powered agents
 *  - ClawHub (clawhub.ai) — OpenClaw plugin/skill registry
 *
 * Both APIs are public and require no authentication for listing endpoints.
 */
class MarketplaceClient {

    private val gson = Gson()

    // ── OpenClaw Marketplace (openclaw.supplies) ──

    data class OpenClawResponse(
        val success: Boolean,
        val data: OpenClawData?
    )

    data class OpenClawData(
        val agents: List<OpenClawAgent>,
        val pagination: OpenClawPagination?
    )

    data class OpenClawAgent(
        val id: Int,
        val uuid: String,
        val slug: String,
        val name: String,
        val tagline: String?,
        val category: OpenClawCategory?,
        val is_featured: Boolean,
        val pricing: OpenClawPricing?
    )

    data class OpenClawCategory(
        val id: Int,
        val slug: String,
        val name: String,
        val icon: String?
    )

    data class OpenClawPricing(
        val price_input_per_1k: Double,
        val price_output_per_1k: Double,
        val unlock_fee: Double
    )

    data class OpenClawPagination(
        val current_page: Int,
        val last_page: Int,
        val per_page: Int,
        val total: Int
    )

    // ── ClawHub (clawhub.ai) ──

    data class ClawHubResponse(
        val items: List<ClawHubSkill>,
        val nextCursor: String?
    )

    data class ClawHubSkill(
        val slug: String,
        val displayName: String,
        val summary: String?,
        val topics: List<String>?,
        val stats: ClawHubStats?
    )

    data class ClawHubStats(
        val comments: Int,
        val downloads: Int,
        val installs: Int,
        val stars: Int,
        val versions: Int
    )

    /**
     * Fetches agents from OpenClaw Marketplace.
     * @param page 1-based page number
     * @param search optional search query (uses the search endpoint)
     */
    suspend fun fetchOpenClawAgents(page: Int = 1, search: String? = null): Result<List<MarketplaceAgent>> =
        withContext(Dispatchers.IO) {
            try {
                val url = if (search.isNullOrBlank()) {
                    "https://openclaw.supplies/api/v1/agents?page=$page"
                } else {
                    "https://openclaw.supplies/api/v1/agents?page=$page&q=${URLEncoder.encode(search, "UTF-8")}"
                }
                val json = httpGet(url)
                val resp = gson.fromJson(json, OpenClawResponse::class.java)
                val agents = resp.data?.agents?.map { it.toMarketplaceAgent() } ?: emptyList()
                Result.success(agents)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }

    /**
     * Fetches skills/plugins from ClawHub.
     * @param limit max items (default 50)
     * @param search optional search query
     */
    suspend fun fetchClawHubSkills(limit: Int = 50, search: String? = null): Result<List<MarketplaceAgent>> =
        withContext(Dispatchers.IO) {
            try {
                val url = if (search.isNullOrBlank()) {
                    "https://clawhub.ai/api/v1/skills?limit=$limit&sort=updated"
                } else {
                    "https://clawhub.ai/api/v1/search?q=${URLEncoder.encode(search, "UTF-8")}"
                }
                val json = httpGet(url)

                // Search endpoint returns {"items":[...]} or {"results":[...]}
                // Skills endpoint returns {"items":[...]}
                val resp = try {
                    gson.fromJson(json, ClawHubResponse::class.java)
                } catch (_: Exception) {
                    // Search results may have different shape; try extracting items manually
                    val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
                    val items = if (obj.has("items")) obj.getAsJsonArray("items")
                        else if (obj.has("results")) obj.getAsJsonArray("results")
                        else if (obj.has("data")) obj.getAsJsonArray("data")
                        else null
                    if (items != null) {
                        ClawHubResponse(items.map { gson.fromJson(it, ClawHubSkill::class.java) }, null)
                    } else {
                        ClawHubResponse(emptyList(), null)
                    }
                }
                val agents = resp.items?.map { it.toMarketplaceAgent() } ?: emptyList()
                Result.success(agents)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }

    /**
     * Fetches from both sources concurrently and merges results.
     * 始终在结果顶部插入本地端点模板（ComfyUI / OpenWebUI），方便用户一键连接本地服务。
     */
    suspend fun fetchAll(search: String? = null): Result<List<MarketplaceAgent>> =
        withContext(Dispatchers.IO) {
            try {
                val openClawResult = fetchOpenClawAgents(page = 1, search = search)
                val clawHubResult = fetchClawHubSkills(limit = 50, search = search)

                val combined = mutableListOf<MarketplaceAgent>()
                // 本地端点模板置顶，且不受搜索词过滤影响 —— 用户随时都能看到这两个本地起手模板
                combined.addAll(fetchLocalTemplates(search))
                openClawResult.getOrNull()?.let { combined.addAll(it) }
                clawHubResult.getOrNull()?.let { combined.addAll(it) }

                if (combined.isEmpty() && openClawResult.isFailure && clawHubResult.isFailure) {
                    // 两个源都失败：优先取 openClaw 的异常，其次取 clawHub 的异常，
                    // 两者都为 null（理论上不应发生）时回退到一个默认异常，避免 !! 抛 NPE。
                    val ex = openClawResult.exceptionOrNull()
                        ?: clawHubResult.exceptionOrNull()
                        ?: RuntimeException("Unknown marketplace error")
                    Result.failure(ex)
                } else {
                    Result.success(combined)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }

    /**
     * 本地端点起手模板 — ComfyUI / OpenWebUI。
     *
     * 这两个端点无需联网即可使用（指向 127.0.0.1），作为 marketplace 的内置推荐项，
     * 让用户在浏览在线 Agent 之余能快速连接本地部署的图像 / 对话服务。
     *
     * @param search 搜索词；为空时返回全部模板，非空时按名称/描述做大小写不敏感匹配。
     */
    fun fetchLocalTemplates(search: String? = null): List<MarketplaceAgent> {
        val templates = listOf(
            MarketplaceAgent(
                id = "local_comfyui",
                name = "ComfyUI (Local)",
                description = "本地 ComfyUI 文生图端点，支持原生 JSON 工作流与默认文生图工作流自动切换。",
                type = AgentType.ComfyUI,
                serverUrl = "http://127.0.0.1:8188",
                author = "Local",
                tags = listOf("Image", "Local", "ComfyUI")
            ),
            MarketplaceAgent(
                id = "local_openwebui",
                name = "OpenWebUI (Local)",
                description = "本地 OpenWebUI 对话端点，兼容 OpenAI API 格式，可接入任意本地大模型。",
                type = AgentType.OpenWebUI,
                serverUrl = "http://127.0.0.1:3000/api/v1",
                author = "Local",
                tags = listOf("Chat", "Local", "OpenWebUI")
            )
        )
        return if (search.isNullOrBlank()) {
            templates
        } else {
            templates.filter {
                it.name.contains(search, ignoreCase = true) ||
                    it.description.contains(search, ignoreCase = true) ||
                    it.tags.any { tag -> tag.contains(search, ignoreCase = true) }
            }
        }
    }

    // ── Mappers ──

    private fun OpenClawAgent.toMarketplaceAgent() = MarketplaceAgent(
        id = "oc_$uuid",
        name = name,
        description = tagline ?: "OpenClaw agent in ${category?.name ?: "General"}",
        type = AgentType.OpenClaw,
        serverUrl = "https://openclaw.supplies/marketplace/$slug",
        author = "OpenClaw",
        downloads = null, // OpenClaw API 不暴露下载量，不编造
        rating = null,    // OpenClaw API 不暴露评分，不编造
        tags = listOfNotNull(category?.name) + if (is_featured) listOf("Featured") else emptyList()
    )

    private fun ClawHubSkill.toMarketplaceAgent() = MarketplaceAgent(
        id = "ch_$slug",
        name = displayName,
        description = summary ?: "ClawHub skill",
        type = AgentType.OpenClaw,
        serverUrl = "https://clawhub.ai/skills/$slug",
        author = "ClawHub",
        downloads = stats?.downloads, // 仅当 API 真实提供时展示
        rating = null,                // ClawHub 不暴露评分，不编造
        tags = topics ?: emptyList()
    )

    // ── HTTP helper ──

    private fun httpGet(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Agent Control Center/1.1.0 Android")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (connection.responseCode in 200..299) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw Exception("HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }
}
