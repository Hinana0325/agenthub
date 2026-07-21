import Foundation
import Observation

// MARK: - McpClient
// 对应 Android com.agentcontrolcenter.app.mcp.client.McpClient

/// 原子递增计数器 — 使用 NSLock 保护，生成 JSON-RPC 请求 ID。
///
/// 独立实现为简单类（替代 `AtomicInt`/actor），保证跨并发域安全递增。
private final class AtomicCounter: @unchecked Sendable {

    /// 当前计数值
    private var value: Int = 0

    /// 保护 value 的锁
    private let lock = NSLock()

    /// 原子递增并返回新值。
    /// - Returns: 递增后的值（从 1 开始）
    func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        value += 1
        return value
    }
}

/// MCP 客户端 — 通过 JSON-RPC 2.0 over HTTP 与 MCP Server 通信。
///
/// 职责：
/// 1. `connect`: 发送 `initialize` 请求，完成能力协商
/// 2. `listTools`: 发送 `tools/list` 请求，获取工具列表
/// 3. `callTool`: 发送 `tools/call` 请求，执行工具
/// 4. `disconnect`: 关闭连接（HTTP 模式下为占位实现，预留 SSE/STDIO 扩展）
///
/// 传输层使用 HTTP POST，遵循 `protocol/schemas/mcp-schema.json` 中
/// `transport.http` 约定：单次请求超时 30 秒，apiKey 存在时附加
/// `Authorization: Bearer {apiKey}` 头。
///
/// 协议参考：https://modelcontextprotocol.io/specification/2025-03-26
///
/// 与 Android 版的差异：
/// - Android 使用 Ktor `HttpClient` + `withTimeoutOrNull`，iOS 使用 `URLSession` + 自实现 `withTimeout`
/// - Android 使用 `AtomicLong`，iOS 使用 `AtomicCounter`（NSLock 包装）
@Observable
final class McpClient: @unchecked Sendable {

    /// HTTP 会话，配置 30 秒请求超时（对应 mcp-schema.json `transport.http.timeout_seconds`）
    @ObservationIgnored
    private let session: URLSession

    /// JSON-RPC 请求 ID 计数器（线程安全）
    @ObservationIgnored
    private let requestCounter = AtomicCounter()

    /// JSON 编码器（复用实例以提升性能）
    @ObservationIgnored
    private let encoder = JSONEncoder()

    /// JSON 解码器（复用实例以提升性能）
    @ObservationIgnored
    private let decoder = JSONDecoder()

    // MARK: - 初始化

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30   // 单次请求超时 30s
        config.timeoutIntervalForResource = 30  // 资源整体超时 30s
        config.waitsForConnectivity = false     // 不等待连通性，立即失败
        self.session = URLSession(configuration: config)
    }

    // MARK: - 公共方法

    /// 连接到 MCP Server，发送 `initialize` 握手。
    ///
    /// 请求参数（对应 `InitializeParams`）：
    /// - `protocolVersion`: "2025-03-26"
    /// - `capabilities`: `{}`（空对象，客户端无特殊能力声明）
    /// - `clientInfo`: `{ name: "Agent Control Center", version: "2.1.3" }`
    ///
    /// 解析响应 `result.capabilities`，根据 `tools`/`resources`/`prompts`/`logging`
    /// 字段是否存在构造 `McpServerCapabilities`（存在即视为支持该能力）。
    ///
    /// - Parameter server: 目标 MCP Server
    /// - Returns: 协商后的服务器能力；网络错误、JSON-RPC 错误或解析失败时返回 nil
    func connect(_ server: McpServer) async -> McpServerCapabilities? {
        // 构造 initialize 参数
        let params: [String: AnyCodable] = [
            "protocolVersion": AnyCodable("2025-03-26"),
            "capabilities": AnyCodable([String: Any]()),
            "clientInfo": AnyCodable([
                "name": "Agent Control Center",
                "version": "2.1.3"
            ])
        ]

        let request = JsonRpcRequest(
            id: requestCounter.next(),
            method: "initialize",
            params: params
        )

        guard let url = URLValidator.validate(server.transportUrl, allowLocalhost: true) else { return nil }
        guard let response = await sendRequest(url: url, apiKey: server.apiKey, request: request) else {
            return nil
        }

        // JSON-RPC 层错误视为握手失败
        if response.error != nil { return nil }

        // 解析 result.capabilities
        guard let result = response.result?.asDict else {
            return McpServerCapabilities()
        }
        guard let caps = result["capabilities"] as? [String: Any] else {
            return McpServerCapabilities()
        }

        // 各能力字段存在即视为支持（与 Android 实现一致）
        return McpServerCapabilities(
            tools: caps["tools"] != nil,
            resources: caps["resources"] != nil,
            prompts: caps["prompts"] != nil,
            logging: caps["logging"] != nil
        )
    }

    /// 获取服务器提供的工具列表（`tools/list`）。
    ///
    /// 解析响应 `result.tools[]`，每个工具映射为 `McpTool`，包含：
    /// - `name`: 工具名
    /// - `description`: 工具描述（缺失时为空串）
    /// - `inputSchema`: 由 `parseSchema` 解析的 JSON Schema
    /// - `serverId`: 绑定到当前 Server
    ///
    /// - Parameter server: 目标 MCP Server
    /// - Returns: 工具列表；网络错误或 JSON-RPC 错误返回 nil；成功但无工具返回空数组
    func listTools(_ server: McpServer) async -> [McpTool]? {
        // tools/list 无参数
        let request = JsonRpcRequest(
            id: requestCounter.next(),
            method: "tools/list",
            params: nil
        )

        guard let url = URLValidator.validate(server.transportUrl, allowLocalhost: true) else { return nil }
        guard let response = await sendRequest(url: url, apiKey: server.apiKey, request: request) else {
            return nil
        }

        if response.error != nil { return nil }

        guard let result = response.result?.asDict else { return nil }
        guard let toolsRaw = result["tools"] as? [Any] else { return [] }

        return toolsRaw.compactMap { toolRaw -> McpTool? in
            guard let toolMap = toolRaw as? [String: Any] else { return nil }
            guard let name = toolMap["name"] as? String else { return nil }
            let description = toolMap["description"] as? String ?? ""
            let schemaMap = toolMap["inputSchema"] as? [String: Any] ?? [:]

            return McpTool(
                name: name,
                description: description,
                inputSchema: parseSchema(schemaMap),
                serverId: server.id
            )
        }
    }

    /// 调用工具（`tools/call`）。
    ///
    /// 请求参数（对应 `ToolsCallParams`）：
    /// - `name`: 工具名
    /// - `arguments`: 调用参数对象（须符合工具的 `inputSchema`）
    ///
    /// 解析响应 `result.content[]`（支持 text/image/audio 三种内容类型）与
    /// `result.isError` 标志。当 JSON-RPC 层返回错误时，构造一个 `isError=true`
    /// 的 `McpToolResult`，内容为错误描述文本。
    ///
    /// - Parameters:
    ///   - server: 目标 MCP Server
    ///   - toolName: 工具名
    ///   - arguments: 调用参数（任意 JSON 可序列化结构）
    /// - Returns: 工具执行结果；URL 无效或网络错误返回 nil；JSON-RPC 错误返回带错误描述的结果
    func callTool(
        _ server: McpServer,
        toolName: String,
        arguments: [String: Any]
    ) async -> McpToolResult? {
        let params: [String: AnyCodable] = [
            "name": AnyCodable(toolName),
            "arguments": AnyCodable(arguments)
        ]

        let request = JsonRpcRequest(
            id: requestCounter.next(),
            method: "tools/call",
            params: params
        )

        guard let url = URLValidator.validate(server.transportUrl, allowLocalhost: true) else { return nil }
        guard let response = await sendRequest(url: url, apiKey: server.apiKey, request: request) else {
            return nil
        }

        // JSON-RPC 层错误：包装为带错误描述的 McpToolResult
        if let error = response.error {
            return McpToolResult(
                content: [.text("JSON-RPC Error \(error.code): \(error.message)")],
                isError: true
            )
        }

        guard let result = response.result?.asDict else { return nil }
        let contentRaw = result["content"] as? [Any] ?? []
        let isError = result["isError"] as? Bool ?? false

        // 解析 content[]，按 type 字段判别具体子类型
        let content = contentRaw.compactMap { item -> McpContent? in
            guard let itemMap = item as? [String: Any] else { return nil }
            let type = itemMap["type"] as? String
            switch type {
            case "text":
                return .text(itemMap["text"] as? String ?? "")
            case "image":
                return .image(
                    data: itemMap["data"] as? String ?? "",
                    mimeType: itemMap["mimeType"] as? String ?? "image/png"
                )
            case "audio":
                return .audio(
                    data: itemMap["data"] as? String ?? "",
                    mimeType: itemMap["mimeType"] as? String ?? "audio/wav"
                )
            default:
                return nil
            }
        }

        return McpToolResult(content: content, isError: isError)
    }

    /// 关闭并释放某个 server 的连接资源。
    ///
    /// 当前 HTTP 传输为无状态连接，此方法为占位实现，预留 SSE/STDIO 扩展。
    /// - Parameter serverId: 要断开的 Server ID
    func disconnect(_ serverId: String) async {
        // HTTP 模式下无连接状态需要清理；SSE/STDIO 模式下应关闭长连接
    }

    // MARK: - 内部方法

    /// 发送 JSON-RPC 请求到指定 URL。
    ///
    /// 流程：
    /// 1. 序列化 `JsonRpcRequest` 为 JSON 字节
    /// 2. 构造 POST 请求，设置 `Content-Type: application/json`
    /// 3. 当 `apiKey` 非空时附加 `Authorization: Bearer {apiKey}` 头
    /// 4. 在 30 秒超时内等待响应（通过 `withTimeout`）
    /// 5. 解析响应为 `JsonRpcResponse`
    ///
    /// - Parameters:
    ///   - url: 目标 URL（来自 `server.transportUrl`）
    ///   - apiKey: 可选 API 密钥；非空时附加 Bearer 认证头
    ///   - request: JSON-RPC 2.0 请求对象
    /// - Returns: 解析后的响应；超时、网络错误或解析失败返回 nil
    private func sendRequest(
        url: URL,
        apiKey: String?,
        request: JsonRpcRequest
    ) async -> JsonRpcResponse? {
        // 序列化请求体（在 timeout 之外执行，避免编码失败被吞掉）
        let bodyData: Data
        do {
            bodyData = try encoder.encode(request)
        } catch {
            return nil
        }

        return await withTimeout(seconds: 30) { [weak self] () -> JsonRpcResponse? in
            guard let self = self else { return nil }
            do {
                var urlRequest = URLRequest(url: url)
                urlRequest.httpMethod = "POST"
                urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
                if let apiKey = apiKey, !apiKey.isEmpty {
                    urlRequest.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
                }
                urlRequest.httpBody = bodyData

                let (data, _) = try await self.session.data(for: urlRequest)
                return self.parseResponse(data)
            } catch {
                return nil
            }
        }
    }

    /// 解析 HTTP 响应数据为 `JsonRpcResponse`。
    ///
    /// 直接使用 `JSONDecoder` 解码，`result` 字段由 `AnyCodable` 处理任意 JSON 结构，
    /// `error` 字段缺失时为 nil。
    ///
    /// - Parameter data: 响应体原始数据
    /// - Returns: 解析成功返回 `JsonRpcResponse`；JSON 格式错误或字段缺失返回 nil
    private func parseResponse(_ data: Data) -> JsonRpcResponse? {
        do {
            let response = try decoder.decode(JsonRpcResponse.self, from: data)
            return response
        } catch {
            return nil
        }
    }

    /// 解析工具输入 Schema。
    ///
    /// 将 `tools/list` 响应中的 `inputSchema` 字典转换为 `McpToolSchema`，
    /// 提取 `type`（默认 "object"）、`properties` 映射与 `required` 数组。
    /// 每个属性映射为 `McpToolProperty`，包含 `type`、`description`、`enumValues`。
    ///
    /// - Parameter map: 来自响应的 `inputSchema` 字典
    /// - Returns: 解析后的 `McpToolSchema`
    private func parseSchema(_ map: [String: Any]) -> McpToolSchema {
        var properties: [String: McpToolProperty] = [:]
        if let propsMap = map["properties"] as? [String: Any] {
            for (key, value) in propsMap {
                guard let propMap = value as? [String: Any] else { continue }
                properties[key] = McpToolProperty(
                    type: propMap["type"] as? String ?? "string",
                    description: propMap["description"] as? String ?? "",
                    enumValues: (propMap["enum"] as? [Any])?.compactMap { $0 as? String }
                )
            }
        }

        let required = (map["required"] as? [Any])?.compactMap { $0 as? String } ?? []

        return McpToolSchema(
            type: map["type"] as? String ?? "object",
            properties: properties,
            required: required
        )
    }

    /// 带超时执行异步操作。
    ///
    /// 使用 `TaskGroup` 并发执行操作与超时定时器，任一完成即返回：
    /// - 操作先完成：返回操作结果
    /// - 定时器先完成：返回 nil 并取消操作
    ///
    /// - Parameters:
    ///   - seconds: 超时秒数
    ///   - operation: 要执行的异步操作（返回可空结果）
    /// - Returns: 操作完成返回其结果；超时返回 nil
    private func withTimeout<T: Sendable>(
        seconds: TimeInterval,
        operation: @escaping @Sendable () async -> T?
    ) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            // 实际操作任务
            group.addTask {
                return await operation()
            }
            // 超时定时器任务
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return nil
            }
            // 取第一个完成的结果
            let result = await group.next() ?? nil
            group.cancelAll()
            return result
        }
    }
}
