package com.agentcontrolcenter.app.feature.compare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.core.util.runSafely
import com.agentcontrolcenter.app.data.repository.ChatRepository
import com.agentcontrolcenter.app.transport.protocol.AgentEvent
import com.agentcontrolcenter.app.transport.protocol.AgentTransport
import com.agentcontrolcenter.app.transport.TransportFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CompareUiState(
    val agentAName: String = "Agent A",
    val agentBName: String = "Agent B",
    val agentAResponse: String = "",
    val agentBResponse: String = "",
    val isComparing: Boolean = false,
    val isAComplete: Boolean = false,
    val isBComplete: Boolean = false,
    val error: String? = null,
    val isCancelled: Boolean = false
)

@HiltViewModel
class CompareViewModel @Inject constructor(
    application: Application,
    private val repository: ChatRepository,
    private val transportFactory: TransportFactory
) : AndroidViewModel(application) {
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
                error = null,
                isCancelled = false
            )
        }
        val sessionId = "compare_${System.currentTimeMillis()}"
        viewModelScope.launch { repository.logActivity("compare", "Starting compare: ${configA.name} vs ${configB.name}") }

        viewModelScope.launch {
            runSafely(
                onError = { e ->
                    _transportA.value?.shutdown()
                    _transportA.value = null
                    // A failed to start: mark A complete and only stop comparing if B is done.
                    _uiState.update {
                        it.copy(isAComplete = true, isComparing = !it.isBComplete, error = "Agent A: ${e.message}")
                    }
                }
            ) {
                val transportA = transportFactory.create(configA.type).also { _transportA.value = it }
                transportA.connect(configA)
                transportA.sendMessage("${sessionId}_a", prompt)
            }
        }

        viewModelScope.launch {
            runSafely(
                onError = { e ->
                    _transportB.value?.shutdown()
                    _transportB.value = null
                    // B failed to start: mark B complete and only stop comparing if A is done.
                    _uiState.update {
                        it.copy(isBComplete = true, isComparing = !it.isAComplete, error = "Agent B: ${e.message}")
                    }
                }
            ) {
                val transportB = transportFactory.create(configB.type).also { _transportB.value = it }
                transportB.connect(configB)
                transportB.sendMessage("${sessionId}_b", prompt)
            }
        }

        // Timeout: cancel after 60 seconds if neither agent responded
        viewModelScope.launch {
            delay(60_000)
            if (_uiState.value.isComparing && !_uiState.value.isAComplete && !_uiState.value.isBComplete) {
                cancelCompare()
                _uiState.update { it.copy(error = "Compare timed out (60s)") }
            }
        }
    }

    fun cancelCompare() {
        _uiState.update { it.copy(isCancelled = true) }
        viewModelScope.launch { repository.logActivity("compare", "Compare cancelled") }
        // 使用 shutdown 而非 disconnect，彻底释放 transport 的协程作用域与 HttpClient。
        // disconnect 只断开连接但不释放底层资源，CompareViewModel 频繁创建/销毁
        // transport 会导致协程作用域与 HttpClient 泄漏。
        _transportA.value?.shutdown()
        _transportB.value?.shutdown()
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
        if (_uiState.value.isCancelled) return
        when (event) {
            is AgentEvent.MessageReceived -> {
                if (event.isDelta) {
                    _uiState.update { it.copy(agentAResponse = it.agentAResponse + event.content) }
                } else {
                    // Mark A complete, but only stop comparing once B is also complete.
                    // Previously this set isComparing = false as soon as A finished, which
                    // made the UI show a "done" state while B was still streaming.
                    _uiState.update {
                        it.copy(
                            agentAResponse = event.content,
                            isAComplete = true,
                            isComparing = !it.isBComplete
                        )
                    }
                }
            }
            is AgentEvent.Error -> {
                _uiState.update {
                    it.copy(
                        isAComplete = true,
                        isComparing = !it.isBComplete,
                        error = "Agent A: ${event.message}"
                    )
                }
            }
            is AgentEvent.Disconnected -> {
                _uiState.update {
                    it.copy(isAComplete = true, isComparing = !it.isBComplete)
                }
            }
            is AgentEvent.StreamComplete -> {
                // HTTP SSE 纯增量流结束后触发，标记 A 完成并检查是否全部完成。
                _uiState.update {
                    it.copy(isAComplete = true, isComparing = !it.isBComplete)
                }
            }
            is AgentEvent.Connected -> { /* connected */ }
            is AgentEvent.Reconnecting -> { /* reconnecting */ }
        }
    }

    private suspend fun handleAgentBEvent(event: AgentEvent) {
        if (_uiState.value.isCancelled) return
        when (event) {
            is AgentEvent.MessageReceived -> {
                if (event.isDelta) {
                    _uiState.update { it.copy(agentBResponse = it.agentBResponse + event.content) }
                } else {
                    // Mark B complete, but only stop comparing once A is also complete.
                    // Previously this set isComparing = false as soon as B finished, which
                    // made the UI show a "done" state while A was still streaming.
                    _uiState.update {
                        it.copy(
                            agentBResponse = event.content,
                            isBComplete = true,
                            isComparing = !it.isAComplete
                        )
                    }
                }
            }
            is AgentEvent.Error -> {
                _uiState.update {
                    it.copy(
                        isBComplete = true,
                        isComparing = !it.isAComplete,
                        error = "Agent B: ${event.message}"
                    )
                }
            }
            is AgentEvent.Disconnected -> {
                _uiState.update {
                    it.copy(isBComplete = true, isComparing = !it.isAComplete)
                }
            }
            is AgentEvent.StreamComplete -> {
                // HTTP SSE 纯增量流结束后触发，标记 B 完成并检查是否全部完成。
                _uiState.update {
                    it.copy(isBComplete = true, isComparing = !it.isAComplete)
                }
            }
            is AgentEvent.Connected -> { /* connected */ }
            is AgentEvent.Reconnecting -> { /* reconnecting */ }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 使用 shutdown 彻底释放 transport 资源（协程作用域、HttpClient、Channel），
        // 修复此前仅 disconnect 导致的协程作用域与 HttpClient 泄漏。
        _transportA.value?.shutdown()
        _transportB.value?.shutdown()
        _transportA.value = null
        _transportB.value = null
    }
}
