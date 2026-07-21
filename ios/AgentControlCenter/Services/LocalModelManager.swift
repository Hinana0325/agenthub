import Foundation
import Observation
import os

// MARK: - LocalModelManager
// 对应 Android LocalModelManager — 本地模型发现管理器
//
// 职责：自动发现局域网或本机运行的 Ollama / LM Studio / llama.cpp 端点
// 通过并发探测默认端口列表，收集可用的本地推理服务及其模型列表

/// 本地模型发现管理器
/// 自动发现局域网或本机运行的 Ollama / LM Studio / llama.cpp 端点
@MainActor
@Observable
final class LocalModelManager {

    /// 已发现的本地端点
    var discoveredEndpoints: [DiscoveredEndpoint] = []

    /// SW-M3: 端点探测日志器（网络失败是预期行为，记 debug；JSON 解析失败记 warning）
    private static let logger = Logger(subsystem: "com.agentcontrolcenter.app.ios", category: "LocalModelManager")

    /// 是否正在扫描中
    var isScanning: Bool = false

    /// 默认扫描的本地端点列表
    ///
    /// 覆盖本机回环地址和常见的局域网网关地址（192.168.1.100）
    /// 可根据实际网络环境在后续版本中支持自定义扫描范围
    static let defaultEndpoints: [(name: String, url: String, type: DiscoveredEndpoint.EndpointType)] = [
        ("Ollama", "http://localhost:11434", .ollama),
        ("Ollama (Lan)", "http://192.168.1.100:11434", .ollama),
        ("LM Studio", "http://localhost:1234", .lmStudio),
        ("LM Studio (Lan)", "http://192.168.1.100:1234", .lmStudio),
        ("llama.cpp", "http://localhost:8080", .llamaCpp),
    ]

    // MARK: - 扫描

    /// 并发扫描所有默认端点
    ///
    /// 使用 `withTaskGroup` 对所有预设端点发起并发探测，
    /// 仅收集返回可用模型的端点。扫描期间 `isScanning` 为 `true`，
    /// 扫描结束后更新 `discoveredEndpoints`。
    func scan() async {
        isScanning = true
        var results: [DiscoveredEndpoint] = []

        await withTaskGroup(of: DiscoveredEndpoint?.self) { group in
            for endpoint in Self.defaultEndpoints {
                // [weak self] 避免循环引用：LocalModelManager 是 @MainActor 类，
                // 若扫描期间被释放（虽然实际不会，因为 AppState 持有），闭包不会延长其生命周期。
                // fetchModels 标记为 nonisolated，可在 group.addTask（非 MainActor 上下文）中直接调用，
                // 无需 await 跨隔离跳转。
                group.addTask { [weak self] in
                    guard let self else { return nil }
                    let models = await self.fetchModels(from: endpoint.url, type: endpoint.type)
                    // 仅当成功获取到至少一个模型时才认为端点可用
                    guard !models.isEmpty else { return nil }
                    return DiscoveredEndpoint(
                        id: "\(endpoint.type.rawValue)-\(endpoint.url)",
                        name: endpoint.name,
                        url: endpoint.url,
                        type: endpoint.type,
                        models: models,
                        isAvailable: true
                    )
                }
            }

            for await result in group {
                if let result { results.append(result) }
            }
        }

        discoveredEndpoints = results
        isScanning = false
    }

    // MARK: - 模型获取路由

    /// 根据端点类型路由到对应 API 获取模型列表
    ///
    /// `nonisolated`：纯 URL 抓取，不访问实例状态，可从任意 actor 调用。
    private nonisolated func fetchModels(from url: String, type: DiscoveredEndpoint.EndpointType) async -> [LocalModel] {
        switch type {
        case .ollama:
            return await fetchOllamaModels(from: url)
        case .lmStudio:
            return await fetchLmStudioModels(from: url)
        case .llamaCpp:
            return await fetchLlamaCppModels(from: url)
        }
    }

    // MARK: - Ollama

    /// Ollama: GET /api/tags
    ///
    /// 返回格式：
    /// ```json
    /// { "models": [{ "name": "llama3:8b", "size": 4661224676 }] }
    /// ```
    private nonisolated func fetchOllamaModels(from url: String) async -> [LocalModel] {
        // H-S4 修复：原代码 URL(string:) 直接构造未过 URLValidator。
        // 当前 url 来自 defaultEndpoints 静态数组，但方法签名接受任意字符串，
        // 未来扩展为用户输入即 SSRF 入口。补防御性校验。
        guard let url = URLValidator.validate("\(url)/api/tags", allowLocalhost: true) else { return [] }
        guard let (data, response) = try? await URLSession.shared.data(from: url),
              let http = response as? HTTPURLResponse, http.statusCode == 200 else { return [] }

        // Ollama API 响应结构
        struct OllamaResponse: Codable {
            let models: [OllamaModel]?
            struct OllamaModel: Codable {
                let name: String
                let size: Int64?
            }
        }

        // SW-M3: 网络层成功但 JSON 解析失败，说明服务返回了非预期格式，应记录
        do {
            let resp = try JSONDecoder().decode(OllamaResponse.self, from: data)
            guard let models = resp.models else { return [] }
            return models.map { LocalModel(id: $0.name, name: $0.name, size: Self.formatSize($0.size)) }
        } catch {
            Self.logger.warning("fetchOllamaModels: 响应解析失败 (\(url.absoluteString)): \(error.localizedDescription)")
            return []
        }
    }

    // MARK: - LM Studio

    /// LM Studio: GET /v1/models（兼容 OpenAI 格式）
    ///
    /// 返回格式：
    /// ```json
    /// { "data": [{ "id": "meta-llama/Llama-3-8B-Instruct-q4" }] }
    /// ```
    private nonisolated func fetchLmStudioModels(from url: String) async -> [LocalModel] {
        // H-S4 修复：补防御性 URLValidator 校验（与 fetchOllamaModels 一致）
        guard let url = URLValidator.validate("\(url)/v1/models", allowLocalhost: true) else { return [] }
        guard let (data, response) = try? await URLSession.shared.data(from: url),
              let http = response as? HTTPURLResponse, http.statusCode == 200 else { return [] }

        // LM Studio 兼容 OpenAI /v1/models 响应结构
        struct LmStudioResponse: Codable {
            let data: [LmModel]?
            struct LmModel: Codable {
                let id: String
            }
        }

        // SW-M3: 网络层成功但 JSON 解析失败，应记录
        do {
            let resp = try JSONDecoder().decode(LmStudioResponse.self, from: data)
            guard let models = resp.data else { return [] }
            return models.map { LocalModel(id: $0.id, name: $0.id, size: nil) }
        } catch {
            Self.logger.warning("fetchLmStudioModels: 响应解析失败 (\(url.absoluteString)): \(error.localizedDescription)")
            return []
        }
    }

    // MARK: - llama.cpp

    /// llama.cpp: GET /health
    ///
    /// llama.cpp 的 /health 端点不返回模型列表，仅用于确认服务可用性。
    /// 成功时返回一个占位模型条目，表示服务已就绪。
    private nonisolated func fetchLlamaCppModels(from url: String) async -> [LocalModel] {
        // H-S4 修复：补防御性 URLValidator 校验（与 fetchOllamaModels 一致）
        guard let url = URLValidator.validate("\(url)/health", allowLocalhost: true) else { return [] }
        guard let (_, response) = try? await URLSession.shared.data(from: url),
              let http = response as? HTTPURLResponse, http.statusCode == 200 else { return [] }
        // llama.cpp health 端点不返回模型列表，仅确认可用
        return [LocalModel(id: "default", name: "默认模型", size: nil)]
    }

    // MARK: - 工具方法

    /// 将字节数格式化为人类可读的 GB 字符串
    /// - Parameter bytes: 文件大小（字节数），nil 时返回 nil
    /// - Returns: 格式化后的字符串，如 "4.3 GB"
    private static func formatSize(_ bytes: Int64?) -> String? {
        guard let bytes else { return nil }
        let gb = Double(bytes) / 1_073_741_824.0
        return String(format: "%.1f GB", gb)
    }

    // MARK: - 数据模型

    /// 已发现的本地推理端点
    struct DiscoveredEndpoint: Identifiable, Codable {
        let id: String
        let name: String
        let url: String
        let type: EndpointType
        var models: [LocalModel]
        var isAvailable: Bool

        /// 端点类型枚举
        enum EndpointType: String, Codable, CaseIterable {
            case ollama = "Ollama"
            case lmStudio = "LM Studio"
            case llamaCpp = "llama.cpp"
        }
    }

    /// 本地模型信息
    struct LocalModel: Identifiable, Codable {
        let id: String
        let name: String
        /// 模型文件大小（格式化后的可读字符串，如 "4.3 GB"），部分端点无法获取时为 nil
        let size: String?
    }
}
