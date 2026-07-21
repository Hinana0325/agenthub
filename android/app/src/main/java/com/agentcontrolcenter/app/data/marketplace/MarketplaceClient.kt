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
     */
    suspend fun fetchAll(search: String? = null): Result<List<MarketplaceAgent>> =
        withContext(Dispatchers.IO) {
            try {
                val openClawResult = fetchOpenClawAgents(page = 1, search = search)
                val clawHubResult = fetchClawHubSkills(limit = 50, search = search)

                val combined = mutableListOf<MarketplaceAgent>()
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
