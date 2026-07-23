package com.agentcontrolcenter.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agentcontrolcenter.app.core.database.entity.MarketplaceFavoriteEntity
import kotlinx.coroutines.flow.Flow

/**
 * v4.9.0: Marketplace 收藏 DAO。
 *
 * 提供 upsert / delete / 查询全部（Flow）/ 判断是否已收藏 等操作。
 * UI 通过 [getAllFlow] 订阅，实时响应收藏变化。
 */
@Dao
interface MarketplaceFavoriteDao {

    /** 新增或更新收藏（同一 agentId 重复收藏覆盖旧记录，刷新元数据）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: MarketplaceFavoriteEntity)

    /** 删除指定 agentId 的收藏。 */
    @Query("DELETE FROM marketplace_favorites WHERE agentId = :agentId")
    suspend fun delete(agentId: String)

    /** 查询所有收藏，按 [addedAt] 倒序，作为 Flow 订阅实时刷新。 */
    @Query("SELECT * FROM marketplace_favorites ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<MarketplaceFavoriteEntity>>

    /** 一次性查询所有收藏（非 Flow，用于一次性操作如批量导出）。 */
    @Query("SELECT * FROM marketplace_favorites ORDER BY addedAt DESC")
    suspend fun getAllOnce(): List<MarketplaceFavoriteEntity>

    /** 判断某 agentId 是否已收藏（供 UI 卡片展示书签状态）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM marketplace_favorites WHERE agentId = :agentId)")
    suspend fun isFavorite(agentId: String): Boolean

    /** 查询所有已收藏的 agentId（供 UI 批量判断书签状态）。 */
    @Query("SELECT agentId FROM marketplace_favorites")
    fun getFavoriteIdsFlow(): Flow<List<String>>
}
