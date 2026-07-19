package com.agenthub.app.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agenthub.app.data.sync.DeviceSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel that owns the [DeviceSyncManager] instance.
 *
 * Previously the screen created the manager with `remember { DeviceSyncManager() }`,
 * which discarded all pairing / sync state the moment the user left the screen.
 * Hoisting the manager into a Hilt-scoped ViewModel keeps the state alive for the
 * lifetime of the ViewModel (tied to the navigation back stack entry) instead.
 *
 * The persistent state (paired devices, sync stats) is exposed as StateFlows from
 * the manager. Transient UI state (discovered devices list, discovery progress) is
 * kept in the screen via `rememberCoroutineScope` + local `mutableStateOf`, which is
 * the appropriate layering for ephemeral dialog state.
 */
@HiltViewModel
class DeviceSyncViewModel @Inject constructor() : ViewModel() {

    private val manager = DeviceSyncManager()

    val pairedDevices: StateFlow<List<DeviceSyncManager.PairedDevice>> = manager.pairedDevices
    val syncState: StateFlow<DeviceSyncManager.SyncState> = manager.syncState

    fun toggleSync(enabled: Boolean) = manager.toggleSync(enabled)

    fun removeDevice(deviceId: String) = manager.removeDevice(deviceId)

    fun syncAll() {
        viewModelScope.launch { manager.syncAll() }
    }

    suspend fun discoverDevices(): List<DeviceSyncManager.DiscoveredDevice> =
        manager.discoverDevices()

    suspend fun pairDevice(deviceId: String, deviceName: String): Boolean =
        manager.pairDevice(deviceId, deviceName)
}
