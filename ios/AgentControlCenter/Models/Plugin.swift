import Foundation

// MARK: - Plugin Model
// 对应 protocol/schemas/plugin-schema.json

/// 插件动作类型
enum PluginActionType: String, Codable {
    case http = "http"
    case broadcast = "broadcast"
    case workflow = "workflow"
    case none = "none"
}

/// 插件动作。判别联合，通过 type 字段区分。
enum PluginAction: Codable, Equatable {
    case httpCall(HttpCall)
    case broadcast(Broadcast)
    case workflow(WorkflowAction)

    struct HttpCall: Codable, Equatable {
        var url: String
        var method: String = "GET"
        var headers: [String: String] = [:]
        var bodyTemplate: String? = nil
    }

    struct Broadcast: Codable, Equatable {
        var action: String
        var extras: [String: String] = [:]
    }

    struct WorkflowAction: Codable, Equatable {
        var promptTemplate: String
    }

    var type: PluginActionType {
        switch self {
        case .httpCall: return .http
        case .broadcast: return .broadcast
        case .workflow: return .workflow
        }
    }

    private enum CodingKeys: String, CodingKey { case type }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(PluginActionType.self, forKey: .type)
        switch type {
        case .http:
            self = .httpCall(try HttpCall(from: decoder))
        case .broadcast:
            self = .broadcast(try Broadcast(from: decoder))
        case .workflow:
            self = .workflow(try WorkflowAction(from: decoder))
        case .none:
            throw DecodingError.dataCorruptedError(forKey: .type, in: container, debugDescription: "none type has no action")
        }
    }

    func encode(to encoder: Encoder) throws {
        switch self {
        case .httpCall(let v): try v.encode(to: encoder)
        case .broadcast(let v): try v.encode(to: encoder)
        case .workflow(let v): try v.encode(to: encoder)
        }
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(type, forKey: .type)
    }
}

/// 插件
struct Plugin: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var description: String
    var icon: String
    var isEnabled: Bool = true
    var permissions: [String] = []
    var version: String = "1.0.0"
    var action: PluginAction? = nil
}

/// 插件执行结果
struct PluginResult: Equatable {
    var content: String
    var sendToAgent: Bool = false
}

// MARK: - File Transfer Model
// 对应 protocol/schemas/file-transfer-schema.json

enum FileTransferDirection: String, Codable {
    case upload = "upload"
    case download = "download"
}

enum FileTransferStatus: String, Codable {
    case pending = "pending"
    case uploading = "uploading"
    case completed = "completed"
    case failed = "failed"
    case cancelled = "cancelled"
}

struct FileMetadata: Codable, Equatable {
    var filename: String
    var mimeType: String
    var size: Int
    var checksum: String
}

struct FileTransferRequest: Codable, Identifiable {
    var id: String
    var direction: FileTransferDirection
    var metadata: FileMetadata
    var data: String?
    var uploadUrl: String? = nil
    var status: FileTransferStatus = .pending
}
