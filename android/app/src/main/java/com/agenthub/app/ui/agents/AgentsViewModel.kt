package com.agenthub.app.ui.agents

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agenthub.app.agent.model.AgentConfig
import com.agenthub.app.agent.model.AgentType
import com.agenthub.app.data.repository.ChatRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject

data class AgentsUiState(
    val agents: List<AgentConfig> = emptyList(),
    val editingAgent: AgentConfig? = null,
    val showForm: Boolean = false,
    val exportMessage: String? = null
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
                _uiState.update { it.copy(agents = agents) }
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
            try {
                val configs = repository.getAllConfigsList()
                val json = Gson().toJson(configs)
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloads, "agenthub-configs-${System.currentTimeMillis()}.json")
                file.writeText(json)
                _uiState.update { it.copy(exportMessage = "Exported to ${file.name}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(exportMessage = "Export failed: ${e.message}") }
            }
        }
    }

    fun importConfigs(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (json != null) {
                    val type = object : TypeToken<List<AgentConfig>>() {}.type
                    val configs: List<AgentConfig> = Gson().fromJson(json, type)
                    configs.forEach { repository.saveConfig(it) }
                    _uiState.update { it.copy(exportMessage = "Imported ${configs.size} configs") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(exportMessage = "Import failed: ${e.message}") }
            }
        }
    }

    fun clearExportMessage() {
        _uiState.update { it.copy(exportMessage = null) }
    }
}
