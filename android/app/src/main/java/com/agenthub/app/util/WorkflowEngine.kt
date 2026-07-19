package com.agenthub.app.util

import com.agenthub.app.data.model.AgentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Agent 编排工作流引擎
 *
 * 支持多节点 DAG 执行，每个节点可以是：
 * - INPUT: 接收外部输入
 * - AGENT: 调用 Agent（本地或远程）
 * - TRANSFORM: 数据转换（提取、格式化、过滤）
 * - OUTPUT: 输出最终结果
 */

enum class NodeType(val displayName: String) {
    INPUT("Input"),
    AGENT("Agent"),
    TRANSFORM("Transform"),
    OUTPUT("Output")
}

data class WorkflowNode(
    val id: String = UUID.randomUUID().toString(),
    val type: NodeType,
    val label: String = "",
    val agentType: AgentType? = null,
    val prompt: String = "",
    val transformType: TransformType = TransformType.PASSTHROUGH,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val outputCache: String = ""
)

enum class TransformType(val displayName: String) {
    PASSTHROUGH("Pass Through"),
    EXTRACT("Extract"),
    TO_UPPERCASE("To Uppercase"),
    TO_LOWERCASE("To Lowercase"),
    TRIM("Trim"),
    PREFIX("Add Prefix"),
    SUFFIX("Add Suffix"),
    JSON_EXTRACT("Extract JSON Field")
}

data class WorkflowEdge(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String,
    val toNodeId: String,
    val condition: String? = null  // null = always, otherwise evaluate
)

data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList()
)

data class WorkflowExecutionState(
    val isRunning: Boolean = false,
    val currentNodeId: String? = null,
    val completedNodeIds: Set<String> = emptySet(),
    val output: String = "",
    val error: String? = null,
    val logs: List<String> = emptyList()
)

class WorkflowEngine {

    private val _executionState = MutableStateFlow(WorkflowExecutionState())
    val executionState: StateFlow<WorkflowExecutionState> = _executionState.asStateFlow()

    /**
     * 执行工作流
     */
    suspend fun execute(
        workflow: Workflow,
        input: String,
        agentExecutor: suspend (AgentType, String, String) -> String = { _, prompt, _ -> prompt }
    ): String = withContext(Dispatchers.IO) {
        _executionState.value = WorkflowExecutionState(isRunning = true)

        try {
            // Build adjacency map
            val adjacency = mutableMapOf<String, MutableList<String>>()
            workflow.edges.forEach { edge ->
                adjacency.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge.toNodeId)
            }

            // Find input node
            val inputNode = workflow.nodes.find { it.type == NodeType.INPUT }
                ?: throw IllegalStateException("No INPUT node found")

            // Topological execution via BFS
            val nodeMap = workflow.nodes.associateBy { it.id }
            val results = mutableMapOf<String, String>()
            val visited = mutableSetOf<String>()
            val queue = mutableListOf(inputNode.id)
            results[inputNode.id] = input

            var stepLog = mutableListOf<String>()
            stepLog.add("Starting workflow: ${workflow.name}")

            // Cycle detection: track how many times each node has been re-queued.
            // If a node is re-queued more than MAX_REQUEUES times, its predecessors
            // can never all complete (likely a cycle), so we abort.
            val requeueCount = mutableMapOf<String, Int>()
            val MAX_REQUEUES = 100

            while (queue.isNotEmpty()) {
                val nodeId = queue.removeFirst()
                if (nodeId in visited) continue
                val node = nodeMap[nodeId] ?: continue

                // Check all predecessors are done
                val predecessors = workflow.edges.filter { it.toNodeId == nodeId }.map { it.fromNodeId }
                if (predecessors.any { it !in visited && it != nodeId }) {
                    val count = (requeueCount[nodeId] ?: 0) + 1
                    if (count > MAX_REQUEUES) {
                        throw IllegalStateException("Possible cycle detected in workflow at node '${node.label.ifEmpty { nodeId }}'")
                    }
                    requeueCount[nodeId] = count
                    queue.add(nodeId) // Re-queue
                    continue
                }

                _executionState.value = _executionState.value.copy(
                    currentNodeId = nodeId,
                    logs = stepLog
                )

                // Execute node
                val nodeInput = if (predecessors.isEmpty()) {
                    results[nodeId] ?: input
                } else {
                    // Combine outputs from all predecessors
                    predecessors.map { results[it] ?: "" }.joinToString("\n\n")
                }

                val nodeOutput = executeNode(node, nodeInput, agentExecutor)
                results[nodeId] = nodeOutput
                visited.add(nodeId)

                stepLog.add("[${node.type.displayName}] ${node.label.ifEmpty { node.id }}: ${nodeOutput.take(100)}")

                // Add successors to queue
                adjacency[nodeId]?.let { successors ->
                    queue.addAll(successors.filter { it !in visited })
                }
            }

            // Find output
            val outputNode = workflow.nodes.find { it.type == NodeType.OUTPUT }
            val finalOutput = outputNode?.let { results[it.id] }
                ?: results.values.lastOrNull()
                ?: ""

            stepLog.add("Workflow completed.")
            _executionState.value = WorkflowExecutionState(
                isRunning = false,
                completedNodeIds = visited,
                output = finalOutput,
                logs = stepLog
            )

            finalOutput
        } catch (e: Exception) {
            _executionState.value = _executionState.value.copy(
                isRunning = false,
                error = e.message,
                logs = _executionState.value.logs + "Error: ${e.message}"
            )
            "Error: ${e.message}"
        }
    }

    private suspend fun executeNode(
        node: WorkflowNode,
        input: String,
        agentExecutor: suspend (AgentType, String, String) -> String
    ): String {
        return when (node.type) {
            NodeType.INPUT -> input
            NodeType.AGENT -> {
                val agentType = node.agentType ?: AgentType.Hermes
                val prompt = if (node.prompt.isNotBlank()) {
                    node.prompt.replace("{input}", input)
                } else {
                    input
                }
                agentExecutor(agentType, prompt, input)
            }
            NodeType.TRANSFORM -> applyTransform(node.transformType, input, node.prompt)
            NodeType.OUTPUT -> input
        }
    }

    private fun applyTransform(type: TransformType, input: String, extra: String): String {
        return when (type) {
            TransformType.PASSTHROUGH -> input
            TransformType.EXTRACT -> {
                // Simple regex extraction
                val regex = try { Regex(extra) } catch (_: Exception) { Regex("(.+)") }
                regex.find(input)?.groupValues?.getOrNull(1) ?: input
            }
            TransformType.TO_UPPERCASE -> input.uppercase()
            TransformType.TO_LOWERCASE -> input.lowercase()
            TransformType.TRIM -> input.trim()
            TransformType.PREFIX -> "$extra$input"
            TransformType.SUFFIX -> "$input$extra"
            TransformType.JSON_EXTRACT -> {
                try {
                    val json = org.json.JSONObject(input)
                    json.optString(extra, input)
                } catch (_: Exception) { input }
            }
        }
    }

    fun reset() {
        _executionState.value = WorkflowExecutionState()
    }
}

/**
 * 预置工作流模板
 */
object WorkflowTemplates {

    fun translationChain(): Workflow {
        val input = WorkflowNode(
            id = "input",
            type = NodeType.INPUT,
            label = "Input Text",
            positionX = 50f, positionY = 200f
        )
        val translate = WorkflowNode(
            id = "translate",
            type = NodeType.AGENT,
            label = "Translate",
            agentType = AgentType.OpenAI,
            prompt = "Translate the following text to the target language. Preserve meaning and tone:\n\n{input}",
            positionX = 250f, positionY = 200f
        )
        val review = WorkflowNode(
            id = "review",
            type = NodeType.AGENT,
            label = "Review & Polish",
            agentType = AgentType.OpenAI,
            prompt = "Review this translation for accuracy and natural flow. Fix any issues:\n\n{input}",
            positionX = 450f, positionY = 200f
        )
        val output = WorkflowNode(
            id = "output",
            type = NodeType.OUTPUT,
            label = "Final Translation",
            positionX = 650f, positionY = 200f
        )

        return Workflow(
            name = "Translation Chain",
            description = "Translate → Review & Polish",
            nodes = listOf(input, translate, review, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "input", toNodeId = "translate"),
                WorkflowEdge(fromNodeId = "translate", toNodeId = "review"),
                WorkflowEdge(fromNodeId = "review", toNodeId = "output")
            )
        )
    }

    fun codeReview(): Workflow {
        val input = WorkflowNode(
            id = "input",
            type = NodeType.INPUT,
            label = "Code Input",
            positionX = 50f, positionY = 200f
        )
        val analyze = WorkflowNode(
            id = "analyze",
            type = NodeType.AGENT,
            label = "Analyze Code",
            agentType = AgentType.OpenCode,
            prompt = "Analyze the following code for potential issues, bugs, and improvements. Provide a structured analysis:\n\n{input}",
            positionX = 250f, positionY = 100f
        )
        val suggest = WorkflowNode(
            id = "suggest",
            type = NodeType.AGENT,
            label = "Generate Suggestions",
            agentType = AgentType.OpenCode,
            prompt = "Based on this code analysis, provide specific improvement suggestions with code examples:\n\n{input}",
            positionX = 250f, positionY = 300f
        )
        val output = WorkflowNode(
            id = "output",
            type = NodeType.OUTPUT,
            label = "Review Report",
            positionX = 500f, positionY = 200f
        )

        return Workflow(
            name = "Code Review",
            description = "Analyze → Suggest Improvements",
            nodes = listOf(input, analyze, suggest, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "input", toNodeId = "analyze"),
                WorkflowEdge(fromNodeId = "input", toNodeId = "suggest"),
                WorkflowEdge(fromNodeId = "analyze", toNodeId = "output"),
                WorkflowEdge(fromNodeId = "suggest", toNodeId = "output")
            )
        )
    }

    fun researchAssistant(): Workflow {
        val input = WorkflowNode(
            id = "input",
            type = NodeType.INPUT,
            label = "Research Topic",
            positionX = 50f, positionY = 200f
        )
        val search = WorkflowNode(
            id = "search",
            type = NodeType.AGENT,
            label = "Search & Gather",
            agentType = AgentType.OpenAI,
            prompt = "Research the following topic. Provide key facts, data points, and relevant information:\n\n{input}",
            positionX = 250f, positionY = 200f
        )
        val extract = WorkflowNode(
            id = "extract",
            type = NodeType.TRANSFORM,
            label = "Extract Key Points",
            transformType = TransformType.PASSTHROUGH,
            positionX = 450f, positionY = 100f
        )
        val summarize = WorkflowNode(
            id = "summarize",
            type = NodeType.AGENT,
            label = "Summarize",
            agentType = AgentType.OpenAI,
            prompt = "Create a concise, well-structured summary of this research:\n\n{input}",
            positionX = 450f, positionY = 300f
        )
        val output = WorkflowNode(
            id = "output",
            type = NodeType.OUTPUT,
            label = "Research Report",
            positionX = 650f, positionY = 200f
        )

        return Workflow(
            name = "Research Assistant",
            description = "Search → Summarize",
            nodes = listOf(input, search, extract, summarize, output),
            edges = listOf(
                WorkflowEdge(fromNodeId = "input", toNodeId = "search"),
                WorkflowEdge(fromNodeId = "search", toNodeId = "extract"),
                WorkflowEdge(fromNodeId = "search", toNodeId = "summarize"),
                WorkflowEdge(fromNodeId = "extract", toNodeId = "output"),
                WorkflowEdge(fromNodeId = "summarize", toNodeId = "output")
            )
        )
    }

    fun allTemplates(): List<Workflow> = listOf(
        translationChain(),
        codeReview(),
        researchAssistant()
    )
}
