import Foundation

// MARK: - Workflow Model
// 对应 protocol/schemas/workflow-schema.json

/// 工作流节点类型
enum NodeType: String, Codable {
    case input = "INPUT"
    case agent = "AGENT"
    case transform = "TRANSFORM"
    case output = "OUTPUT"
}

/// 变换类型
enum TransformType: String, Codable {
    case passthrough = "PASSTHROUGH"
    case extract = "EXTRACT"
    case toUppercase = "TO_UPPERCASE"
    case toLowercase = "TO_LOWERCASE"
    case trim = "TRIM"
    case prefix = "PREFIX"
    case suffix = "SUFFIX"
    case jsonExtract = "JSON_EXTRACT"
}

/// 工作流节点
struct WorkflowNode: Codable, Identifiable, Equatable, Sendable {
    var id: String = UUID().uuidString
    var type: NodeType
    var label: String = ""
    var agentType: AgentType? = nil
    var prompt: String = ""
    var transformType: TransformType = .passthrough
    var positionX: Float = 0
    var positionY: Float = 0
    var outputCache: String = ""
}

/// 工作流边
struct WorkflowEdge: Codable, Identifiable, Equatable, Sendable {
    var id: String = UUID().uuidString
    var fromNodeId: String
    var toNodeId: String
    var condition: String? = nil
}

/// 工作流 DAG
struct Workflow: Codable, Identifiable, Equatable, Sendable {
    var id: String = UUID().uuidString
    var name: String
    var description: String = ""
    var nodes: [WorkflowNode] = []
    var edges: [WorkflowEdge] = []
}

/// 工作流执行状态
struct WorkflowExecutionState: Equatable, Sendable {
    var isRunning: Bool = false
    var currentNodeId: String? = nil
    var completedNodeIds: Set<String> = []
    var output: String = ""
    var error: String? = nil
    var logs: [String] = []
}
