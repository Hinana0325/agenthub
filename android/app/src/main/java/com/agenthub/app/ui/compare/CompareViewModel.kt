package com.agenthub.app.ui.compare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agenthub.app.data.AppModule
import com.agenthub.app.data.model.AgentConfig
import com.agenthub.app.provider.AgentEvent
import com.agenthub.app.provider.AgentTransport
import com.agenthub.app.provider.TransportFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CompareUiState(
    val agentAName: String = "Agent A",
    val agentBName: String = "Agent B",
    val agentAResponse: String = "",
    val agentBResponse: String = "",
    val isComparing: Boolean = false,
    val isAComplete: Boolean = false,
    val isBComplete: Boolean = false,
    val error: String? = null
)

class CompareViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppModule.getRepository(application)
    private val _transportA = MutableStateFlow<AgentTransport?>(null)
    private val _transportB = MutableStateFlow<AgentTransport?>(null)

    private val _uiState = MutableStateFlow(CompareUiState())
    val uiState: StateFlow<CompareUiState> = _uiState.asStateFlow()

    init {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _transportA.filterNotNull().flatMapLatest { it.events }.collect { event ->
                handleAgentAEvent(event)
            }
        }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            _transportB.filterNotNull().flatMapLatest { it.events }.collect { event ->
                handleAgentBEvent(event)
            }
        }
    }

    fun startCompare(configA: AgentConfig, configB: AgentConfig, prompt: String) {
        _uiState.update {
            it.copy(
                agentAName = configA.name,
                agentBName = configB.name,
                agentAResponse = "",
                agentBResponse = "",
                isComparing = true,
                isAComplete = false,
                isBComplete = false,
                error = null
            )
        }
        viewModelScope.launch { repository.logActivity("compare", "Starting compare between ${configA.name} and ${configB.name}") }

        viewModelScope.launch {
            try {
                val transportA = TransportFactory.create(configA.type).also {
                    _transportA.value = it
                }
                transportA.connect(configA)
                transportA.sendMessage("compare_session_a", prompt)
            } catch (e: Exception) {
                _uiState.update { it.copy(isAComplete = true, error = "Agent A error: ${e.message}") }
            }
        }

        viewModelScope.launch {
            try {
                val transportB = TransportFactory.create(configB.type).also {
                    _transportB.value = it
                }
                transportB.connect(configB)
                transportB.sendMessage("compare_session_b", prompt)
            } catch (e: Exception) {
                _uiState.update { it.copy(isBComplete = true, error = "Agent B error: ${e.message}") }
            }
        }
    }

    fun cancelCompare() {
        viewModelScope.launch { repository.logActivity("compare", "Compare cancelled") }
        _transportA.value?.disconnect()
        _transportB.value?.disconnect()
        _transportA.value = null
        _transportB.value = null
        _uiState.update {
            it.copy(isComparing = false, error = null)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun handleAgentAEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageReceived -> {
                if (event.isDelta) {
                    _uiState.update { it.copy(agentAResponse = it.agentAResponse + event.content) }
                } else {
                    _uiState.update {
                        it.copy(agentAResponse = event.content, isAComplete = true, isComparing = false)
                    }
                }
            }
            is AgentEvent.Error -> {
                _uiState.update { it.copy(isAComplete = true, isComparing = false, error = "Agent A: ${event.message}") }
            }
            is AgentEvent.Disconnected -> {
                _uiState.update { it.copy(isAComplete = true, isComparing = false) }
            }
            is AgentEvent.Connected -> { /* connected */ }
            is AgentEvent.Reconnecting -> { /* reconnecting */ }
        }
    }

    private suspend fun handleAgentBEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageReceived -> {
                if (event.isDelta) {
                    _uiState.update { it.copy(agentBResponse = it.agentBResponse + event.content) }
                } else {
                    _uiState.update {
                        it.copy(agentBResponse = event.content, isBComplete = true, isComparing = false)
                    }
                }
            }
            is AgentEvent.Error -> {
                _uiState.update { it.copy(isBComplete = true, isComparing = false, error = "Agent B: ${event.message}") }
            }
            is AgentEvent.Disconnected -> {
                _uiState.update { it.copy(isBComplete = true, isComparing = false) }
            }
            is AgentEvent.Connected -> { /* connected */ }
            is AgentEvent.Reconnecting -> { /* reconnecting */ }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _transportA.value?.disconnect()
        _transportB.value?.disconnect()
        _transportA.value = null
        _transportB.value = null
    }
}
