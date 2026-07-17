package com.agenthub.app.data.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设备间同步 — P2P 方案
 *
 * 使用 WebSocket 在已配对设备间同步数据
 * 当前实现：设备发现 + 消息广播
 * 未来可接入云中转服务
 */
class DeviceSyncManager {

    data class PairedDevice(
        val id: String,
        val name: String,
        val lastSeen: Long,
        val isOnline: Boolean
    )

    data class SyncState(
        val isSyncEnabled: Boolean = false,
        val isSyncing: Boolean = false,
        val lastSyncTime: Long = 0L,
        val syncedMessages: Int = 0,
        val syncedConfigs: Int = 0
    )

    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState

    private val _discoveredDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = MutableStateFlow(emptyList())

    data class DiscoveredDevice(
        val id: String,
        val name: String,
        val signalStrength: Int
    )

    /**
     * 发现附近的设备
     *
     * 真实的 P2P / Wi-Fi Direct 设备发现尚未接入，此处不再返回写死的
     * 假设备（"My Tablet" / "Office PC" / "Living Room TV"），以免误导用户。
     * 返回空列表，UI 会正确显示「未发现设备」。
     */
    suspend fun discoverDevices(): List<DiscoveredDevice> {
        // 保留短暂延迟以呈现扫描态，但不编造任何设备
        delay(800)
        return emptyList()
    }

    /**
     * 配对设备
     */
    suspend fun pairDevice(deviceId: String, deviceName: String): Boolean {
        delay(500)
        val newDevice = PairedDevice(
            id = deviceId,
            name = deviceName,
            lastSeen = System.currentTimeMillis(),
            isOnline = true
        )
        _pairedDevices.value = _pairedDevices.value + newDevice
        return true
    }

    /**
     * 移除配对设备
     */
    fun removeDevice(deviceId: String) {
        _pairedDevices.value = _pairedDevices.value.filter { it.id != deviceId }
    }

    /**
     * 同步消息到指定设备
     */
    suspend fun syncMessages(deviceId: String): Int {
        _syncState.value = _syncState.value.copy(isSyncing = true)
        delay(1000)
        // 真实同步后端未接入：不编造同步条数，返回实际已同步数量（当前为 0）
        val count = 0
        _syncState.value = _syncState.value.copy(
            isSyncing = false,
            lastSyncTime = System.currentTimeMillis(),
            syncedMessages = _syncState.value.syncedMessages + count
        )
        return count
    }

    /**
     * 同步配置到指定设备
     */
    suspend fun syncConfigs(deviceId: String): Int {
        _syncState.value = _syncState.value.copy(isSyncing = true)
        delay(800)
        // 真实同步后端未接入：不编造同步条数，返回实际已同步数量（当前为 0）
        val count = 0
        _syncState.value = _syncState.value.copy(
            isSyncing = false,
            lastSyncTime = System.currentTimeMillis(),
            syncedConfigs = _syncState.value.syncedConfigs + count
        )
        return count
    }

    /**
     * 同步所有已配对设备
     */
    suspend fun syncAll() {
        val devices = _pairedDevices.value.filter { it.isOnline }
        devices.forEach { device ->
            syncMessages(device.id)
            syncConfigs(device.id)
        }
    }

    /**
     * 切换同步开关
     */
    fun toggleSync(enabled: Boolean) {
        _syncState.value = _syncState.value.copy(isSyncEnabled = enabled)
    }
}
