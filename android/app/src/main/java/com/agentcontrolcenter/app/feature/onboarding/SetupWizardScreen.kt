package com.agentcontrolcenter.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcontrolcenter.app.R
import com.agentcontrolcenter.app.agent.model.AgentType
import kotlinx.coroutines.launch

// MARK: - SetupWizardScreen
// 首次启动配置向导，替代纯介绍型的 [OnboardingScreen]。
// 5 步流程：欢迎 → 选类型 → 填地址 → 填 API Key/模型 → 测试连接 + 保存

/**
 * Setup Wizard 主入口。
 *
 * @param onComplete 完成回调（无论保存还是跳过），由 [MainActivity] 切换到主界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    onComplete: () -> Unit,
    viewModel: SetupWizardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { SetupWizardStep.entries.size })
    val scope = rememberCoroutineScope()

    // 完成时回调
    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted) onComplete()
    }

    // VM 步骤变化时同步 pager
    LaunchedEffect(state.currentStep) {
        val target = SetupWizardStep.entries.indexOf(state.currentStep)
        if (target >= 0 && target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // 顶部：跳过按钮
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopEnd
        ) {
            TextButton(onClick = { viewModel.skipWizard() }) {
                Text(stringResource(R.string.btn_skip))
            }
        }

        // Pager 内容
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            // 禁止用户左右滑动手势，强制按「下一步」校验后才能跳步
            userScrollEnabled = false
        ) { page ->
            val step = SetupWizardStep.entries[page]
            SetupStepContent(
                step = step,
                state = state,
                viewModel = viewModel
            )
        }

        // 步骤指示器
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(SetupWizardStep.entries.size) { index ->
                val color = if (pagerState.currentPage == index) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
                Surface(
                    color = color,
                    shape = CircleShape,
                    modifier = Modifier.size(8.dp)
                ) {}
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 底部按钮区
        SetupWizardBottomBar(
            state = state,
            onPrevious = {
                viewModel.previousStep()
                scope.launch {
                    val prev = SetupWizardStep.entries.indexOf(state.currentStep) - 1
                    if (prev >= 0) pagerState.animateScrollToPage(prev)
                }
            },
            onNext = {
                if (viewModel.nextStep()) {
                    scope.launch {
                        val next = SetupWizardStep.entries.indexOf(viewModel.uiState.value.currentStep)
                        if (next >= 0) pagerState.animateScrollToPage(next)
                    }
                }
            },
            onTestConnection = { viewModel.testConnection() },
            onCancelConnection = { viewModel.cancelTestConnection() },
            onSave = { viewModel.saveAndComplete() }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SetupStepContent(
    step: SetupWizardStep,
    state: SetupWizardUiState,
    viewModel: SetupWizardViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (step) {
            SetupWizardStep.Welcome -> WelcomeStep()
            SetupWizardStep.ChooseType -> ChooseTypeStep(state, viewModel)
            SetupWizardStep.Endpoint -> EndpointStep(state, viewModel)
            SetupWizardStep.AuthModel -> AuthModelStep(state, viewModel)
            SetupWizardStep.TestAndSave -> TestAndSaveStep(state, viewModel)
        }
    }
}

@Composable
private fun WelcomeStep() {
    Spacer(modifier = Modifier.height(48.dp))
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(96.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    Text(
        text = "欢迎使用 Agent Control Center",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = "本向导将引导你配置第一个 Agent。" +
            "完成后即可开始对话。如果暂时不想配置，可点击右上角「跳过」稍后再设置。",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseTypeStep(state: SetupWizardUiState, viewModel: SetupWizardViewModel) {
    Text(
        text = "选择 Agent 类型",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = "不同类型对应不同的传输协议（WebSocket / HTTP-SSE / 本地进程）。选择最贴近你部署方式的类型。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    OutlinedTextField(
        value = state.draft.name,
        onValueChange = viewModel::updateName,
        label = { Text("Agent 名称") },
        singleLine = true,
        isError = state.validationErrors.any { it.field == "name" },
        supportingText = {
            state.validationErrors.firstOrNull { it.field == "name" }?.let { Text(it.message) }
        },
        modifier = Modifier.fillMaxWidth()
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        AgentType.entries.forEach { type ->
            val selected = state.draft.type == type
            FilterChip(
                selected = selected,
                onClick = { viewModel.updateAgentType(type) },
                label = { Text(type.displayName) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else null
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointStep(state: SetupWizardUiState, viewModel: SetupWizardViewModel) {
    Text(
        text = "填写服务地址",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    val isLocal = state.draft.type == AgentType.LocalModel
    Text(
        text = if (isLocal) "本地模型可填 Ollama / LM Studio 的本地端点（如 http://localhost:11434）。"
        else "支持 http/https/ws/wss；禁止指向云厂商元数据 IP（SSRF 防护）。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    OutlinedTextField(
        value = state.draft.serverUrl,
        onValueChange = viewModel::updateServerUrl,
        label = { Text("服务地址") },
        singleLine = true,
        isError = state.validationErrors.any { it.field == "serverUrl" },
        supportingText = {
            state.validationErrors.firstOrNull { it.field == "serverUrl" }?.let { Text(it.message) }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthModelStep(state: SetupWizardUiState, viewModel: SetupWizardViewModel) {
    Text(
        text = "API Key 与模型",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    val isLocal = state.draft.type == AgentType.LocalModel
    if (!isLocal) {
        OutlinedTextField(
            value = state.draft.apiKey,
            onValueChange = viewModel::updateApiKey,
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = state.validationErrors.any { it.field == "apiKey" },
            supportingText = {
                state.validationErrors.firstOrNull { it.field == "apiKey" }?.let { Text(it.message) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    OutlinedTextField(
        value = state.draft.model,
        onValueChange = viewModel::updateModel,
        label = { Text("模型名称") },
        singleLine = true,
        isError = state.validationErrors.any { it.field == "model" },
        supportingText = {
            state.validationErrors.firstOrNull { it.field == "model" }?.let { Text(it.message) }
        },
        modifier = Modifier.fillMaxWidth()
    )
    // 温度
    val tempError = state.validationErrors.firstOrNull { it.field == "temperature" }
    Text("Temperature: ${"%.2f".format(state.draft.temperature)}", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = state.draft.temperature,
        onValueChange = viewModel::updateTemperature,
        valueRange = 0f..2f
    )
    if (tempError != null) Text(tempError.message, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun TestAndSaveStep(state: SetupWizardUiState, viewModel: SetupWizardViewModel) {
    Text(
        text = "测试连接并保存",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = "点击「测试连接」验证地址与凭据是否可用。成功后再点「保存并完成」结束向导。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    // 摘要
    AssistChip(
        onClick = {},
        label = { Text("类型: ${state.draft.type.displayName}") },
        colors = AssistChipDefaults.assistChipColors()
    )
    AssistChip(
        onClick = {},
        label = { Text("协议: ${state.draft.protocolType.displayName}") }
    )
    AssistChip(
        onClick = {},
        label = { Text("模型: ${state.draft.model.ifBlank { "(未填)" }}") }
    )

    // 连接结果
    when (val result = state.connectionResult) {
        is ConnectionTestResult.Success -> Text(
            "✓ 连接成功：${result.serverUrl}",
            color = MaterialTheme.colorScheme.primary
        )
        is ConnectionTestResult.Failure -> Text(
            "✗ 连接失败：${result.message}",
            color = MaterialTheme.colorScheme.error
        )
        null -> {}
    }
}

@Composable
private fun SetupWizardBottomBar(
    state: SetupWizardUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTestConnection: () -> Unit,
    onCancelConnection: () -> Unit,
    onSave: () -> Unit
) {
    val isLastStep = state.currentStep == SetupWizardStep.TestAndSave
    val isFirstStep = state.currentStep == SetupWizardStep.Welcome
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!isFirstStep) {
            OutlinedButtonWithIcon(
                onClick = onPrevious,
                text = "上一步",
                icon = Icons.AutoMirrored.Filled.ArrowBack
            )
        }
        if (!isLastStep) {
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("下一步")
            }
        } else {
            // 最后一步：测试中显示「取消」可中止，否则显示「测试连接」
            if (state.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                OutlinedButtonWithIcon(
                    onClick = onCancelConnection,
                    text = "取消测试",
                    icon = Icons.Default.Cancel
                )
            } else {
                OutlinedButtonWithIcon(
                    onClick = onTestConnection,
                    text = "测试连接",
                    icon = Icons.Default.Bolt
                )
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                enabled = state.connectionResult is ConnectionTestResult.Success
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("保存并完成")
            }
        }
    }
}

@Composable
private fun OutlinedButtonWithIcon(
    onClick: () -> Unit,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.size(8.dp))
        Text(text)
    }
}
