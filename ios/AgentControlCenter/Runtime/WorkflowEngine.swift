import Foundation
import Observation

// MARK: - FlowAbortError
// 对应 Android FlowAbortException

/// 流程中断错误 — 用于在收到 StreamComplete 或 Error 事件时
/// 中断 AsyncStream 的事件收集循环。
///
/// 对应 Android 端的 `private object FlowAbortException : Exception()`，
/// 在 Kotlin 中通过 throw FlowAbortException 跳出 Flow.collect，
/// 在 Swift 中通过 throw FlowAbortError 跳出 for await 循环。
private struct FlowAbortError: Error, Sendable {
    let message: String
}

// MARK: - AgentOutcome

/// Agent 调用结果（用于 TaskGroup 竞争超时）
private enum AgentOutcome: Sendable {
    case completed(String)
    case timedOut
}

// MARK: - WorkflowEngine
// 对应 Android WorkflowEngine (Phase 4.1)

/// Agent 编排工作流引擎 — 执行多节点 DAG 工作流。
///
/// 支持的节点类型：
/// - INPUT: 接收外部输入
/// - AGENT: 调用 Agent（通过 TransportFactory 创建传输层，连接并发送消息）
/// - TRANSFORM: 数据转换（PASSTHROUGH / EXTRACT / TO_UPPERCASE 等）
/// - OUTPUT: 输出最终结果
///
/// 执行流程：
/// 1. 对工作流节点进行拓扑排序（Kahn 算法），检测循环依赖
/// 2. 按拓扑顺序逐个执行节点
/// 3. AGENT 节点：替换 {input} 占位符 → 连接 Agent → 发送消息 → 收集流式事件 → 60s 超时
/// 4. TRANSFORM 节点：对上游输出应用转换函数
/// 5. 单个节点出错不影响后续节点，返回 "Error: ..." 字符串继续执行
///
/// Phase 4.1 说明：
/// - Android 版通过 Hilt 注入 TransportFactory 和 AgentConfigDao
/// - iOS 版使用 TransportFactory.create(agentType) 创建传输层
/// - AgentConfigDao 尚未实现，当前使用默认配置占位
@Observable
final class WorkflowEngine {

    /// 工作流执行状态（响应式，UI 可观察）
    private(set) var executionState = WorkflowExecutionState()

    /// Agent 调用超时时间（秒），对应 Android 的 withTimeoutOrNull(60_000L)
    private let agentTimeoutSeconds: TimeInterval = 60

    // MARK: - Public

    /// 执行工作流
    /// - Parameters:
    ///   - workflow: 工作流定义（DAG）
    ///   - input: 输入文本
    /// - Returns: 工作流输出结果，出错时返回 "Error: ..." 字符串
    func execute(workflow: Workflow, input: String) async -> String {
        // 重置执行状态
        executionState = WorkflowExecutionState(isRunning: true)
        log("开始执行工作流: \(workflow.name)")

        // 查找 INPUT 节点
        guard let inputNode = workflow.nodes.first(where: { $0.type == .input }) else {
            executionState.isRunning = false
            executionState.error = "工作流缺少 INPUT 节点"
            log("Error: 工作流缺少 INPUT 节点")
            return "Error: 工作流缺少 INPUT 节点"
        }

        // 拓扑排序（检测循环依赖）
        guard let sortedNodes = topologicalSort(workflow) else {
            executionState.isRunning = false
            executionState.error = "工作流包含循环依赖"
            log("Error: 工作流包含循环依赖")
            return "Error: 工作流包含循环依赖"
        }

        log("拓扑排序完成，共 \(sortedNodes.count) 个节点")

        // 节点输出映射: nodeId -> output
        var nodeOutputs: [String: String] = [:]
        nodeOutputs[inputNode.id] = input

        // 按拓扑顺序执行节点
        for node in sortedNodes {
            executionState.currentNodeId = node.id
            let nodeLabel = node.label.isEmpty ? node.id : node.label
            log("[\(node.type.rawValue)] \(nodeLabel): 开始执行")

            // 获取上游节点的输出作为当前节点的输入
            let inputForNode: String
            if node.type == .input {
                inputForNode = input
            } else {
                inputForNode = getInputForNode(node, workflow: workflow, nodeOutputs: nodeOutputs)
            }

            // 执行节点
            let output: String
            switch node.type {
            case .input:
                // 输入节点：直接使用工作流输入
                output = input

            case .agent:
                // Agent 节点：替换 {input} 占位符后调用 Agent
                let agentType = node.agentType ?? .hermes
                let prompt = node.prompt.isEmpty
                    ? inputForNode
                    : node.prompt.replacingOccurrences(of: "{input}", with: inputForNode)
                log("调用 Agent(\(agentType.displayName))，提示词长度: \(prompt.count)")
                output = await executeAgent(agentType: agentType, prompt: prompt)

            case .transform:
                // 转换节点：对上游输出应用转换函数
                output = applyTransform(node.transformType, input: inputForNode, extra: node.prompt)
                log("应用转换: \(node.transformType.rawValue)")

            case .output:
                // 输出节点：透传上游输出
                output = inputForNode
                executionState.output = output
            }

            // 保存节点输出
            nodeOutputs[node.id] = output
            executionState.completedNodeIds.insert(node.id)

            // 记录日志（截取前 100 字符预览）
            let preview = output.prefix(100)
            log("[\(node.type.rawValue)] \(nodeLabel): \(preview)")
        }

        executionState.isRunning = false
        executionState.currentNodeId = nil
        log("工作流执行完成")

        // 优先返回 OUTPUT 节点的结果
        if let outputNode = workflow.nodes.first(where: { $0.type == .output }),
           let output = nodeOutputs[outputNode.id] {
            return output
        }
        // 没有 OUTPUT 节点时，返回最后一个节点的输出
        return sortedNodes.last.flatMap { nodeOutputs[$0.id] } ?? ""
    }

    /// 重置执行状态
    func reset() {
        executionState = WorkflowExecutionState()
    }

    // MARK: - Topological Sort

    /// 拓扑排序 — 使用 Kahn 算法（BFS）
    ///
    /// 算法步骤：
    /// 1. 统计每个节点的入度（ incoming edge 数量）
    /// 2. 将入度为 0 的节点入队
    /// 3. 出队一个节点，将其下游节点的入度减 1
    /// 4. 若下游节点入度变为 0，则入队
    /// 5. 重复直到队列为空
    /// 6. 若结果数量不等于节点总数，说明存在环
    ///
    /// - Parameter workflow: 工作流定义
    /// - Returns: 排序后的节点列表，存在环时返回 nil
    private func topologicalSort(_ workflow: Workflow) -> [WorkflowNode]? {
        // 入度表: nodeId -> 入度
        var inDegree: [String: Int] = [:]
        // 邻接表: nodeId -> 下游节点 ID 列表
        var adjacency: [String: [String]] = [:]

        // 初始化
        for node in workflow.nodes {
            inDegree[node.id] = 0
            adjacency[node.id] = []
        }

        // 构建图
        for edge in workflow.edges {
            adjacency[edge.fromNodeId]?.append(edge.toNodeId)
            inDegree[edge.toNodeId, default: 0] += 1
        }

        // 入度为 0 的节点入队（BFS 起点）
        var queue = workflow.nodes
            .filter { inDegree[$0.id] == 0 }
            .map { $0.id }

        var result: [WorkflowNode] = []

        // BFS 处理
        while !queue.isEmpty {
            let nodeId = queue.removeFirst()
            if let node = workflow.nodes.first(where: { $0.id == nodeId }) {
                result.append(node)
            }
            // 遍历下游节点
            for neighbor in adjacency[nodeId] ?? [] {
                inDegree[neighbor, default: 0] -= 1
                if inDegree[neighbor] == 0 {
                    queue.append(neighbor)
                }
            }
        }

        // 结果数量不等于节点总数 → 存在环
        if result.count != workflow.nodes.count {
            return nil
        }

        return result
    }

    // MARK: - Node Input

    /// 获取节点的输入（来自上游节点的输出）
    ///
    /// 查找所有指向当前节点的边，取上游节点的输出拼接。
    /// 多个上游输出用双换行分隔。
    ///
    /// - Parameters:
    ///   - node: 当前节点
    ///   - workflow: 工作流定义
    ///   - nodeOutputs: 已完成节点的输出映射
    /// - Returns: 拼接后的输入文本
    private func getInputForNode(
        _ node: WorkflowNode,
        workflow: Workflow,
        nodeOutputs: [String: String]
    ) -> String {
        // 找到所有指向当前节点的边
        let upstreamIds = workflow.edges
            .filter { $0.toNodeId == node.id }
            .map { $0.fromNodeId }

        // 获取上游节点的输出
        let upstreamOutputs = upstreamIds.compactMap { nodeOutputs[$0] }

        // 多个上游输出用双换行拼接
        return upstreamOutputs.joined(separator: "\n\n")
    }

    // MARK: - Agent Execution

    /// 执行 Agent 调用
    ///
    /// 完整流程：
    /// 1. 通过 TransportFactory 创建传输层
    /// 2. 使用默认 AgentConfig 建立连接（AgentConfigDao 尚未实现）
    /// 3. 发送提示词（使用唯一 sessionId 避免与主会话冲突）
    /// 4. 收集流式事件直到 StreamComplete（60s 超时保护）
    /// 5. 关闭连接并返回结果
    ///
    /// 事件处理：
    /// - MessageReceived: 累积文本片段
    /// - StreamComplete: 抛出 FlowAbortError 中断收集（正常完成）
    /// - Error: 抛出 FlowAbortError 中断收集（异常完成）
    /// - Connected/Disconnected/Reconnecting: 忽略
    ///
    /// 超时处理：
    /// 使用 withTaskGroup 竞争事件收集和超时计时器，
    /// 先完成者胜出。超时后关闭 transport 终止事件流。
    ///
    /// - Parameters:
    ///   - agentType: Agent 类型
    ///   - prompt: 提示词
    /// - Returns: Agent 输出文本，出错时返回 "Error: ..." 字符串
    private func executeAgent(agentType: AgentType, prompt: String) async -> String {
        // 使用默认配置（AgentConfigDao 尚未实现）
        let config = Self.defaultConfig(for: agentType)
        let transport = TransportFactory.create(agentType)

        // 1. 建立连接
        await transport.connect(config: config, e2eKey: nil)

        // 2. 发送消息（使用唯一 sessionId 避免与主会话冲突）
        let sessionId = "workflow_\(UUID().uuidString)"
        do {
            try await transport.sendMessage(sessionId: sessionId, content: prompt)
        } catch {
            transport.shutdown()
            return "Error: 发送消息失败 - \(error.localizedDescription)"
        }

        // 3. 收集事件（60s 超时竞争）
        let outcome: AgentOutcome = await withTaskGroup(of: AgentOutcome.self) { group in
            // 事件收集任务
            group.addTask {
                var collected = ""
                do {
                    for await event in transport.events {
                        // 检查任务是否被取消（超时后会被 cancel）
                        if Task.isCancelled { break }
                        switch event {
                        case .messageReceived(let content, _):
                            // 累积文本片段
                            collected += content
                        case .error:
                            // 收到错误事件 → 中断收集
                            throw FlowAbortError(message: "Agent 返回错误事件")
                        case .streamComplete:
                            // 流式输出完成 → 中断收集
                            throw FlowAbortError(message: "流式输出完成")
                        default:
                            // connected / disconnected / reconnecting 事件忽略
                            break
                        }
                    }
                } catch is FlowAbortError {
                    // FlowAbortError 是正常的中断信号，不需要向上传播
                    // collected 中已累积所有文本片段
                }
                return .completed(collected)
            }

            // 超时任务
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(self.agentTimeoutSeconds * 1_000_000_000))
                return .timedOut
            }

            // 等待第一个完成
            let first = await group.next()!

            // 关闭 transport 以终止事件流，使另一个任务尽快完成
            transport.shutdown()
            // 取消剩余任务
            group.cancelAll()

            return first
        }

        // 4. 返回结果
        switch outcome {
        case .completed(let text):
            if text.isEmpty {
                return "Error: Agent \(agentType.displayName) 返回空响应"
            }
            return text
        case .timedOut:
            return "Error: Agent \(agentType.displayName) 超时（\(Int(agentTimeoutSeconds))s）"
        }
    }

    // MARK: - Transform

    /// 应用转换函数
    ///
    /// 对应 Android 的 applyTransform(type, input, extra)，
    /// 其中 extra 来自节点的 prompt 字段。
    ///
    /// - Parameters:
    ///   - type: 转换类型
    ///   - input: 输入文本
    ///   - extra: 附加参数（正则 / 前缀 / 后缀 / JSON 字段名）
    /// - Returns: 转换后的文本
    private func applyTransform(_ type: TransformType, input: String, extra: String) -> String {
        switch type {
        case .passthrough:
            // 直接透传
            return input

        case .extract:
            // 正则提取：用 extra 作为正则表达式，提取第一个捕获组
            // extra 为空时默认匹配全部内容
            let pattern = extra.isEmpty ? "(.+)" : extra
            guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]) else {
                return input
            }
            let range = NSRange(input.startIndex..., in: input)
            if let match = regex.firstMatch(in: input, range: range),
               match.numberOfRanges > 1,
               let extractedRange = Range(match.range(at: 1), in: input) {
                return String(input[extractedRange])
            }
            return input

        case .toUppercase:
            // 转大写
            return input.uppercased()

        case .toLowercase:
            // 转小写
            return input.lowercased()

        case .trim:
            // 去除首尾空白字符
            return input.trimmingCharacters(in: .whitespacesAndNewlines)

        case .prefix:
            // 添加前缀
            return extra + input

        case .suffix:
            // 添加后缀
            return input + extra

        case .jsonExtract:
            // JSON 字段提取：用 extra 作为字段名
            guard let data = input.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return input
            }
            if let value = json[extra] {
                if let strValue = value as? String {
                    return strValue
                }
                // 非字符串值转为字符串
                return "\(value)"
            }
            return input
        }
    }

    // MARK: - Logging

    /// 记录执行日志
    /// - Parameter message: 日志消息
    private func log(_ message: String) {
        executionState.logs.append(message)
    }

    // MARK: - Default Config

    /// 为指定 Agent 类型生成默认配置
    ///
    /// AgentConfigDao 尚未实现，此处使用占位配置。
    /// 实际项目中应从持久化存储查询匹配 agentType 的 AgentConfig。
    ///
    /// - Parameter agentType: Agent 类型
    /// - Returns: 默认 AgentConfig
    private static func defaultConfig(for agentType: AgentType) -> AgentConfig {
        AgentConfig(
            id: "default_\(agentType.rawValue)",
            name: agentType.displayName,
            type: agentType,
            serverUrl: "",
            apiKey: "",
            model: "",
            systemPrompt: "",
            temperature: 0.7,
            maxTokens: 4096
        )
    }
}

// MARK: - WorkflowTemplates
// 对应 Android WorkflowTemplates

/// 预置工作流模板 — 提供常用的工作流预设。
///
/// 模板列表：
/// - translationChain: 翻译链（英译中）
/// - codeReview: 代码审查
/// - researchAssistant: 研究助手
enum WorkflowTemplates {

    /// 翻译链：INPUT → AGENT(翻译为英文) → AGENT(翻译为中文) → OUTPUT
    ///
    /// 演示多步骤 Agent 调用：先将输入翻译为英文，再将英文翻译回中文。
    static func translationChain() -> Workflow {
        Workflow(
            id: "translation_chain",
            name: "翻译链",
            description: "翻译为英文 → 翻译为中文",
            nodes: [
                WorkflowNode(
                    id: "input",
                    type: .input,
                    label: "输入文本"
                ),
                WorkflowNode(
                    id: "translate_to_en",
                    type: .agent,
                    label: "翻译为英文",
                    agentType: .openAI,
                    prompt: "请将以下内容翻译为英文，保持原意和语气:\n\n{input}"
                ),
                WorkflowNode(
                    id: "translate_to_zh",
                    type: .agent,
                    label: "翻译为中文",
                    agentType: .openAI,
                    prompt: "请将以下内容翻译为中文，保持原意和语气:\n\n{input}"
                ),
                WorkflowNode(
                    id: "output",
                    type: .output,
                    label: "最终翻译"
                ),
            ],
            edges: [
                WorkflowEdge(fromNodeId: "input", toNodeId: "translate_to_en"),
                WorkflowEdge(fromNodeId: "translate_to_en", toNodeId: "translate_to_zh"),
                WorkflowEdge(fromNodeId: "translate_to_zh", toNodeId: "output"),
            ]
        )
    }

    /// 代码审查：INPUT → AGENT(代码审查) → TRANSFORM(EXTRACT) → OUTPUT
    ///
    /// 演示 Agent 与 Transform 节点的组合：Agent 分析代码，
    /// Transform 提取核心审查意见。
    static func codeReview() -> Workflow {
        Workflow(
            id: "code_review",
            name: "代码审查",
            description: "代码分析 → 提取要点",
            nodes: [
                WorkflowNode(
                    id: "input",
                    type: .input,
                    label: "代码输入"
                ),
                WorkflowNode(
                    id: "review",
                    type: .agent,
                    label: "代码审查",
                    agentType: .openCode,
                    prompt: "请审查以下代码，指出潜在问题、Bug 和改进建议，给出结构化的分析报告:\n\n{input}"
                ),
                WorkflowNode(
                    id: "extract",
                    type: .transform,
                    label: "提取要点",
                    transformType: .extract,
                    prompt: "(.+)"
                ),
                WorkflowNode(
                    id: "output",
                    type: .output,
                    label: "审查报告"
                ),
            ],
            edges: [
                WorkflowEdge(fromNodeId: "input", toNodeId: "review"),
                WorkflowEdge(fromNodeId: "review", toNodeId: "extract"),
                WorkflowEdge(fromNodeId: "extract", toNodeId: "output"),
            ]
        )
    }

    /// 研究助手：INPUT → AGENT(研究) → AGENT(总结) → OUTPUT
    ///
    /// 演示多个 Agent 串联完成复杂任务：先研究收集信息，再总结提炼。
    static func researchAssistant() -> Workflow {
        Workflow(
            id: "research_assistant",
            name: "研究助手",
            description: "研究收集 → 总结提炼",
            nodes: [
                WorkflowNode(
                    id: "input",
                    type: .input,
                    label: "研究主题"
                ),
                WorkflowNode(
                    id: "research",
                    type: .agent,
                    label: "研究收集",
                    agentType: .openAI,
                    prompt: "请针对以下主题进行研究，收集关键事实、数据和相关信息:\n\n{input}"
                ),
                WorkflowNode(
                    id: "summarize",
                    type: .agent,
                    label: "总结提炼",
                    agentType: .openAI,
                    prompt: "请对以下研究内容进行总结，提炼核心要点，给出结构化的摘要:\n\n{input}"
                ),
                WorkflowNode(
                    id: "output",
                    type: .output,
                    label: "研究报告"
                ),
            ],
            edges: [
                WorkflowEdge(fromNodeId: "input", toNodeId: "research"),
                WorkflowEdge(fromNodeId: "research", toNodeId: "summarize"),
                WorkflowEdge(fromNodeId: "summarize", toNodeId: "output"),
            ]
        )
    }

    /// 获取所有模板
    /// - Returns: 所有预置工作流模板
    static func allTemplates() -> [Workflow] {
        [translationChain(), codeReview(), researchAssistant()]
    }
}
