import Foundation
import Observation

// MARK: - McpRegistry
// 对应 Android com.agentcontrolcenter.app.mcp.registry.McpRegistry

/// MCP Server 注册表 — 管理已连接的 MCP Server 及其提供的工具。
///
/// 职责：
/// - 维护 `servers`（serverId -> McpServer）与 `tools`（已注册工具列表）两份响应式状态
/// - 维护 `toolIndex`（toolName -> (serverId, McpTool)）支持 O(1) 工具名查询
/// - 当 `McpClient` 完成 tools/list 后，由 `McpBridge` 调用 `registerTools` 注册工具
/// - `McpBridge` 通过 `findTool` 查询工具归属的 server，再委托 `McpClient` 执行 tools/call
///
/// 线程安全：使用 `NSLock` 保护 `toolIndex` 及关联状态的原子读写，避免并发注册/注销时索引不一致。
/// 与 Android 版的差异：Android 使用 `ConcurrentHashMap` + `StateFlow.update`，iOS 使用 `@Observable` + `NSLock`。
@Observable
final class McpRegistry {

    /// 已注册的 MCP Server 配置字典（serverId -> McpServer）
    private(set) var servers: [String: McpServer] = [:]

    /// 所有已注册的工具列表（跨多个 server 聚合）
    private(set) var tools: [McpTool] = []

    /// 工具名 -> (serverId, McpTool) 的快速查找索引，支持 O(1) 查询。
    /// 元组含义：(serverId, McpTool)。
    /// 通过 `NSLock` 保护，避免并发写入导致的索引不一致。
    @ObservationIgnored
    private var toolIndex: [String: (String, McpTool)] = [:]

    /// 保护 toolIndex 及其与 servers/tools 同步更新的锁
    @ObservationIgnored
    private let lock = NSLock()

    // MARK: - Server 注册

    /// 注册或更新一个 MCP Server 配置。
    /// - Parameter server: 要注册的 MCP Server。若 id 已存在则覆盖。
    func registerServer(_ server: McpServer) {
        lock.lock()
        defer { lock.unlock() }
        servers[server.id] = server
    }

    /// 注销 MCP Server，同时移除其所有工具。
    /// - Parameter serverId: 要注销的 Server ID
    func unregisterServer(_ serverId: String) {
        lock.lock()
        defer { lock.unlock() }

        // 移除 server 配置
        servers.removeValue(forKey: serverId)

        // 从 toolIndex 中移除该 server 提供的所有工具（元组 .0 = serverId）
        let toolNamesToRemove = toolIndex
            .filter { _, value in value.0 == serverId }
            .map { key, _ in key }
        for name in toolNamesToRemove {
            toolIndex.removeValue(forKey: name)
        }

        // 从工具列表中移除该 server 的工具
        tools.removeAll { $0.serverId == serverId }
    }

    /// 注册某个 server 提供的工具列表（来自 tools/list 响应）。
    ///
    /// 流程：
    /// 1. 清除该 server 在 toolIndex 与 tools 中的旧工具
    /// 2. 写入新工具到 toolIndex（按工具名建立索引）
    /// 3. 合并新工具到 tools 列表
    ///
    /// - Parameters:
    ///   - serverId: 所属 Server ID
    ///   - tools: 该 Server 提供的工具列表
    func registerTools(serverId: String, tools: [McpTool]) {
        lock.lock()
        defer { lock.unlock() }

        // 1. 清除该 server 的旧工具索引（元组 .0 = serverId）
        let oldNames = toolIndex
            .filter { _, value in value.0 == serverId }
            .map { key, _ in key }
        for name in oldNames {
            toolIndex.removeValue(forKey: name)
        }

        // 2. 注册新工具到索引
        for tool in tools {
            toolIndex[tool.name] = (serverId, tool)
        }

        // 3. 更新工具列表：移除该 server 的旧工具 + 追加新工具
        let kept = self.tools.filter { $0.serverId != serverId }
        self.tools = kept + tools
    }

    // MARK: - 工具查询

    /// 按工具名查找工具及其所属 server（O(1) 查询）。
    /// - Parameter toolName: 工具名
    /// - Returns: 命中时返回 (serverId, McpTool)；未注册时返回 nil
    func findTool(_ toolName: String) -> (String, McpTool)? {
        lock.lock()
        defer { lock.unlock() }
        return toolIndex[toolName]
    }

    /// 获取所有已注册的工具名列表（按字母升序排序）。
    /// - Returns: 排序后的工具名数组
    func getAllToolNames() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return toolIndex.keys.sorted()
    }

    /// 获取某个 server 提供的所有工具。
    /// - Parameter serverId: 目标 Server ID
    /// - Returns: 该 Server 的工具列表（未注册时为空数组）
    func getToolsForServer(_ serverId: String) -> [McpTool] {
        lock.lock()
        defer { lock.unlock() }
        return tools.filter { $0.serverId == serverId }
    }

    /// 获取所有已启用的 server（`isEnabled == true`）。
    /// - Returns: 已启用 server 列表
    func getEnabledServers() -> [McpServer] {
        lock.lock()
        defer { lock.unlock() }
        return servers.values.filter { $0.isEnabled }
    }

    // MARK: - 清理

    /// 清空所有注册信息（servers、tools、toolIndex）。
    func clear() {
        lock.lock()
        defer { lock.unlock() }
        toolIndex.removeAll()
        servers.removeAll()
        tools.removeAll()
    }
}
