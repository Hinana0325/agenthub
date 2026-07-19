package com.agenthub.app.feature.sync

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agenthub.app.R
import com.agenthub.app.ui.theme.GlassCard
import com.agenthub.app.ui.theme.GlassTopAppBar
import com.agenthub.app.data.sync.DeviceSyncManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSyncScreen(
    viewModel: DeviceSyncViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    var showDiscoverDialog by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf<List<DeviceSyncManager.DiscoveredDevice>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text(stringResource(R.string.device_sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sync toggle
            item {
                SyncToggleCard(
                    isEnabled = syncState.isSyncEnabled,
                    isSyncing = syncState.isSyncing,
                    onToggle = { viewModel.toggleSync(it) },
                    onSyncNow = {
                        scope.launch { viewModel.syncAll() }
                    }
                )
            }

            // Sync status
            item {
                SyncStatusCard(syncState)
            }

            // Paired devices header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.device_sync_paired),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FilledTonalButton(
                        onClick = {
                            showDiscoverDialog = true
                            isDiscovering = true
                            scope.launch {
                                discoveredDevices = viewModel.discoverDevices()
                                isDiscovering = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.device_sync_discover))
                    }
                }
            }

            // Paired device list
            if (pairedDevices.isEmpty()) {
                item {
                    EmptyDevicesCard()
                }
            } else {
                items(pairedDevices, key = { it.id }) { device ->
                    PairedDeviceCard(
                        device = device,
                        onRemove = { viewModel.removeDevice(device.id) }
                    )
                }
            }
        }
    }

    // Discover dialog
    if (showDiscoverDialog) {
        DiscoverDevicesDialog(
            devices = discoveredDevices,
            isDiscovering = isDiscovering,
            onPair = { device ->
                scope.launch {
                    viewModel.pairDevice(device.id, device.name)
                    discoveredDevices = discoveredDevices.filter { it.id != device.id }
                }
            },
            onDismiss = { showDiscoverDialog = false }
        )
    }
}

@Composable
private fun SyncToggleCard(
    isEnabled: Boolean,
    isSyncing: Boolean,
    onToggle: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isEnabled) Icons.Default.Sync else Icons.Default.SyncDisabled,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.device_sync_enable),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isEnabled) stringResource(R.string.device_sync_enabled_desc)
                           else stringResource(R.string.device_sync_disabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }
        if (isEnabled) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onSyncNow,
                    enabled = !isSyncing
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.device_sync_now))
                }
            }
        }
    }
}

@Composable
private fun SyncStatusCard(syncState: DeviceSyncManager.SyncState) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.device_sync_status),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SyncStatItem(
                    label = stringResource(R.string.device_sync_messages),
                    value = "${syncState.syncedMessages}"
                )
                SyncStatItem(
                    label = stringResource(R.string.device_sync_configs),
                    value = "${syncState.syncedConfigs}"
                )
                SyncStatItem(
                    label = stringResource(R.string.device_sync_last),
                    value = if (syncState.lastSyncTime > 0) {
                        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                        sdf.format(Date(syncState.lastSyncTime))
                    } else "—"
                )
            }
        }
    }
}

@Composable
private fun SyncStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun PairedDeviceCard(
    device: DeviceSyncManager.PairedDevice,
    onRemove: () -> Unit
) {
    var showRemoveDialog by remember { mutableStateOf(false) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device icon with online indicator
            Box {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(
                        Icons.Default.DevicesOther,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Surface(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd),
                    shape = CircleShape,
                    color = if (device.isOnline)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline
                ) {}
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (device.isOnline) stringResource(R.string.device_sync_online)
                           else stringResource(R.string.device_sync_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.isOnline)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            IconButton(onClick = { showRemoveDialog = true }) {
                Icon(
                    Icons.Default.RemoveCircleOutline,
                    contentDescription = stringResource(R.string.device_sync_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.device_sync_remove_title)) },
            text = { Text(stringResource(R.string.device_sync_remove_desc, device.name)) },
            confirmButton = {
                TextButton(onClick = { onRemove(); showRemoveDialog = false }) {
                    Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun EmptyDevicesCard() {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.DevicesOther,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Text(
                text = stringResource(R.string.device_sync_no_devices),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = stringResource(R.string.device_sync_no_devices_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun DiscoverDevicesDialog(
    devices: List<DeviceSyncManager.DiscoveredDevice>,
    isDiscovering: Boolean,
    onPair: (DeviceSyncManager.DiscoveredDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_sync_discover_title)) },
        text = {
            Column {
                if (isDiscovering) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.device_sync_discovering))
                    }
                } else if (devices.isEmpty()) {
                    Text(stringResource(R.string.device_sync_no_found))
                } else {
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = stringResource(R.string.device_sync_signal, device.signalStrength),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            TextButton(onClick = { onPair(device) }) {
                                Text(stringResource(R.string.device_sync_pair))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_dismiss)) }
        }
    )
}
