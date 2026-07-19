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
 */
@HiltViewModel
class WorkflowViewModel @Inject constructor() : ViewModel() {

    private val engine = WorkflowEngine()

    val executionState: StateFlow<WorkflowExecutionState> = engine.executionState

    suspend fun execute(workflow: Workflow, input: String): String = engine.execute(workflow, input)

    fun reset() = engine.reset()
}
