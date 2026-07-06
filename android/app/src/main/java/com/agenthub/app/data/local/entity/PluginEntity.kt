package com.agenthub.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 插件持久化实体。内置插件在首次启动时播种，用户自定义插件也可写入。
 * 动作以 `actionType`(http/broadcast/workflow) + `actionConfig`(JSON) 两列存储。
 */
@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val isEnabled: Boolean = true,
    val permissionsJson: String = "[]",
    val version: String = "1.0.0",
    val actionType: String = "none",
    val actionConfig: String = ""
)
