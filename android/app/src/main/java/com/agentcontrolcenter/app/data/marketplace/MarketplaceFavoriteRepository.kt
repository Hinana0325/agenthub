package com.agentcontrolcenter.app.data.marketplace

import com.agentcontrolcenter.app.core.database.dao.MarketplaceFavoriteDao
import com.agentcontrolcenter.app.core.database.entity.MarketplaceFavoriteEntity
import com.agentcontrolcenter.app.data.model.MarketplaceAgent
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v4.9.0: Marketplace 收藏仓库。
 *
 * 在 [MarketplaceAgent] 数据模型与 [MarketplaceFavoriteEntity] 持久化层之间做转换，
 * 供 UI 层订阅已收藏 agentId 列表并执行 toggle。
 *
 * 设计说明：标签以 JSON 字符串持久化（[MarketplaceFavoriteEntity.tagsJson]），
 * 读取时反序列化回 List<String>，避免引入额外的 TypeConverter。
 */
@Singleton
class MarketplaceFavoriteRepository @Inject constructor(
    private val dao: MarketplaceFavoriteDao
) {
    private val gson = Gson()

    /** 订阅所有已收藏的 agentId（实时刷新）。 */
    fun favoriteIdsFlow(): Flow<Set<String>> =
        dao.getFavoriteIdsFlow().map { it.toSet() }

    /** 订阅所有收藏记录（用于收藏筛选视图展示完整卡片信息）。 */
    fun favoritesFlow(): Flow<List<MarketplaceFavoriteEntity>> = dao.getAllFlow()

    /** 切换收藏状态：未收藏则添加，已收藏则移除。 */
    suspend fun toggle(agent: MarketplaceAgent): Boolean {
        val existing = dao.isFavorite(agent.id)
        if (existing) {
            dao.delete(agent.id)
            return false
        }
        dao.upsert(
            MarketplaceFavoriteEntity(
                agentId = agent.id,
                name = agent.name,
                description = agent.description,
                type = agent.type.name,
                serverUrl = agent.serverUrl,
                author = agent.author,
                tagsJson = gson.toJson(agent.tags),
                addedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    /** 从收藏记录还原为 [MarketplaceAgent]（供从收藏直接安装）。 */
    fun toMarketplaceAgent(entity: MarketplaceFavoriteEntity): MarketplaceAgent {
        val type = runCatching {
            com.agentcontrolcenter.app.agent.model.AgentType.valueOf(entity.type)
        }.getOrDefault(com.agentcontrolcenter.app.agent.model.AgentType.Hermes)
        val tags: List<String> = runCatching {
            gson.fromJson(entity.tagsJson, Array<String>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        return MarketplaceAgent(
            id = entity.agentId,
            name = entity.name,
            description = entity.description,
            type = type,
            serverUrl = entity.serverUrl,
            author = entity.author,
            tags = tags
        )
    }
}
