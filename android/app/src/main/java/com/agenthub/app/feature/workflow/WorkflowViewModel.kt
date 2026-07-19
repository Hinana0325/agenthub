package com.agenthub.app.feature.workflow

import androidx.lifecycle.ViewModel
import com.agenthub.app.runtime.workflow.Workflow
import com.agenthub.app.runtime.workflow.WorkflowEngine
import com.agenthub.app.runtime.workflow.WorkflowExecutionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel that owns the [WorkflowEngine] instance.
 *
 * Previously the screen created the engine with `remember { WorkflowEngine() }`,
 * which reset all execution state (running status, current node, logs, output)
 * every time the user navigated away from the screen and came back. Hoisting the
 * engine into a Hilt-scoped ViewModel preserves that state across navigation.
 *
 * Phase 4.1: WorkflowEngine 现在是 @Singleton，由 Hilt 注入。AGENT 节点
 * 通过注入的 TransportFactory 和 AgentConfigDao 调用真实 Agent。
 */
@HiltViewModel
class WorkflowViewModel @Inject constructor(
    private val engine: WorkflowEngine
) : ViewModel() {

    val executionState: StateFlow<WorkflowExecutionState> = engine.executionState

    suspend fun execute(workflow: Workflow, input: String): String = engine.execute(workflow, input)

    fun reset() = engine.reset()
}
