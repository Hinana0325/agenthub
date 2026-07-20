import Foundation
import Observation

// MARK: - McpBridge
// 对应 Android com.agentcontrolcenter.app.mcp.bridge.McpBridge

/// MCP 桥接层 — Agent 工具调用与 MCP Server 之间的协调器。
///
/// 职责：
/// 1. `connectServer`: 连接 MCP Server → initialize 握手 → tools/list → 注册到 Registry
/// 2. `callTool` / `callToolDetailed`: 从 Registry 查找工具归属 server → 委托 McpClient 执行 tools/call
/// 3. `disconnectServer`: 从 Registry 注销 → 关闭 McpClient 连接
/// 4. `refreshTools`: 重新拉取某个 server 的工具列表
///
/// Agent 层只需调用 `callTool`，无需关心工具来自哪个 server。
/// `connectionStates` 暴露各 server 的实时连接状态，供 SwiftUI 视图响应式观察。
///
/// 设计参考 MCP Host 角色：https://modelcontextprotocol.io/docs/learn/architecture
///
/// 与 Android 版的差异：
/// - Android 使用 `StateFlow<Map<String, ServerConnectionState>>`，iOS 使用 `@Observable` + `[String: ServerConnectionState]`
/// - Android 通过 Hilt `@Inject` 注入依赖，iOS 使用默认参数 init（便于测试时注入 mock）
@Observable
final class McpBridge {

    /// 连接状态枚举，描述 MCP Server 与客户端之间的连接生命周期阶段。
    enum ConnectionState {
        /// 已断开（初始状态或主动 disconnect 后）
        case disconnected
        /// 正在连接（initialize 握手进行中）
        case connecting
        /// 已连接（握手成功，工具已注册）
        case connected
        /// 连接失败（握手错误或异常）
        case failed
    }

    /// 单个 Server 的连接状态快照。
    struct ServerConnectionState: Equatable {
        /// 对应的 Server ID
        let serverId: String
        /// 当前连接状态
        let state: ConnectionState
        /// 错误描述（仅 `failed` 状态下非空）
        let errorMessage: String?
    }

    /// 各 Server 的连接状态（serverId -> 状态快照），供 UI 响应式观察。
    private(set) var connectionStates: [String: ServerConnectionState] = [:]

    /// 工具注册表（管理 server 配置与工具索引）
    @ObservationIgnored
    private let registry: McpRegistry

    /// MCP 客户端（负责 JSON-RPC 通信）
    @ObservationIgnored
    private let client: McpClient

    // MARK: - 初始化

    /// 创建 McpBridge 实例。
    /// - Parameters:
    ///   - registry: 工具注册表（默认创建新实例；测试时可注入 mock）
    ///   - client: MCP 客户端（默认创建新实例；测试时可注入 mock）
    init(registry: McpRegistry = McpRegistry(), client: McpClient = McpClient()) {
        self.registry = registry
        self.client = client
    }

    // MARK: - 连接管理

    /// 连接 MCP Server：initialize 握手 + tools/list + 注册到 Registry。
    ///
    /// 流程：
    /// 1. 更新状态为 `connecting`
    /// 2. 通过 `McpClient.connect` 完成 initialize 握手，获取 `McpServerCapabilities`
    /// 3. 将 server（含更新后的 capabilities）注册到 `McpRegistry`
    /// 4. 若 `capabilities.tools == true`，调用 `listTools` 并 `registerTools`
    /// 5. 更新状态为 `connected`（成功）或 `failed`（失败）
    ///
    /// - Parameter server: 要连接的 MCP Server
    /// - Returns: true 表示连接成功并已注册工具；false 表示握手失败
    func connectServer(_ server: McpServer) async -> Bool {
        updateConnectionState(serverId: server.id, state: .connecting)

        // 1. initialize 握手
        guard let capabilities = await client.connect(server) else {
            updateConnectionState(serverId: server.id, state: .failed, error: "Initialize failed")
            return false
        }

        // 2. 注册 server 配置（回填协商后的 capabilities）
        var updated = server
        updated.capabilities = capabilities
        registry.registerServer(updated)

        // 3. 若支持 tools 能力，拉取工具列表并注册
        if capabilities.tools {
            if let tools = await client.listTools(server) {
                registry.registerTools(serverId: server.id, tools: tools)
            }
            // listTools 返回 nil 时不阻断连接，仅不注册工具
        }

        updateConnectionState(serverId: server.id, state: .connected)
        return true
    }

    /// 调用工具 — Agent 层入口（精简版）。
    ///
    /// 从 `McpRegistry` 查找工具名归属的 server，再委托 `McpClient` 执行 `tools/call`，
    /// 返回结果的 `asText` 文本（拼接所有 text 内容）。
    ///
    /// - Parameters:
    ///   - toolName: 工具名
    ///   - arguments: 调用参数
    /// - Returns: 工具结果文本（若 `isError=true` 则为错误描述）；工具不存在或调用失败返回 nil
    func callTool(toolName: String, arguments: [String: Any]) async -> String? {
        guard let (serverId, _) = registry.findTool(toolName) else { return nil }
        guard let server = registry.servers[serverId] else { return nil }

        guard let result = await client.callTool(server, toolName: toolName, arguments: arguments) else {
            return nil
        }
        return result.asText
    }

    /// 调用工具，返回完整的 `McpToolResult`（含 `isError` 标志和多种内容类型）。
    ///
    /// 适用于需要区分 text/image/audio 内容或检测 `isError` 的场景。
    ///
    /// - Parameters:
    ///   - toolName: 工具名
    ///   - arguments: 调用参数
    /// - Returns: 完整工具结果；工具不存在或网络错误返回 nil
    func callToolDetailed(toolName: String, arguments: [String: Any]) async -> McpToolResult? {
        guard let (serverId, _) = registry.findTool(toolName) else { return nil }
        guard let server = registry.servers[serverId] else { return nil }

        return await client.callTool(server, toolName: toolName, arguments: arguments)
    }

    /// 断开 MCP Server 连接。
    ///
    /// 流程：
    /// 1. 从 `McpRegistry` 注销 server 及其工具
    /// 2. 调用 `McpClient.disconnect` 清理客户端资源
    /// 3. 更新连接状态为 `disconnected`
    ///
    /// - Parameter serverId: 要断开的 Server ID
    func disconnectServer(_ serverId: String) async {
        registry.unregisterServer(serverId)
        await client.disconnect(serverId)
        updateConnectionState(serverId: serverId, state: .disconnected)
    }

    /// 获取所有已注册的工具名。
    /// - Returns: 排序后的工具名列表（委托给 `McpRegistry`）
    func getAvailableTools() -> [String] {
        registry.getAllToolNames()
    }

    /// 刷新某个 server 的工具列表（重新 `tools/list`）。
    ///
    /// 用于工具列表可能发生变化的场景（如 server 端动态加载新工具）。
    ///
    /// - Parameter serverId: 目标 Server ID
    /// - Returns: true 表示刷新成功并已更新注册表；server 不存在或 listTools 失败返回 false
    func refreshTools(_ serverId: String) async -> Bool {
        guard let server = registry.servers[serverId] else { return false }
        guard let tools = await client.listTools(server) else { return false }
        registry.registerTools(serverId: serverId, tools: tools)
        return true
    }

    // MARK: - 内部方法

    /// 更新某个 server 的连接状态。
    ///
    /// 写入 `connectionStates` 字典，触发 @Observable 通知，UI 可响应式刷新。
    ///
    /// - Parameters:
    ///   - serverId: 目标 Server ID
    ///   - state: 新的连接状态
    ///   - error: 可选错误描述（仅在 `failed` 状态下有意义，其他状态自动置 nil）
    private func updateConnectionState(
        serverId: String,
        state: ConnectionState,
        error: String? = nil
    ) {
        connectionStates[serverId] = ServerConnectionState(
            serverId: serverId,
            state: state,
            errorMessage: error
        )
    }
}
