import XCTest
@testable import AgentControlCenter

// MARK: - Workflow 模型单元测试
final class WorkflowModelTests: XCTestCase {

    // MARK: - NodeType 测试

    /// 测试 NodeType rawValue（未遵循 CaseIterable，手动验证 4 个 case）
    func testNodeTypeCount() {
        let allTypes: [NodeType] = [.input, .agent, .transform, .output]
        XCTAssertEqual(allTypes.count, 4, "NodeType 应包含 4 个枚举值")
    }

    /// 测试 NodeType rawValue
    func testNodeTypeRawValues() {
        XCTAssertEqual(NodeType.input.rawValue, "INPUT")
        XCTAssertEqual(NodeType.agent.rawValue, "AGENT")
        XCTAssertEqual(NodeType.transform.rawValue, "TRANSFORM")
        XCTAssertEqual(NodeType.output.rawValue, "OUTPUT")
    }

    // MARK: - TransformType 测试

    /// 测试 TransformType 数量（未遵循 CaseIterable，手动验证 8 个 case）
    func testTransformTypeCount() {
        let allTypes: [TransformType] = [
            .passthrough, .extract, .toUppercase, .toLowercase,
            .trim, .prefix, .suffix, .jsonExtract
        ]
        XCTAssertEqual(allTypes.count, 8, "TransformType 应包含 8 个枚举值")
    }

    /// 测试 TransformType rawValue
    func testTransformTypeRawValues() {
        XCTAssertEqual(TransformType.passthrough.rawValue, "PASSTHROUGH")
        XCTAssertEqual(TransformType.extract.rawValue, "EXTRACT")
        XCTAssertEqual(TransformType.toUppercase.rawValue, "TO_UPPERCASE")
        XCTAssertEqual(TransformType.toLowercase.rawValue, "TO_LOWERCASE")
        XCTAssertEqual(TransformType.trim.rawValue, "TRIM")
        XCTAssertEqual(TransformType.prefix.rawValue, "PREFIX")
        XCTAssertEqual(TransformType.suffix.rawValue, "SUFFIX")
        XCTAssertEqual(TransformType.jsonExtract.rawValue, "JSON_EXTRACT")
    }

    // MARK: - WorkflowNode 测试

    /// 测试 WorkflowNode 默认创建
    func testWorkflowNodeCreation() {
        let node = WorkflowNode(type: .input)
        XCTAssertFalse(node.id.isEmpty, "WorkflowNode id 应自动生成")
        XCTAssertEqual(node.type, .input)
        XCTAssertEqual(node.label, "")
        XCTAssertNil(node.agentType)
        XCTAssertEqual(node.prompt, "")
        XCTAssertEqual(node.transformType, .passthrough)
        XCTAssertEqual(node.positionX, 0)
        XCTAssertEqual(node.positionY, 0)
        XCTAssertEqual(node.outputCache, "")
    }

    /// 测试 WorkflowNode 自定义创建
    func testWorkflowNodeCustomCreation() {
        let node = WorkflowNode(
            id: "node-1",
            type: .agent,
            label: "AI 节点",
            agentType: .hermes,
            prompt: "请分析以下内容",
            transformType: .passthrough,
            positionX: 100.0,
            positionY: 200.0,
            outputCache: "缓存结果"
        )
        XCTAssertEqual(node.id, "node-1")
        XCTAssertEqual(node.type, .agent)
        XCTAssertEqual(node.label, "AI 节点")
        XCTAssertEqual(node.agentType, .hermes)
        XCTAssertEqual(node.prompt, "请分析以下内容")
        XCTAssertEqual(node.transformType, .passthrough)
        XCTAssertEqual(node.positionX, 100.0)
        XCTAssertEqual(node.positionY, 200.0)
        XCTAssertEqual(node.outputCache, "缓存结果")
    }

    /// 测试 WorkflowNode transform 类型节点创建
    func testWorkflowNodeTransformCreation() {
        let node = WorkflowNode(
            type: .transform,
            label: "大写转换",
            transformType: .toUppercase
        )
        XCTAssertEqual(node.type, .transform)
        XCTAssertEqual(node.transformType, .toUppercase)
        XCTAssertEqual(node.label, "大写转换")
    }

    // MARK: - WorkflowEdge 测试

    /// 测试 WorkflowEdge 默认创建
    func testWorkflowEdgeCreation() {
        let edge = WorkflowEdge(fromNodeId: "n1", toNodeId: "n2")
        XCTAssertFalse(edge.id.isEmpty, "WorkflowEdge id 应自动生成")
        XCTAssertEqual(edge.fromNodeId, "n1")
        XCTAssertEqual(edge.toNodeId, "n2")
        XCTAssertNil(edge.condition)
    }

    /// 测试 WorkflowEdge 自定义创建（含 condition）
    func testWorkflowEdgeWithCondition() {
        let edge = WorkflowEdge(
            id: "edge-1",
            fromNodeId: "node-a",
            toNodeId: "node-b",
            condition: "result.success == true"
        )
        XCTAssertEqual(edge.id, "edge-1")
        XCTAssertEqual(edge.fromNodeId, "node-a")
        XCTAssertEqual(edge.toNodeId, "node-b")
        XCTAssertEqual(edge.condition, "result.success == true")
    }

    // MARK: - Workflow 测试

    /// 测试 Workflow 创建（含 nodes + edges）
    func testWorkflowCreation() {
        let node1 = WorkflowNode(id: "wfn-1", type: .input, label: "输入")
        let node2 = WorkflowNode(id: "wfn-2", type: .agent, label: "处理", agentType: .openAI)
        let node3 = WorkflowNode(id: "wfn-3", type: .output, label: "输出")

        let edge1 = WorkflowEdge(id: "wfe-1", fromNodeId: "wfn-1", toNodeId: "wfn-2")
        let edge2 = WorkflowEdge(id: "wfe-2", fromNodeId: "wfn-2", toNodeId: "wfn-3")

        let workflow = Workflow(
            name: "测试工作流",
            description: "一个简单的测试工作流",
            nodes: [node1, node2, node3],
            edges: [edge1, edge2]
        )

        XCTAssertFalse(workflow.id.isEmpty)
        XCTAssertEqual(workflow.name, "测试工作流")
        XCTAssertEqual(workflow.description, "一个简单的测试工作流")
        XCTAssertEqual(workflow.nodes.count, 3)
        XCTAssertEqual(workflow.edges.count, 2)
        XCTAssertEqual(workflow.nodes[1].agentType, .openAI)
    }

    /// 测试 Workflow 默认值
    func testWorkflowDefaultValues() {
        let workflow = Workflow(name: "空工作流")
        XCTAssertFalse(workflow.id.isEmpty)
        XCTAssertEqual(workflow.name, "空工作流")
        XCTAssertEqual(workflow.description, "")
        XCTAssertTrue(workflow.nodes.isEmpty)
        XCTAssertTrue(workflow.edges.isEmpty)
    }

    // MARK: - Workflow Codable 测试

    /// 测试 WorkflowNode Codable 编解码
    func testWorkflowNodeCodable() {
        let node = WorkflowNode(
            id: "node-enc",
            type: .transform,
            label: "提取节点",
            transformType: .extract,
            positionX: 50.0,
            positionY: 75.0
        )

        let data = try? JSONEncoder().encode(node)
        XCTAssertNotNil(data)

        let decoded = try? JSONDecoder().decode(WorkflowNode.self, from: data!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded, node, "编码后解码的 WorkflowNode 应与原始值相等")
    }

    /// 测试 WorkflowEdge Codable 编解码
    func testWorkflowEdgeCodable() {
        let edge = WorkflowEdge(
            id: "edge-enc",
            fromNodeId: "from-1",
            toNodeId: "to-1",
            condition: "x > 10"
        )

        let data = try? JSONEncoder().encode(edge)
        XCTAssertNotNil(data)

        let decoded = try? JSONDecoder().decode(WorkflowEdge.self, from: data!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded, edge, "编码后解码的 WorkflowEdge 应与原始值相等")
    }

    /// 测试 Workflow 完整 Codable 编解码
    func testWorkflowCodable() {
        let nodes = [
            WorkflowNode(id: "wn-1", type: .input, label: "输入"),
            WorkflowNode(id: "wn-2", type: .agent, label: "Agent", agentType: .hermes),
            WorkflowNode(id: "wn-3", type: .transform, label: "转换", transformType: .toUppercase),
            WorkflowNode(id: "wn-4", type: .output, label: "输出")
        ]
        let edges = [
            WorkflowEdge(id: "we-1", fromNodeId: "wn-1", toNodeId: "wn-2"),
            WorkflowEdge(id: "we-2", fromNodeId: "wn-2", toNodeId: "wn-3"),
            WorkflowEdge(id: "we-3", fromNodeId: "wn-3", toNodeId: "wn-4")
        ]
        let workflow = Workflow(
            name: "完整工作流",
            description: "包含 4 节点和 3 条边",
            nodes: nodes,
            edges: edges
        )

        let data = try? JSONEncoder().encode(workflow)
        XCTAssertNotNil(data, "Workflow 编码不应返回 nil")

        let decoded = try? JSONDecoder().decode(Workflow.self, from: data!)
        XCTAssertNotNil(decoded, "Workflow 解码不应返回 nil")
        XCTAssertEqual(decoded?.name, "完整工作流")
        XCTAssertEqual(decoded?.description, "包含 4 节点和 3 条边")
        XCTAssertEqual(decoded?.nodes.count, 4)
        XCTAssertEqual(decoded?.edges.count, 3)
        XCTAssertEqual(decoded, workflow, "编码后解码的 Workflow 应与原始值相等")
    }

    // MARK: - WorkflowExecutionState 测试

    /// 测试 WorkflowExecutionState 默认值
    func testWorkflowExecutionStateDefaults() {
        let state = WorkflowExecutionState()
        XCTAssertFalse(state.isRunning)
        XCTAssertNil(state.currentNodeId)
        XCTAssertTrue(state.completedNodeIds.isEmpty)
        XCTAssertEqual(state.output, "")
        XCTAssertNil(state.error)
        XCTAssertTrue(state.logs.isEmpty)
    }
}