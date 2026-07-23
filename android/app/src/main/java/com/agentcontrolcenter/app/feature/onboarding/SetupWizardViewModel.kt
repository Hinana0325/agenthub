package com.agentcontrolcenter.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentProtocol
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.core.config.AgentConfigValidator
import com.agentcontrolcenter.app.core.config.ConfigValidationError
import com.agentcontrolcenter.app.core.config.ConfigRepository
import com.agentcontrolcenter.app.data.repository.ChatRepository
import com.agentcontrolcenter.app.transport.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

// MARK: - SetupWizardViewModel
// 配合 [SetupWizardScreen] 实现首次启动向导的 4 步流程。
// 每步可独立校验，最后一步测试连接 + 保存。

/**
 * 向导步骤枚举（与 UI 顺序一致）。
 */
enum class SetupWizardStep(val displayTitle: String) {
    Welcome("欢迎使用"),
    ChooseType("选择 Agent 类型"),
    Endpoint("填写服务地址"),
    AuthModel("API Key 与模型"),
    TestAndSave("测试并保存")
}

/**
 * 向导 UI 状态。
 *
 * `isConnecting` 用于「测试连接」按钮的 loading 状态；
 * `validationErrors` 是当前表单校验错误，由 [SetupWizardViewModel.validateCurrentStep] 填充。
 */
data class SetupWizardUiState(
    val currentStep: SetupWizardStep = SetupWizardStep.Welcome,
    val draft: AgentConfig = AgentConfig(id = UUID.randomUUID().toString()),
    val isConnecting: Boolean = false,
    val connectionResult: ConnectionTestResult? = null,
    val validationErrors: List<ConfigValidationError> = emptyList(),
    val isCompleted: Boolean = false
)

/** 测试连接结果。 */
sealed class ConnectionTestResult {
    data class Success(val serverUrl: String) : ConnectionTestResult()
    data class Failure(val message: String) : ConnectionTestResult()
}

@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val connectionRepository: ConnectionRepository,
    private val configRepository: ConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupWizardUiState())
    val uiState: StateFlow<SetupWizardUiState> = _uiState.asStateFlow()

    /** 当前测试连接协程，持有以便用户取消（testConnection 期间可主动中止） */
    private var testJob: Job? = null

    /** 步骤枚举序列，便于 UI 计算「上一步」/「下一步」 */
    private val stepSequence = SetupWizardStep.entries.toList()

    /** 当前步骤在序列中的下标 */
    fun currentStepIndex(): Int = stepSequence.indexOf(_uiState.value.currentStep)

    /**
     * 跳转到指定步骤。
     */
    fun goToStep(step: SetupWizardStep) {
        _uiState.update {
            it.copy(currentStep = step, validationErrors = emptyList())
        }
    }

    /** 下一步。返回 false 表示当前步骤校验失败，UI 应停留。 */
    fun nextStep(): Boolean {
        if (!validateCurrentStep()) return false
        val idx = currentStepIndex()
        if (idx >= stepSequence.lastIndex) return false
        goToStep(stepSequence[idx + 1])
        return true
    }

    /** 上一步。已到第一步时无操作。 */
    fun previousStep() {
        val idx = currentStepIndex()
        if (idx > 0) goToStep(stepSequence[idx - 1])
    }

    // ── 表单字段更新（每步 onChange 调用，触发实时校验）──

    fun updateName(name: String) {
        _uiState.update { it.copy(draft = it.draft.copy(name = name)) }
        validateCurrentStep()
    }

    fun updateAgentType(type: AgentType) {
        // 根据类型智能选择 protocolType：WebSocket 类协议走 WebSocket，
        // OpenAI / LocalModel 走 HttpSSE / Local
        val protocol = when (type) {
            AgentType.Hermes, AgentType.OpenClaw, AgentType.OpenCode -> AgentProtocol.WebSocket
            AgentType.OpenAI, AgentType.XiaomiMiMo, AgentType.OpenWebUI -> AgentProtocol.HttpSSE
            AgentType.LocalModel -> AgentProtocol.Local
            AgentType.ComfyUI -> AgentProtocol.HttpSSE
        }
        // 切换类型时预填合理默认值（serverUrl/model/temperature/maxTokens/systemPrompt），
        // 仅在用户尚未填写时填充，避免覆盖已输入内容
        val prefilled = com.agentcontrolcenter.app.agent.model.AgentTypeUi.withDefaults(
            _uiState.value.draft.copy(type = type, protocolType = protocol)
        )
        _uiState.update {
            it.copy(draft = prefilled)
        }
        validateCurrentStep()
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(draft = it.draft.copy(serverUrl = url)) }
        validateCurrentStep()
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(draft = it.draft.copy(apiKey = key)) }
        validateCurrentStep()
    }

    fun updateModel(model: String) {
        _uiState.update { it.copy(draft = it.draft.copy(model = model)) }
        validateCurrentStep()
    }

    fun updateTemperature(temp: Float) {
        _uiState.update { it.copy(draft = it.draft.copy(temperature = temp)) }
        validateCurrentStep()
    }

    fun updateMaxTokens(tokens: Int) {
        _uiState.update { it.copy(draft = it.draft.copy(maxTokens = tokens)) }
        validateCurrentStep()
    }

    /**
     * 校验当前步骤需要的字段（不是全表单）。
     *
     * 返回 false 时 errors 已填入 uiState，UI 据此显示错误。
     */
    fun validateCurrentStep(): Boolean {
        val draft = _uiState.value.draft
        val fullResult = AgentConfigValidator.validate(draft)
        val relevantFields = when (_uiState.value.currentStep) {
            SetupWizardStep.Welcome -> emptySet<String>()
            SetupWizardStep.ChooseType -> setOf("name")
            SetupWizardStep.Endpoint -> setOf("serverUrl")
            SetupWizardStep.AuthModel -> setOf("apiKey", "model")
            SetupWizardStep.TestAndSave -> setOf("temperature", "maxTokens", "systemPrompt")
        }
        val filtered = fullResult.errors.filter { it.field in relevantFields }
        _uiState.update { it.copy(validationErrors = filtered) }
        return filtered.isEmpty()
    }

    /**
     * 测试连接（在 TestAndSave 步骤触发）。
     *
     * 通过 [ConnectionRepository.connect] 触发握手，然后等待最多 8 秒观察
     * transport.connectionState 是否进入 Connected 状态：
     * - Connected → 显示 Success
     * - 8 秒后仍未 Connected → 显示 Failure
     *
     * [ConnectionRepository.connect] 本身不抛异常（异步驱动连接状态），所以
     * 不能用 try/catch 判定结果，必须 collect 状态流。
     */
    fun testConnection() {
        // 必须先全量校验通过再尝试连接
        val fullResult = AgentConfigValidator.validate(_uiState.value.draft)
        if (!fullResult.isValid) {
            _uiState.update { it.copy(validationErrors = fullResult.errors) }
            return
        }
        // 取消上一次未完成的测试，避免并发连接状态叠加
        testJob?.cancel()
        // 重试前先断开既有连接：ConnectionRepository 对同类型 transport 会复用实例，
        // 不先 disconnect 会导致状态混乱（旧连接残留 + 新连接握手叠加）
        connectionRepository.disconnect()
        testJob = viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, connectionResult = null) }
            try {
                connectionRepository.connect(_uiState.value.draft, e2eKey = null)
                // 等待最多 8 秒观察连接状态
                val deadline = System.currentTimeMillis() + 8_000L
                var connected = false
                while (System.currentTimeMillis() < deadline) {
                    ensureActive() // 响应用户取消，抛 CancellationException
                    val transportState = connectionRepository.connectionState.value
                    if (transportState.isConnected) {
                        connected = true
                        break
                    }
                    delay(200)
                }
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectionResult = if (connected) {
                            ConnectionTestResult.Success(it.draft.serverUrl)
                        } else {
                            ConnectionTestResult.Failure("连接超时或服务器无响应")
                        }
                    )
                }
            } catch (e: CancellationException) {
                // 用户主动取消：不显示失败结果，仅复位 loading 状态
                _uiState.update { it.copy(isConnecting = false) }
                throw e // 重新抛出以正确传播协程取消
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectionResult = ConnectionTestResult.Failure(
                            e.message ?: "连接失败（未知原因）"
                        )
                    )
                }
            } finally {
                testJob = null
            }
        }
    }

    /**
     * 取消进行中的测试连接（用户点击「取消」按钮时调用）。
     *
     * 若当前无测试任务则无操作。
     */
    fun cancelTestConnection() {
        testJob?.cancel()
        _uiState.update { it.copy(isConnecting = false) }
    }

    /**
     * 保存配置并标记 onboarding 完成。
     *
     * 调用方应在 `connectionResult is Success` 后再调本方法。
     */
    fun saveAndComplete() {
        val draft = _uiState.value.draft
        val result = AgentConfigValidator.validate(draft)
        if (!result.isValid) {
            _uiState.update { it.copy(validationErrors = result.errors) }
            return
        }
        viewModelScope.launch {
            // 1. 落库
            repository.saveConfig(draft)
            // 2. 同步默认值到 AppPreferences（便于下次新建 Agent 预填）
            configRepository.setAgentDefaults(
                com.agentcontrolcenter.app.core.config.AgentDefaults(
                    defaultModel = draft.model,
                    defaultTemperature = draft.temperature,
                    defaultMaxTokens = draft.maxTokens
                )
            )
            // 3. 标记 onboarding 完成
            configRepository.setOnboardingCompleted(true)
            // 4. 通知 UI 切换到主界面
            _uiState.update { it.copy(isCompleted = true) }
        }
    }

    /**
     * 跳过向导（用户不想配置第一个 Agent，直接进入主界面）。
     *
     * 标记 onboarding 完成但**不**保存任何 AgentConfig。
     */
    fun skipWizard() {
        viewModelScope.launch {
            configRepository.setOnboardingCompleted(true)
            _uiState.update { it.copy(isCompleted = true) }
        }
    }

    fun clearConnectionResult() {
        _uiState.update { it.copy(connectionResult = null) }
    }
}
