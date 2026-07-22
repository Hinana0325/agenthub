import XCTest
@testable import AgentControlCenter

// MARK: - WorkflowEngine 单元测试
// 验证拓扑排序、转换函数、模板完整性等纯逻辑（不涉及网络/Transport）
@MainActor
final class WorkflowEngineTests: XCTestCase {

    // CI-fix: Swift 6 下 setUp/tearDown 不能收紧 isolation，manager 标 nonisolated(unsafe)
    // 让 nonisolated override 能访问；改用 setUp() async throws + MainActor.run 调用
    // MainActor 隔离的初始化器。super.setUp() 默认 no-op，跳过避免发送 self 跨 actor。
    nonisolated(unsafe) private var engine: WorkflowEngine!

    override func setUp() async throws {
        // 注入 mock TransportFactory，避免模板测试触发真实网络请求
        // M-17 修复：provider 改为 @MainActor 隔离，须通过 MainActor.run 访问
        await MainActor.run {
            TransportFactory.provider = MockTransportFactory()
        }
        // 项1/项2：WorkflowEngine init 新增 featureFlagManager / preferences 参数。
        // 测试场景下使用默认 FeatureFlagManager（所有已发布功能默认开启，gate 不触发）
        // 与默认 preferences（defaultModel 为空，model 兜底不影响现有断言）。
        engine = await MainActor.run {
            WorkflowEngine(
                dataController: DataController(),
                featureFlagManager: FeatureFlagManager(),
                preferences: DefaultAppPreferences()
            )
        }
    }

    override func tearDown() async throws {
        engine = nil
        // M-17 修复：provider 改为 @MainActor 隔离，须通过 MainActor.run 访问
        await MainActor.run {
            TransportFactory.provider = TransportFactory.shared
        }
    }

    // MARK: - 重置

    /// reset() 应将 executionState 恢复到默认值。
    /// 先执行一次工作流让状态沾染数据（logs 非空、completedNodeIds 非空），
    /// 再调用 reset() 验证全部清空。
    func testResetClearsExecutionState() async {
        let workflow = Workflow(
            id: "reset-test",
            name: "重置测试",
            description: "测试",
            nodes: [
                WorkflowNode(id: "input", type: .input, label: "I"),
                WorkflowNode(id: "output", type: .output, label: "O")
            ],
            edges: [WorkflowEdge(fromNodeId: "input", toNodeId: "output")]
        )
        _ = await engine.execute(workflow: workflow, input: "x")
        // 执行后 logs 应非空
        XCTAssertFalse(engine.executionState.logs.isEmpty)
        XCTAssertFalse(engine.executionState.completedNodeIds.isEmpty)

        engine.reset()

        XCTAssertFalse(engine.executionState.isRunning)
        XCTAssertNil(engine.executionState.currentNodeId)
        XCTAssertTrue(engine.executionState.completedNodeIds.isEmpty)
        XCTAssertEqual(engine.executionState.output, "")
        XCTAssertNil(engine.executionState.error)
        XCTAssertTrue(engine.executionState.logs.isEmpty)
    }

    // MARK: - 拓扑排序（通过执行工作流间接验证）

    /// 缺少 INPUT 节点应直接返回错误
    func testExecuteWithoutInputNodeReturnsError() async {
        let workflow = Workflow(
            id: "no-input",
            name: "无输入",
            description: "测试",
            nodes: [
                WorkflowNode(id: "only", type: .output, label: "O")
            ],
            edges: []
        )

        let result = await engine.execute(workflow: workflow, input: "x")
        XCTAssertTrue(result.hasPrefix("Error:"))
        XCTAssertTrue(result.contains("INPUT"))
        XCTAssertNotNil(engine.executionState.error)
        XCTAssertFalse(engine.executionState.isRunning)
    }

    /// 包含循环依赖应返回错误
    func testExecuteWithCycleReturnsError() async {
        let workflow = Workflow(
            id: "cycle",
            name: "循环",
            description: "测试",
            nodes: [
                WorkflowNode(id: "input", type: .input, label: "I"),
                WorkflowNode(id: "a", type: .transform, label: "A"),
                WorkflowNode(id: "b", type: .transform, label: "B"),
                WorkflowNode(id: "output", type: .output, label: "O")
            ],
            edges: [
                WorkflowEdge(fromNodeId: "input", toNodeId: "a"),
                WorkflowEdge(fromNodeId: "a", toNodeId: "b"),
                WorkflowEdge(fromNodeId: "b", toNodeId: "a"),  // 循环
                WorkflowEdge(fromNodeId: "b", toNodeId: "output")
            ]
        )

        let result = await engine.execute(workflow: workflow, input: "x")
        XCTAssertTrue(result.hasPrefix("Error:"))
        XCTAssertTrue(result.contains("循环依赖"))
    }

    /// 纯 TRANSFORM + OUTPUT 工作流（不调用 Agent）应正常完成
    /// 该用例验证拓扑排序、节点输入拼接、转换函数
    func testExecuteTransformOnlyWorkflow() async {
        let workflow = Workflow(
            id: "transform-only",
            name: "转换链",
            description: "测试",
            nodes: [
                WorkflowNode(id: "input", type: .input, label: "输入"),
                WorkflowNode(id: "upper", type: .transform, label: "转大写", transformType: .toUppercase),
                WorkflowNode(id: "prefix", type: .transform, label: "加前缀", prompt: ">> ", transformType: .prefix),
                WorkflowNode(id: "output", type: .output, label: "输出")
            ],
            edges: [
                WorkflowEdge(fromNodeId: "input", toNodeId: "upper"),
                WorkflowEdge(fromNodeId: "upper", toNodeId: "prefix"),
                WorkflowEdge(fromNodeId: "prefix", toNodeId: "output")
            ]
        )

        let result = await engine.execute(workflow: workflow, input: "hello")
        XCTAssertEqual(result, ">> HELLO")
        XCTAssertEqual(engine.executionState.output, ">> HELLO")
        XCTAssertFalse(engine.executionState.isRunning)
        XCTAssertNil(engine.executionState.error)
        // 三个非 INPUT 节点应都标记为完成
        XCTAssertEqual(engine.executionState.completedNodeIds.count, 4)
        XCTAssertTrue(engine.executionState.completedNodeIds.contains("upper"))
        XCTAssertTrue(engine.executionState.completedNodeIds.contains("prefix"))
        XCTAssertTrue(engine.executionState.completedNodeIds.contains("output"))
    }

    /// 多上游节点的输出应以双换行拼接
    func testMultipleUpstreamOutputsJoined() async {
        let workflow = Workflow(
            id: "multi-upstream",
            name: "多上游",
            description: "测试",
            nodes: [
                WorkflowNode(id: "input", type: .input, label: "I"),
                WorkflowNode(id: "a", type: .transform, label: "A", prompt: "A:", transformType: .prefix),
                WorkflowNode(id: "b", type: .transform, label: "B", prompt: "B:", transformType: .prefix),
                WorkflowNode(id: "merge", type: .transform, label: "Merge", transformType: .passthrough),
                WorkflowNode(id: "output", type: .output, label: "O")
            ],
            edges: [
                WorkflowEdge(fromNodeId: "input", toNodeId: "a"),
                WorkflowEdge(fromNodeId: "input", toNodeId: "b"),
                WorkflowEdge(fromNodeId: "a", toNodeId: "merge"),
                WorkflowEdge(fromNodeId: "b", toNodeId: "merge"),
                WorkflowEdge(fromNodeId: "merge", toNodeId: "output")
            ]
        )

        let result = await engine.execute(workflow: workflow, input: "x")
        // merge 节点输入应为 "A:x\n\nB:x"（顺序取决于拓扑排序结果，但两段都应出现）
        XCTAssertTrue(result.contains("A:x"))
        XCTAssertTrue(result.contains("B:x"))
        XCTAssertTrue(result.contains("\n\n"))
    }

    // MARK: - 转换函数（通过执行工作流间接验证 applyTransform）

    func testTransformPassthrough() async {
        let result = await runSingleTransform(.passthrough, input: "hello", extra: "")
        XCTAssertEqual(result, "hello")
    }

    func testTransformToUppercase() async {
        let result = await runSingleTransform(.toUppercase, input: "hello", extra: "")
        XCTAssertEqual(result, "HELLO")
    }

    func testTransformToLowercase() async {
        let result = await runSingleTransform(.toLowercase, input: "HELLO", extra: "")
        XCTAssertEqual(result, "hello")
    }

    func testTransformTrim() async {
        let result = await runSingleTransform(.trim, input: "  hello  \n", extra: "")
        XCTAssertEqual(result, "hello")
    }

    func testTransformPrefix() async {
        let result = await runSingleTransform(.prefix, input: "world", extra: "hello, ")
        XCTAssertEqual(result, "hello, world")
    }

    func testTransformSuffix() async {
        let result = await runSingleTransform(.suffix, input: "hello", extra: "!")
        XCTAssertEqual(result, "hello!")
    }

    /// 正则提取：使用 extra 作为正则，提取第一个捕获组
    func testTransformExtractWithRegex() async {
        let result = await runSingleTransform(.extract, input: "code: ABC-123 end", extra: "code: (\\w+-\\d+)")
        XCTAssertEqual(result, "ABC-123")
    }

    /// 正则提取：extra 为空时匹配全部内容
    func testTransformExtractDefaultPattern() async {
        let result = await runSingleTransform(.extract, input: "hello", extra: "")
        XCTAssertEqual(result, "hello")
    }

    /// JSON 字段提取：正常路径
    func testTransformJsonExtract() async {
        let input = #"{"name":"Alice","age":30}"#
        let result = await runSingleTransform(.jsonExtract, input: input, extra: "name")
        XCTAssertEqual(result, "Alice")
    }

    /// JSON 字段提取：非字符串值应转为字符串
    func testTransformJsonExtractNonString() async {
        let input = #"{"age":30}"#
        let result = await runSingleTransform(.jsonExtract, input: input, extra: "age")
        XCTAssertEqual(result, "30")
    }

    /// JSON 字段提取：非法 JSON 应返回原输入
    func testTransformJsonExtractInvalidJson() async {
        let result = await runSingleTransform(.jsonExtract, input: "not-json", extra: "name")
        XCTAssertEqual(result, "not-json")
    }

    /// JSON 字段提取：字段不存在应返回原输入
    func testTransformJsonExtractMissingField() async {
        let input = #"{"name":"Alice"}"#
        let result = await runSingleTransform(.jsonExtract, input: input, extra: "missing")
        XCTAssertEqual(result, #"{"name":"Alice"}"#)
    }

    /// 非法正则应回退为原输入（不抛出）
    func testTransformExtractInvalidRegex() async {
        let result = await runSingleTransform(.extract, input: "hello", extra: "(")
        XCTAssertEqual(result, "hello")
    }

    // MARK: - 模板完整性

    /// WorkflowTemplates.allTemplates() 应返回 3 个模板
    func testAllTemplatesCount() {
        let templates = WorkflowTemplates.allTemplates()
        XCTAssertEqual(templates.count, 3)
    }

    /// 每个模板都应包含至少一个 INPUT 节点
    func testTemplatesContainInputNode() {
        for template in WorkflowTemplates.allTemplates() {
            XCTAssertNotNil(
                template.nodes.first { $0.type == .input },
                "模板 \(template.name) 应包含 INPUT 节点"
            )
        }
    }

    /// 每个模板都应包含至少一个 OUTPUT 节点
    func testTemplatesContainOutputNode() {
        for template in WorkflowTemplates.allTemplates() {
            XCTAssertNotNil(
                template.nodes.first { $0.type == .output },
                "模板 \(template.name) 应包含 OUTPUT 节点"
            )
        }
    }

    /// 每个模板的拓扑排序应成功（无循环依赖）
    func testTemplatesTopologicallySortable() async {
        for template in WorkflowTemplates.allTemplates() {
            let result = await engine.execute(workflow: template, input: "test")
            // 模板包含 AGENT 节点，会因网络/默认配置而失败，
            // 但失败原因不应是"循环依赖"或"缺少 INPUT 节点"
            XCTAssertFalse(result.contains("循环依赖"), "模板 \(template.name) 不应包含循环依赖")
            XCTAssertFalse(result.contains("缺少 INPUT 节点"), "模板 \(template.name) 应包含 INPUT 节点")
        }
    }

    // MARK: - 日志

    /// 执行工作流应记录日志
    func testExecutionLogsAppended() async {
        let workflow = Workflow(
            id: "log-test",
            name: "日志测试",
            description: "测试",
            nodes: [
                WorkflowNode(id: "input", type: .input, label: "I"),
                WorkflowNode(id: "output", type: .output, label: "O")
            ],
            edges: [
                WorkflowEdge(fromNodeId: "input", toNodeId: "output")
            ]
        )

        _ = await engine.execute(workflow: workflow, input: "x")
        XCTAssertFalse(engine.executionState.logs.isEmpty, "应记录执行日志")
        XCTAssertTrue(engine.executionState.logs.contains { $0.contains("开始执行工作流") })
        XCTAssertTrue(engine.executionState.logs.contains { $0.contains("工作流执行完成") })
    }

    // MARK: - Helpers

    /// 执行一个 INPUT → TRANSFORM → OUTPUT 的最小工作流，返回 TRANSFORM 节点的输出
    private func runSingleTransform(_ type: TransformType, input: String, extra: String) async -> String {
        let workflow = Workflow(
            id: "single-transform-\(type.rawValue)",
            name: "ST",
            description: "测试",
            nodes: [
                WorkflowNode(id: "input", type: .input, label: "I"),
                WorkflowNode(id: "transform", type: .transform, label: "T", prompt: extra, transformType: type),
                WorkflowNode(id: "output", type: .output, label: "O")
            ],
            edges: [
                WorkflowEdge(fromNodeId: "input", toNodeId: "transform"),
                WorkflowEdge(fromNodeId: "transform", toNodeId: "output")
            ]
        )
        return await engine.execute(workflow: workflow, input: input)
    }
}

// MARK: - Mock TransportFactory（WorkflowEngineTests 专用）

/// 测试用 TransportFactory mock，创建立即返回 streamComplete 的 MockTransport，
/// 让 executeAgent 的事件收集循环快速结束，避免 60s 超时。
private final class MockTransportFactory: TransportFactorying, @unchecked Sendable {
    func create(_ agentType: AgentType) -> AgentTransport {
        MockTransport()
    }
}

private final class MockTransport: AgentTransport, @unchecked Sendable {
    var events: AsyncStream<AgentEvent> {
        AsyncStream { continuation in
            // 立即 yield streamComplete 并 finish，让 executeAgent 的 for-await 循环快速退出
            continuation.yield(.streamComplete)
            continuation.finish()
        }
    }
    var connectionState: AgentConnectionState { AgentConnectionState() }
    func connect(config: AgentConfig, e2eKey: String?) async {}
    func sendMessage(sessionId: String, content: String) async throws {}
    func disconnect() {}
    func shutdown() {}
    func clearHistory(sessionId: String) async {}
    func clearAllHistory() async {}
}
