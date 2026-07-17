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
import com.agenthub.app.data.AppModule
import com.agenthub.app.data.model.ChatBackup
import com.agenthub.app.data.settings.SettingsDataStore
import com.agenthub.app.util.PerformanceMonitor
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

data class SettingsUiState(
    val themeMode: String = "system",
    val fontSize: String = "medium",
    val e2eEnabled: Boolean = false,
    val e2eKey: String = "",
    val backupMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = SettingsDataStore(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
        // Periodically refresh performance metrics (memory, uptime) every 3 seconds
        viewModelScope.launch {
            while (isActive) {
                PerformanceMonitor.refresh(getApplication())
                kotlinx.coroutines.delay(3000)
            }
        }
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
                val repository = AppModule.getRepository(context)
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
                    val repository = AppModule.getRepository(context)
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
}
