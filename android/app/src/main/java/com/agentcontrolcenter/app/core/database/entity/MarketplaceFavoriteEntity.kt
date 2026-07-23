package com.agentcontrolcenter.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v4.9.0: Marketplace 收藏实体。
 *
 * 对应 [com.agentcontrolcenter.app.data.model.MarketplaceAgent] 的快照：
 * 冗余 name/type/serverUrl 等字段，便于从收藏列表直接安装，
 * 无需再次请求 Marketplace API。
 *
 * 索引设计：
 * - [addedAt]：按时间倒序展示收藏列表
 *
 * @see com.agentcontrolcenter.app.core.database.dao.MarketplaceFavoriteDao
 */
@Entity(
    tableName = "marketplace_favorites",
    indices = [Index("addedAt")]
)
data class MarketplaceFavoriteEntity(
    @PrimaryKey val agentId: String,
    val name: String,
    val description: String = "",
    val type: String,
    val serverUrl: String,
    val author: String,
    val tagsJson: String = "[]",
    val addedAt: Long
)
