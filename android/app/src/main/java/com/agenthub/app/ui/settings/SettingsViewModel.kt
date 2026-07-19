package com.agenthub.app.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agenthub.app.R
import com.agenthub.app.agent.model.AgentConfig
import com.agenthub.app.data.model.ChatBackup
import com.agenthub.app.data.repository.ChatRepository
import com.agenthub.app.core.datastore.SettingsDataStore
import com.agenthub.app.core.common.PerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: String = "system",
    val fontSize: String = "medium",
    val e2eEnabled: Boolean = false,
    val e2eKey: String = "",
    val backupMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val dataStore: SettingsDataStore,
    private val repository: ChatRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Agent configs exposed via Hilt-injected repository (replaces direct AppModule access from the UI).
    val agentConfigs: StateFlow<List<AgentConfig>> = repository.getAllConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            dataStore.themeMode.collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            dataStore.fontSize.collect { size ->
                _uiState.update { it.copy(fontSize = size) }
            }
        }
        viewModelScope.launch {
            dataStore.e2eEnabled.collect { enabled ->
                _uiState.update { it.copy(e2eEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            dataStore.e2eKey.collect { key ->
                _uiState.update { it.copy(e2eKey = key) }
            }
        }
        // NOTE: The previous implementation ran a permanent `while (isActive) { ... delay(3000) }`
        // loop in init to refresh performance metrics. That kept a coroutine alive for the entire
        // lifetime of the ViewModel (i.e. the whole app session) and refreshed metrics even when the
        // Settings screen was not on screen. Refresh is now opt-in via [refreshPerformanceMetrics],
        // which the SettingsScreen should call from a `LaunchedEffect`/`DisposableEffect` so it only
        // runs while the screen is visible.
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { dataStore.setThemeMode(mode) }
    }

    fun setFontSize(size: String) {
        viewModelScope.launch { dataStore.setFontSize(size) }
    }

    // ── E2E Encryption ──

    fun toggleE2E(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setE2eEnabled(enabled)
            if (enabled && _uiState.value.e2eKey.isEmpty()) {
                regenerateKey()
            }
        }
    }

    fun regenerateKey() {
        viewModelScope.launch {
            val key = generateSecureKey()
            dataStore.setE2eKey(key)
        }
    }

    fun copyKey(context: Context) {
        val key = _uiState.value.e2eKey
        if (key.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AgentHub E2E Key", key)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.e2e_key_copied), Toast.LENGTH_SHORT).show()
        }
    }

    fun importKey(key: String) {
        viewModelScope.launch {
            dataStore.setE2eKey(key.trim())
            if (!_uiState.value.e2eEnabled) {
                dataStore.setE2eEnabled(true)
            }
        }
    }

    private fun generateSecureKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Backup / Restore ──

    fun exportChatHistory(context: Context) {
        viewModelScope.launch {
            try {
                val sessions = repository.getAllSessionsList()
                val allMessages = sessions.flatMap { session ->
                    repository.getMessagesBySessionList(session.id)
                }
                val backup = ChatBackup(
                    version = "2.2.0",
                    exportedAt = System.currentTimeMillis(),
                    sessions = sessions,
                    messages = allMessages
                )
                val json = Gson().toJson(backup)
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloads, "agenthub-backup-${System.currentTimeMillis()}.json")
                file.writeText(json)
                _uiState.update { it.copy(backupMessage = context.getString(R.string.backup_saved, file.name)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(backupMessage = context.getString(R.string.backup_export_failed, e.message ?: "")) }
            }
        }
    }

    fun importChatHistory(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (json != null) {
                    val backup = Gson().fromJson(json, ChatBackup::class.java)
                    backup.sessions.forEach { session ->
                        repository.insertSessionDirect(session)
                    }
                    backup.messages.forEach { message ->
                        repository.insertMessageDirect(message)
                    }
                    _uiState.update { it.copy(backupMessage = context.getString(R.string.backup_restored, backup.sessions.size, backup.messages.size)) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(backupMessage = context.getString(R.string.backup_restore_failed, e.message ?: "")) }
            }
        }
    }

    fun clearBackupMessage() {
        _uiState.update { it.copy(backupMessage = null) }
    }

    // ── Performance ──

    fun getPerformanceMetrics() = PerformanceMonitor.metrics

    /**
     * Refresh performance metrics (memory, uptime, avg latency) on demand.
     *
     * The SettingsScreen should call this from a `LaunchedEffect` (or `DisposableEffect` with a
     * polling loop) while it is visible, instead of relying on a permanent background loop that
     * runs for the entire ViewModel lifetime. This avoids keeping a coroutine alive and doing
     * unnecessary work when the user is not viewing the Settings screen.
     */
    fun refreshPerformanceMetrics() {
        viewModelScope.launch {
            try {
                PerformanceMonitor.refresh(getApplication())
            } catch (_: Exception) {
                // ignore — non-critical refresh
            }
        }
    }
}
