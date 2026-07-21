import Foundation

// MARK: - MCP Models
// 对应 protocol/schemas/mcp-schema.json
// 基于 Model Context Protocol 2025-03-26 规范

/// MCP 传输类型
enum McpTransportType: String, Codable {
    case stdio = "STDIO"
    case sse = "SSE"
    case http = "HTTP"
}

/// MCP 服务器能力
struct McpServerCapabilities: Codable, Equatable, Sendable {
    var tools: Bool = false
    var resources: Bool = false
    var prompts: Bool = false
    var logging: Bool = false
}

/// MCP Server 连接配置
struct McpServer: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var name: String
    var transportUrl: String
    var transportType: McpTransportType = .sse
    var apiKey: String? = nil
    var isEnabled: Bool = true
    var capabilities: McpServerCapabilities = McpServerCapabilities()
}

/// 工具输入参数的 JSON Schema
struct McpToolProperty: Codable, Equatable, Sendable {
    var type: String
    var description: String = ""
    var enumValues: [String]? = nil

    enum CodingKeys: String, CodingKey {
        case type, description
        case enumValues = "enum"
    }
}

struct McpToolSchema: Codable, Equatable, Sendable {
    var type: String = "object"
    var properties: [String: McpToolProperty] = [:]
    var required: [String] = []
}

/// MCP 工具定义
struct McpTool: Codable, Identifiable, Equatable, Sendable {
    var id: String { "\(serverId):\(name)" }
    var name: String
    var description: String
    var inputSchema: McpToolSchema
    var serverId: String
}

/// MCP 内容类型（判别联合）
enum McpContent: Codable, Equatable, Sendable {
    case text(String)
    case image(data: String, mimeType: String)
    case audio(data: String, mimeType: String)

    private enum CodingKeys: String, CodingKey { case type, text, data, mimeType }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "text":
            self = .text(try container.decode(String.self, forKey: .text))
        case "image":
            self = .image(data: try container.decode(String.self, forKey: .data),
                         mimeType: try container.decode(String.self, forKey: .mimeType))
        case "audio":
            self = .audio(data: try container.decode(String.self, forKey: .data),
                         mimeType: try container.decode(String.self, forKey: .mimeType))
        default:
            throw DecodingError.dataCorruptedError(forKey: .type, in: container, debugDescription: "Unknown content type: \(type)")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .text(let text):
            try container.encode("text", forKey: .type)
            try container.encode(text, forKey: .text)
        case .image(let data, let mimeType):
            try container.encode("image", forKey: .type)
            try container.encode(data, forKey: .data)
            try container.encode(mimeType, forKey: .mimeType)
        case .audio(let data, let mimeType):
            try container.encode("audio", forKey: .type)
            try container.encode(data, forKey: .data)
            try container.encode(mimeType, forKey: .mimeType)
        }
    }
}

/// 工具调用结果
struct McpToolResult: Equatable {
    var content: [McpContent]
    var isError: Bool = false

    /// 提取所有文本内容拼接为单个字符串
    var asText: String {
        content.compactMap { item in
            if case .text(let text) = item { return text }
            return nil
        }.joined(separator: "\n")
    }
}

// MARK: - JSON-RPC 2.0

struct JsonRpcRequest: Codable, Sendable {
    var jsonrpc: String = "2.0"
    var id: Int
    var method: String
    var params: [String: AnyCodable]? = nil
}

struct JsonRpcResponse: Codable, Sendable {
    var jsonrpc: String = "2.0"
    var id: Int
    var result: AnyCodable? = nil
    var error: JsonRpcError? = nil
}

struct JsonRpcError: Codable, Error, Sendable {
    var code: Int
    var message: String
}

/// 用于在 JSON 中承载任意值的包装类型
///
/// `@unchecked Sendable` — `value: Any` 在类型系统层面不具备 Sendable，
/// 但本类型仅通过 `init(from:)` 解码 JSON 得到，所存值均为 Sendable
/// 基础类型（Bool / Int / Double / String / NSNull）或其嵌套字典/数组。
/// 在跨 actor 传递 JSON-RPC 参数时需要 Sendable，故显式声明。
struct AnyCodable: Codable, Equatable, @unchecked Sendable {
    let value: Any

    init(_ value: Any) { self.value = value }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let v = try? container.decode(Bool.self) { self.value = v }
        else if let v = try? container.decode(Int.self) { self.value = v }
        else if let v = try? container.decode(Double.self) { self.value = v }
        else if let v = try? container.decode(String.self) { self.value = v }
        else if let v = try? container.decode([String: AnyCodable].self) {
            self.value = v.mapValues { $0.value }
        }
        else if let v = try? container.decode([AnyCodable].self) {
            self.value = v.map { $0.value }
        }
        else { self.value = NSNull() }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch value {
        case let v as Bool: try container.encode(v)
        case let v as Int: try container.encode(v)
        case let v as Double: try container.encode(v)
        case let v as String: try container.encode(v)
        case let v as [String: Any]: try container.encode(v.mapValues { AnyCodable($0) })
        case let v as [Any]: try container.encode(v.map { AnyCodable($0) })
        case is NSNull: try container.encodeNil()
        default: try container.encodeNil()
        }
    }

    static func == (lhs: AnyCodable, rhs: AnyCodable) -> Bool {
        String(describing: lhs.value) == String(describing: rhs.value)
    }

    /// 以 Map 形式访问
    var asDict: [String: Any]? { value as? [String: Any] }
    var asArray: [Any]? { value as? [Any] }
    var asString: String? { value as? String }
    var asBool: Bool? { value as? Bool }
    var asInt: Int? { value as? Int }
}
