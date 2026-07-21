package com.agentcontrolcenter.app.feature.agents

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.core.util.runSafely
import com.agentcontrolcenter.app.data.repository.ChatRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject

data class AgentsUiState(
    val agents: List<AgentConfig> = emptyList(),
    val editingAgent: AgentConfig? = null,
    val showForm: Boolean = false,
    val exportMessage: String? = null,
    // Agent 列表首次加载状态：首次从 Room 拿到数据前为 true，用于驱动骨架屏
    val isLoading: Boolean = true
)

@HiltViewModel
class AgentsViewModel @Inject constructor(
    application: Application,
    private val repository: ChatRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AgentsUiState())
    val uiState: StateFlow<AgentsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllConfigs().collect { agents ->
                // 首次拿到数据后关闭骨架屏加载状态
                _uiState.update { it.copy(agents = agents, isLoading = false) }
            }
        }
    }

    fun showNewForm() {
        _uiState.update {
            it.copy(
                editingAgent = AgentConfig(id = UUID.randomUUID().toString()),
                showForm = true
            )
        }
    }

    fun showEditForm(agent: AgentConfig) {
        _uiState.update { it.copy(editingAgent = agent, showForm = true) }
    }

    fun dismissForm() {
        _uiState.update { it.copy(showForm = false, editingAgent = null) }
    }

    fun saveAgent(agent: AgentConfig) {
        viewModelScope.launch {
            repository.saveConfig(agent)
            _uiState.update { it.copy(showForm = false, editingAgent = null) }
        }
    }

    fun deleteAgent(id: String) {
        viewModelScope.launch {
            repository.deleteConfig(id)
        }
    }

    fun exportConfigs(context: Context) {
        viewModelScope.launch {
            runSafely(
                onError = { e ->
                    _uiState.update { it.copy(exportMessage = "Export failed: ${e.message}") }
                }
            ) {
                // F21 修复：DB 全表读取 + Gson 序列化 + 文件写入 Downloads 全部为 I/O，
                // 移入 Dispatchers.IO（与 SettingsViewModel H-A3 修复模式一致）。
                val (fileName, saved) = withContext(Dispatchers.IO) {
                    val configs = repository.getAllConfigsList()
                    val json = Gson().toJson(configs)
                    val name = "agentcontrolcenter-configs-${System.currentTimeMillis()}.json"
                    val ok = writeJsonToDownloads(context, name, json)
                    name to ok
                }
                _uiState.update {
                    it.copy(
                        exportMessage = if (saved) "Exported to $fileName" else "Export failed"
                    )
                }
            }
        }
    }

    /**
     * Write a JSON payload into the public Downloads directory.
     *
     * On Android 10+ (API 29+) this uses the MediaStore.Downloads API via
     * ContentResolver.insert, which does not require any storage permissions.
     * On older API levels it falls back to the legacy
     * Environment.getExternalStoragePublicDirectory path (the WRITE_EXTERNAL_STORAGE
     * permission must be declared in the manifest for those versions).
     */
    private fun writeJsonToDownloads(context: Context, fileName: String, json: String): Boolean {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { os ->
                    os.write(json.toByteArray())
                }
            }
            uri != null
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloads, fileName)
            file.writeText(json)
            true
        }
    }

    fun importConfigs(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                // F21 修复：文件读取 + Gson 反序列化 + 批量 DB 写入全部为 I/O，
                // 移入 Dispatchers.IO（与 SettingsViewModel H-A4 修复模式一致）。
                val importedCount = withContext(Dispatchers.IO) {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    if (json == null) {
                        return@withContext 0
                    }
                    val type = object : TypeToken<List<AgentConfig>>() {}.type
                    val configs: List<AgentConfig> = Gson().fromJson(json, type)
                    configs.forEach { repository.saveConfig(it) }
                    configs.size
                }
                if (importedCount > 0) {
                    _uiState.update { it.copy(exportMessage = "Imported $importedCount configs") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(exportMessage = "Import failed: ${e.message}") }
            }
        }
    }

    fun clearExportMessage() {
        _uiState.update { it.copy(exportMessage = null) }
    }
}
