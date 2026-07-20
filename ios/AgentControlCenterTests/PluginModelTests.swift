import XCTest
@testable import AgentControlCenter

// MARK: - Plugin 模型单元测试
final class PluginModelTests: XCTestCase {

    // MARK: - PluginActionType 测试

    /// 测试 PluginActionType 所有 case（未遵循 CaseIterable，手动验证 4 个 case）
    func testPluginActionTypeAllCases() {
        let allTypes: [PluginActionType] = [.http, .broadcast, .workflow, .none]
        XCTAssertEqual(allTypes.count, 4, "PluginActionType 应包含 4 个枚举值")

        // 验证 rawValue
        XCTAssertEqual(PluginActionType.http.rawValue, "http")
        XCTAssertEqual(PluginActionType.broadcast.rawValue, "broadcast")
        XCTAssertEqual(PluginActionType.workflow.rawValue, "workflow")
        XCTAssertEqual(PluginActionType.none.rawValue, "none")
    }

    // MARK: - PluginAction 测试

    /// 测试 PluginAction .httpCall 类型
    func testPluginActionHttpCall() {
        let httpCall = PluginAction.HttpCall(
            url: "https://api.example.com/webhook",
            method: "POST",
            headers: ["Authorization": "Bearer token"],
            bodyTemplate: "{\"text\": \"{{message}}\"}"
        )
        let action: PluginAction = .httpCall(httpCall)
        XCTAssertEqual(action.type, .http)
    }

    /// 测试 PluginAction .broadcast 类型
    func testPluginActionBroadcast() {
        let broadcast = PluginAction.Broadcast(
            action: "OPEN_APP",
            extras: ["url": "myapp://deeplink"]
        )
        let action: PluginAction = .broadcast(broadcast)
        XCTAssertEqual(action.type, .broadcast)
    }

    /// 测试 PluginAction .workflow 类型
    func testPluginActionWorkflow() {
        let workflowAction = PluginAction.WorkflowAction(promptTemplate: "分析: {{input}}")
        let action: PluginAction = .workflow(workflowAction)
        XCTAssertEqual(action.type, .workflow)
    }

    // MARK: - Plugin 创建测试

    /// 测试 Plugin 基本创建
    func testPluginCreation() {
        let plugin = Plugin(
            id: "plugin-1",
            name: "测试插件",
            description: "用于测试的插件",
            icon: "icon.png"
        )
        XCTAssertEqual(plugin.id, "plugin-1")
        XCTAssertEqual(plugin.name, "测试插件")
        XCTAssertEqual(plugin.description, "用于测试的插件")
        XCTAssertEqual(plugin.icon, "icon.png")
        // 默认值
        XCTAssertTrue(plugin.isEnabled)
        XCTAssertTrue(plugin.permissions.isEmpty)
        XCTAssertEqual(plugin.version, "1.0.0")
        XCTAssertNil(plugin.action)
    }

    /// 测试 Plugin 完整创建（含 action）
    func testPluginCreationWithAction() {
        let httpAction = PluginAction.HttpCall(
            url: "https://example.com/api",
            method: "POST",
            headers: ["Content-Type": "application/json"],
            bodyTemplate: "{\"data\": \"{{value}}\"}"
        )
        let plugin = Plugin(
            id: "plugin-2",
            name: "HTTP 插件",
            description: "发送 HTTP 请求",
            icon: "http-icon.png",
            isEnabled: true,
            permissions: ["NETWORK", "CLIPBOARD"],
            version: "2.1.0",
            action: .httpCall(httpAction)
        )
        XCTAssertEqual(plugin.id, "plugin-2")
        XCTAssertEqual(plugin.version, "2.1.0")
        XCTAssertEqual(plugin.permissions, ["NETWORK", "CLIPBOARD"])
        XCTAssertNotNil(plugin.action)
        XCTAssertEqual(plugin.action?.type, .http)
    }

    // MARK: - Plugin Codable 测试

    /// 测试 Plugin Codable 编解码（不含 action）
    func testPluginCodableWithoutAction() {
        let plugin = Plugin(
            id: "p-enc-1",
            name: "编码插件",
            description: "编解码测试",
            icon: "enc.png",
            isEnabled: false,
            permissions: ["READ"],
            version: "1.5.0"
        )

        let data = try? JSONEncoder().encode(plugin)
        XCTAssertNotNil(data, "Plugin 编码不应返回 nil")

        let decoded = try? JSONDecoder().decode(Plugin.self, from: data!)
        XCTAssertNotNil(decoded, "Plugin 解码不应返回 nil")
        XCTAssertEqual(decoded, plugin, "编码后解码的 Plugin 应与原始值相等")
        XCTAssertFalse(decoded!.isEnabled)
    }

    /// 测试 Plugin Codable 编解码（含 http action）
    func testPluginCodableWithHttpAction() {
        let httpAction = PluginAction.HttpCall(
            url: "https://example.com/hook",
            method: "POST",
            headers: ["X-Token": "abc"]
        )
        let plugin = Plugin(
            id: "p-enc-2",
            name: "HTTP 插件",
            description: "带 HTTP 动作的插件",
            icon: "hook.png",
            action: .httpCall(httpAction)
        )

        let data = try? JSONEncoder().encode(plugin)
        XCTAssertNotNil(data)

        let decoded = try? JSONDecoder().decode(Plugin.self, from: data!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded, plugin, "编码后解码的 Plugin（含 action）应与原始值相等")
        XCTAssertEqual(decoded?.action?.type, .http)
    }

    /// 测试 Plugin Codable 编解码（含 workflow action）
    func testPluginCodableWithWorkflowAction() {
        let wfAction = PluginAction.WorkflowAction(promptTemplate: "处理: {{input}}")
        let plugin = Plugin(
            id: "p-enc-3",
            name: "工作流插件",
            description: "触发工作流",
            icon: "wf.png",
            action: .workflow(wfAction)
        )

        let data = try? JSONEncoder().encode(plugin)
        XCTAssertNotNil(data)

        let decoded = try? JSONDecoder().decode(Plugin.self, from: data!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded, plugin)
        XCTAssertEqual(decoded?.action?.type, .workflow)
    }
}