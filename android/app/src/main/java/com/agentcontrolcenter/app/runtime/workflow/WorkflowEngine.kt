package com.agentcontrolcenter.app.runtime.workflow

import com.agentcontrolcenter.app.agent.model.AgentConfig
import com.agentcontrolcenter.app.agent.model.AgentType
import com.agentcontrolcenter.app.core.database.dao.AgentConfigDao
import com.agentcontrolcenter.app.core.database.entity.AgentConfigEntity
import com.agentcontrolcenter.app.core.featureflag.FeatureFlagManager
import com.agentcontrolcenter.app.transport.TransportFactory
import com.agentcontrolcenter.app.transport.protocol.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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

/**
 * Agent 编排工作流引擎
 *
 * 支持多节点 DAG 执行，每个节点可以是：
 * - INPUT: 接收外部输入
 * - AGENT: 调用 Agent（本地或远程）
 * - TRANSFORM: 数据转换（提取、格式化、过滤）
 * - OUTPUT: 输出最终结果
 *
 * Phase 4.1: 从普通 class 改为 [@Singleton]，通过 Hilt 注入
 * [TransportFactory] 和 [AgentConfigDao]。AGENT 节点不再使用 mock executor，
 * 而是查询数据库中匹配 [AgentType] 的第一个 [AgentConfigEntity]，创建临时
 * [com.agentcontrolcenter.app.transport.protocol.AgentTransport]，connect → sendMessage →
 * 收集 events 直到 [AgentEvent.StreamComplete] → shutdown → 返回拼接结果。
 */

@Singleton
class WorkflowEngine @Inject constructor(
    private val transportFactory: TransportFactory,
    private val agentConfigDao: AgentConfigDao,
    private val featureFlagManager: FeatureFlagManager
) {

    private val _executionState = MutableStateFlow(WorkflowExecutionState())
    val executionState: StateFlow<WorkflowExecutionState> = _executionState.asStateFlow()

    /**
     * 执行工作流
     *
     * Phase 4.1: 移除 agentExecutor 参数。AGENT 节点现在通过注入的
     * [transportFactory] 和 [agentConfigDao] 调用真实 Agent。
     * 若需测试时替换执行逻辑，可通过 Hilt 注入 mock [TransportFactory]。
     */
    suspend fun execute(
        workflow: Workflow,
        input: String
    ): String = withContext(Dispatchers.IO) {
        _executionState.value = WorkflowExecutionState(isRunning = true)

        try {
            // 痛点6 修复：工作流引擎受 WORKFLOW_ENGINE FeatureFlag 控制。
            // flag 关闭时拒绝执行并上报错误（check 抛 IllegalStateException，
            // 与既有 No INPUT node / cycle detected 一致）。
            check(featureFlagManager.isEnabled(FeatureFlagManager.FeatureFlag.WORKFLOW_ENGINE)) {
                "Workflow engine is disabled by feature flag"
            }

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

                val nodeOutput = executeNode(node, nodeInput)
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
        } catch (e: CancellationException) {
            // 协程取消必须传播，绝不能被下面 catch (e: Exception) 吞掉，
            // 否则 execute 会把取消当成业务错误返回 "Error: ..." 字符串，
            // 破坏结构化并发（调用方永远等不到真正的取消信号）。
            _executionState.value = _executionState.value.copy(isRunning = false)
            throw e
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
        input: String
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
                executeAgent(agentType, prompt)
            }
            NodeType.TRANSFORM -> applyTransform(node.transformType, input, node.prompt)
            NodeType.OUTPUT -> input
        }
    }

    /**
     * Phase 4.1: 通过真实 Transport 执行 Agent 调用。
     *
     * 1. 查询数据库中匹配 [agentType] 的第一个 [AgentConfigEntity]
     * 2. 创建临时 [com.agentcontrolcenter.app.transport.protocol.AgentTransport]
     * 3. connect → sendMessage → 收集 events 直到 [AgentEvent.StreamComplete]
     * 4. 拼接所有 delta 为完整响应
     * 5. shutdown 释放资源
     *
     * 若无匹配配置或超时（60s），返回错误字符串而非抛异常，
     * 保证工作流后续节点能继续执行。
     */
    private suspend fun executeAgent(agentType: AgentType, prompt: String): String {
        val configEntity = agentConfigDao.getAllConfigsOnce()
            .firstOrNull { entity ->
                try { AgentType.valueOf(entity.type) == agentType }
                catch (_: Exception) { false }
            }
            ?: return "Error: No agent config found for type ${agentType.displayName}"

        val config = configEntity.toAgentConfig()
        val transport = transportFactory.create(agentType)

        return try {
            transport.connect(config)
            // 使用唯一 sessionId 避免与主会话冲突
            val sessionId = "workflow_${UUID.randomUUID()}"
            transport.sendMessage(sessionId, prompt)

            val responseBuilder = StringBuilder()
            // 60s 超时保护，避免工作流无限挂起
            withTimeoutOrNull(60_000L) {
                try {
                    transport.events.collect { event ->
                        when (event) {
                            // M-16: 区分 delta 与 non-delta。
                            // - delta（isDelta=true）：增量片段，append 到累加器
                            // - non-delta（isDelta=false）：完整消息，setLength(0) 后覆盖，
                            //   避免此前对完整消息也 append 导致与 delta 累加重复（同一内容被记录两次）
                            is AgentEvent.MessageReceived -> {
                                if (event.isDelta) {
                                    responseBuilder.append(event.content)
                                } else {
                                    responseBuilder.setLength(0)
                                    responseBuilder.append(event.content)
                                }
                            }
                            is AgentEvent.Error -> throw FlowAbortException
                            is AgentEvent.StreamComplete -> throw FlowAbortException
                            else -> { /* Connected/Disconnected/Reconnecting 忽略 */ }
                        }
                    }
                } catch (_: FlowAbortException) {
                    // 正常结束：收到 StreamComplete 或 Error，停止收集
                }
            }

            responseBuilder.toString().ifBlank {
                "Error: Agent ${agentType.displayName} returned empty response"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message}"
        } finally {
            transport.shutdown()
        }
    }

    /** 将 [AgentConfigEntity] 转换为 [AgentConfig]，解密 apiKey。 */
    private fun AgentConfigEntity.toAgentConfig() = AgentConfig(
        id = id,
        name = name,
        type = try { AgentType.valueOf(type) } catch (_: Exception) { AgentType.Hermes },
        serverUrl = serverUrl,
        apiKey = com.agentcontrolcenter.app.core.security.KeystoreManager.decryptOrRaw(apiKey),
        model = model,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        protocolType = com.agentcontrolcenter.app.agent.model.AgentProtocol.fromRawValue(protocolType)
    )

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
                // L-8: 统一使用 Gson 解析 JSON，避免与项目其余部分（TypeConverter /
                // OpenAIHttpTransport / WebSocketTransport 均用 Gson）混用 org.json，
                // 减少依赖体积并保持解析行为一致。
                try {
                    val json = com.google.gson.JsonParser.parseString(input).asJsonObject
                    if (json.has(extra)) {
                        // asString 会对字符串自动去除外层引号；非字符串字段则返回其文本表示
                        json.get(extra).let { elem ->
                            if (elem.isJsonPrimitive) elem.asString else elem.toString()
                        }
                    } else {
                        input
                    }
                } catch (_: Exception) { input }
            }
        }
    }

    fun reset() {
        _executionState.value = WorkflowExecutionState()
    }
}

/** 用于在收到 [AgentEvent.StreamComplete] 或 [AgentEvent.Error] 时中断 Flow 收集。 */
private object FlowAbortException : Exception()

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
