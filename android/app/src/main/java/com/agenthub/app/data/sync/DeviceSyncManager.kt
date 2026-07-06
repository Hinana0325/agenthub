package com.agenthub.app.data.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

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
     */
    suspend fun discoverDevices(): List<DiscoveredDevice> {
        // Simulate network discovery delay
        delay(1500)
        val devices = listOf(
            DiscoveredDevice(UUID.randomUUID().toString(), "My Tablet", 85),
            DiscoveredDevice(UUID.randomUUID().toString(), "Office PC", 62),
            DiscoveredDevice(UUID.randomUUID().toString(), "Living Room TV", 40)
        )
        return devices
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
        val count = (5..20).random()
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
        val count = (1..5).random()
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
