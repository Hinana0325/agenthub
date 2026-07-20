import Foundation

// MARK: - OpenAI HTTP Transport
// 对应 Android OpenAIHttpTransport
// 协议契约: transport/http-api.md + transport/sse-protocol.md

/// OpenAI 兼容的 HTTP + SSE 传输实现。
///
/// 协议规范：
/// - 端点: POST {serverUrl}/v1/chat/completions
/// - 请求头: Authorization: Bearer {apiKey}, Content-Type: application/json
/// - 请求体: {model, messages, stream: true, temperature, max_tokens}
/// - SSE 解析: choices[0].delta.content (增量), choices[0].message.content (全量回退)
/// - 终止: data: [DONE]
/// - 超时: connect 10s, request 120s, socket 30s
/// - 历史: 客户端侧滑动窗口，每 sessionId 最多 20 条
final class OpenAIHTTPTransport: AgentTransport, @unchecked Sendable {

    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    private var config: AgentConfig?
    private var e2eKey: String?

    /// 事件流续期
    private var eventContinuation: AsyncStream<AgentEvent>.Continuation?

    /// 客户端侧会话历史 (sessionId -> 消息列表)，滑动窗口上限 20
    private var history: [String: [ConversationMessage]] = [:]
    private let historyLock = NSLock()
    private let maxHistory = 20

    /// 连接状态
    private(set) var connectionState = AgentConnectionState()

    lazy var events: AsyncStream<AgentEvent> = {
        AsyncStream { continuation in
            self.eventContinuation = continuation
        }
    }()

    private struct ConversationMessage: Codable {
        let role: String
        let content: String
    }

    /// SSE 响应中的 choices[0] 结构
    private struct SSEChunk: Decodable {
        let choices: [Choice]
        struct Choice: Decodable {
            let delta: Delta?
            let message: Delta?
        }
        struct Delta: Decodable {
            let content: String?
        }
    }

    // MARK: - Init

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120  // request 120s
        config.timeoutIntervalForResource = 120
        config.waitsForConnectivity = true
        self.session = URLSession(configuration: config)
    }

    // MARK: - Connect

    func connect(config: AgentConfig, e2eKey: String?) async {
        self.config = config
        self.e2eKey = e2eKey

        // HTTP 传输是无状态连接，connect 仅做探活
        let reachable = await probeEndpoint(serverUrl: config.serverUrl, apiKey: config.apiKey)
        connectionState = AgentConnectionState(
            isConnected: reachable,
            serverUrl: config.serverUrl,
            agentType: config.type,
            latency: 0
        )

        if reachable {
            emit(.connected(serverUrl: config.serverUrl, agentType: config.type))
        } else {
            emit(.error(message: "Failed to reach server"))
        }
    }

    // MARK: - Send Message

    func sendMessage(sessionId: String, content: String) async throws {
        guard let config = config else {
            emit(.error(message: "Not connected"))
            return
        }

        // 更新客户端侧历史
        appendHistory(sessionId: sessionId, role: "user", content: content)

        // 构建请求 URL: {serverUrl}/v1/chat/completions
        let baseURL = config.serverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let urlString = "\(baseURL)/v1/chat/completions"
        guard let url = URL(string: urlString) else {
            emit(.error(message: "Invalid URL: \(urlString)"))
            return
        }

        // 构建请求体
        let messages = getHistory(sessionId: sessionId).map { msg in
            ["role": msg.role, "content": msg.content]
        }
        let requestBody: [String: Any] = [
            "model": config.model,
            "messages": messages,
            "stream": true,
            "temperature": config.temperature,
            "max_tokens": config.maxTokens
        ]

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: requestBody)

        // HTTP 重试：对可重试错误（5xx / 网络异常 / 超时）进行指数退避重试。
        // 不可重试：4xx 客户端错误、CancellationException。
        let maxAttempts = 3
        var attempt = 0
        var streamSucceeded = false
        var lastErrorMessage: String?

        while attempt < maxAttempts && !streamSucceeded {
            attempt += 1
            do {
                let (bytes, response) = try await session.bytes(for: request)
                guard let httpResponse = response as? HTTPURLResponse else {
                    if attempt < maxAttempts {
                        lastErrorMessage = "Invalid response (retry \(attempt)/\(maxAttempts))"
                    } else {
                        emit(.error(message: "Invalid response"))
                    }
                    continue
                }

                let statusCode = httpResponse.statusCode
                if statusCode != 200 {
                    // 4xx 客户端错误不重试
                    if (400...499).contains(statusCode) {
                        emit(.error(message: "HTTP \(statusCode)"))
                        return
                    }
                    // 5xx 服务端错误：可重试
                    if attempt < maxAttempts {
                        lastErrorMessage = "HTTP \(statusCode) (retry \(attempt)/\(maxAttempts))"
                    } else {
                        emit(.error(message: "HTTP \(statusCode)"))
                    }
                    continue
                }

                // SSE 流式解析
                var responseAccumulator = ""
                var dataBuffer = ""

                for try await line in bytes.lines {
                    // SSE 注释行 (以 : 开头)
                    if line.hasPrefix(":") { continue }

                    // 空行：派发已累积的 data 并重置
                    if line.isEmpty {
                        if !dataBuffer.isEmpty {
                            if dataBuffer == "[DONE]" {
                                break
                            }
                            if let delta = parseDelta(dataBuffer) {
                                responseAccumulator += delta
                                emit(.messageReceived(content: delta, isDelta: true))
                            }
                            dataBuffer = ""
                        }
                        continue
                    }

                    // data: 行
                    if line.hasPrefix("data:") {
                        let payload = line.dropFirst(5).drop(while: { $0 == " " })
                        if dataBuffer.isEmpty {
                            dataBuffer = String(payload)
                        } else {
                            dataBuffer += "\n" + payload
                        }
                    }
                }

                // 处理最后缓冲的数据
                if !dataBuffer.isEmpty && dataBuffer != "[DONE]" {
                    if let delta = parseDelta(dataBuffer) {
                        responseAccumulator += delta
                        emit(.messageReceived(content: delta, isDelta: true))
                    }
                }

                // 保存助手回复到历史
                if !responseAccumulator.isEmpty {
                    appendHistory(sessionId: sessionId, role: "assistant", content: responseAccumulator)
                }
                streamSucceeded = true
                emit(.streamComplete)

            } catch {
                // 网络异常 / 超时：可重试
                if attempt < maxAttempts {
                    lastErrorMessage = "\(error.localizedDescription) (retry \(attempt)/\(maxAttempts))"
                } else {
                    emit(.error(message: error.localizedDescription))
                }
            }

            // 指数退避：1s, 2s, 4s...
            if !streamSucceeded && attempt < maxAttempts {
                let backoff = min(UInt64(1_000_000_000 * (1 << (attempt - 1))), 8_000_000_000)
                try? await Task.sleep(nanoseconds: backoff)
            }
        }

        // 所有重试均失败
        if !streamSucceeded && lastErrorMessage != nil {
            emit(.error(message: "Request failed after \(maxAttempts) attempts: \(lastErrorMessage!)"))
        }
    }

    // MARK: - Disconnect / Shutdown

    func disconnect() {
        connectionState = AgentConnectionState(
            isConnected: false,
            serverUrl: connectionState.serverUrl,
            agentType: connectionState.agentType,
            latency: 0
        )
        emit(.disconnected(reason: "User disconnected"))
    }

    func shutdown() {
        disconnect()
        eventContinuation?.finish()
    }

    // MARK: - History Management

    func clearHistory(sessionId: String) async {
        historyLock.lock()
        history.removeValue(forKey: sessionId)
        historyLock.unlock()
    }

    func clearAllHistory() async {
        historyLock.lock()
        history.removeAll()
        historyLock.unlock()
    }

    private func appendHistory(sessionId: String, role: String, content: String) {
        historyLock.lock()
        var list = history[sessionId] ?? []
        list.append(ConversationMessage(role: role, content: content))
        // 滑动窗口：超过上限时从头部裁剪
        if list.count > maxHistory {
            list.removeFirst(list.count - maxHistory)
        }
        history[sessionId] = list
        historyLock.unlock()
    }

    private func getHistory(sessionId: String) -> [ConversationMessage] {
        historyLock.lock()
        let list = history[sessionId] ?? []
        historyLock.unlock()
        return list
    }

    // MARK: - SSE Parsing

    /// 解析 SSE data 负载，提取 choices[0].delta.content
    private func parseDelta(_ data: String) -> String? {
        guard let jsonData = data.data(using: .utf8) else { return nil }
        do {
            let chunk = try decoder.decode(SSEChunk.self, from: jsonData)
            // 优先 delta (流式)，其次 message (全量回退)
            return chunk.choices.first?.delta?.content
                ?? chunk.choices.first?.message?.content
        } catch {
            return nil
        }
    }

    // MARK: - Probe Endpoint

    /// 探活: GET {base}/v1/models
    /// 可达判定: 2xx/401/403/404 = 可达, 5xx/异常 = 不可达, 超时 5s
    private func probeEndpoint(serverUrl: String, apiKey: String) async -> Bool {
        let base = serverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let probePath = base.hasSuffix("/v1") ? "\(base)/models" : "\(base)/v1/models"
        guard let url = URL(string: probePath) else { return false }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 5  // 探活超时 5s

        do {
            let (_, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return false }
            return (200...299).contains(http.statusCode) ||
                   http.statusCode == 401 || http.statusCode == 403 || http.statusCode == 404
        } catch {
            return false
        }
    }

    // MARK: - Event Emission

    private func emit(_ event: AgentEvent) {
        eventContinuation?.yield(event)
    }
}
