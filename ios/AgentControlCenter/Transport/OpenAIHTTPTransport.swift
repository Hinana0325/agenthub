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

    private var session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    private var config: AgentConfig?

    /// E2E 密钥的内部存储（锁保护，跨 actor 安全访问）。
    /// 与 WebSocketTransport 一致：Swift 6 strict concurrency 下 e2eKey 可能被
    /// AppState 订阅线程调用 updateE2eKey 更新，同时被 sendMessage 读取。
    /// 改为通过计算属性 + NSLock 保护读写，对齐 Android @Volatile 语义。
    private let e2eKeyLock = NSLock()
    private var _e2eKey: String?
    private var e2eKey: String? {
        get {
            e2eKeyLock.lock()
            defer { e2eKeyLock.unlock() }
            return _e2eKey
        }
        set {
            e2eKeyLock.lock()
            _e2eKey = newValue
            e2eKeyLock.unlock()
        }
    }

    /// 事件流续期
    private var eventContinuation: AsyncStream<AgentEvent>.Continuation?

    /// 客户端侧会话历史 (sessionId -> 消息列表)，滑动窗口上限 20
    private var history: [String: [ConversationMessage]] = [:]
    private let historyLock = NSLock()
    private let maxHistory = 20

    /// session 锁，保护 cancel/重建路径
    private let sessionLock = NSLock()

    /// continuation 锁，保护 eventContinuation 的读写（emit / shutdown / onTermination）
    private let continuationLock = NSLock()

    /// 连接状态
    private(set) var connectionState = AgentConnectionState()

    lazy var events: AsyncStream<AgentEvent> = {
        AsyncStream { continuation in
            self.continuationLock.lock()
            self.eventContinuation = continuation
            self.continuationLock.unlock()
            // 消费者取消迭代时（视图消失 / Task 取消 / finish() 调用）触发，
            // 清空存储的 continuation，避免后续 emit() 写入已结束的流。
            // 使用 [weak self] 避免循环引用：
            //   transport -> eventContinuation -> onTermination closure -> transport
            continuation.onTermination = { @Sendable [weak self] _ in
                guard let self else { return }
                self.continuationLock.lock()
                self.eventContinuation = nil
                self.continuationLock.unlock()
            }
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
        // H16 TODO（TLS 证书钉扎 / SPKI Pinning）：当前使用默认 URLSessionConfiguration，
        // 无 URLSessionDelegate，仅依赖系统 CA 链校验，无法防御 MITM。
        // 后续应通过 URLSessionDelegate 校验服务端证书 SPKI hash 与预置 pin 列表，
        // 对齐 Android NetworkSecurityConfig。暂缓实现：详见 protocol/transport/tls-pinning.md（待补）。
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
            // C3 修复：探活失败 → TRANSPORT_CONNECT_FAILED (1001)
            emit(.error(code: .transportConnectFailed, message: "Failed to reach server"))
        }
    }

    // MARK: - 热更新 E2E 密钥

    /// 运行时热更新 E2E 密钥，无需断开重连。
    ///
    /// 与 Android / WebSocketTransport.updateE2eKey 对齐：
    /// - 仅更新内部 e2eKey 字段（通过 e2eKeyLock 保护写入）
    /// - HTTP 传输虽无持久连接，但 e2eKey 仍会影响下次 sendMessage 的加密路径
    /// - 传 nil 表示禁用 E2E 加密
    func updateE2eKey(_ key: String?) {
        e2eKey = key
    }

    // MARK: - Send Message

    func sendMessage(sessionId: String, content: String) async throws {
        guard let config = config else {
            // C3 修复：未连接 → TRANSPORT_DISCONNECTED (1004)
            emit(.error(code: .transportDisconnected, message: "Not connected"))
            return
        }

        // 更新客户端侧历史
        appendHistory(sessionId: sessionId, role: "user", content: content)

        // URL 构造 + SSRF 校验
        // 兼容 4 种 base URL 形式（参考 Android OpenAIHttpTransport.buildChatCompletionsUrl）：
        //   http://host:port        → http://host:port/v1/chat/completions（标准 OpenAI）
        //   http://host:port/v1     → http://host:port/v1/chat/completions（已含 /v1）
        //   http://host:port/api/v1 → http://host:port/api/v1/chat/completions（OpenWebUI）
        //   http://host:port/api    → http://host:port/api/v1/chat/completions（OpenWebUI 简写）
        let urlString = buildChatCompletionsUrl(serverUrl: config.serverUrl)
        guard let url = URLValidator.validate(urlString, allowLocalhost: true) else {
            // C3 修复：URL 无效阻断连接 → TRANSPORT_CONNECT_FAILED (1001)
            // 注：注册表无 TRANSPORT_INVALID_URL，归入 1001（连接失败）
            emit(.error(code: .transportConnectFailed, message: "服务地址格式错误或不被允许"))
            return
        }

        // 构建请求体：注入 systemPrompt（若非空）作为 messages 数组首条
        var messages: [[String: String]] = []
        if !config.systemPrompt.isEmpty {
            messages.append(["role": "system", "content": config.systemPrompt])
        }
        messages.append(contentsOf: getHistory(sessionId: sessionId).map { msg in
            ["role": msg.role, "content": msg.content]
        })
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
        // SSE 协议要求显式声明 Accept: text/event-stream，部分代理/CDN 据此启用流式透传
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
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
            // 用户取消生成时，通过 withTaskCancellationHandler 显式 cancel 底层 URLSessionDataTask。
            // 仅 cancel Task 无法终止 session.bytes(for:) 的 AsyncSequence，必须显式持有 task。
            do {
                let (bytes, response) = try await withTaskCancellationHandler {
                    // CI-fix: withTaskCancellationHandler 的 operation 闭包签名为
                    // `() async throws -> T`，调用 `session.bytes(for:)`（async throws）
                    // 需显式 `try await`，否则报 "call can throw, but it is not marked
                    // with 'try'" + "expression is 'async' but is not marked with 'await'"。
                    try await session.bytes(for: request)
                } onCancel: {
                    // Task 取消时，invalidateAndCancel 终止 session 上所有 task，
                    // 使 bytes AsyncSequence 抛 URLError(.cancelled) 退出 for await。
                    // 注意：此处无法直接访问局部 task（session.bytes 不返回 task），
                    // 用 session.invalidateAndCancel() 是最稳妥的方式。
                    // session 在下一次 sendMessage 时需要重建（已通过懒加载方式规避，
                    // 这里通过 cancelCurrentRequest() 标记）
                    self.cancelCurrentRequest()
                }

                guard let httpResponse = response as? HTTPURLResponse else {
                    if attempt < maxAttempts {
                        lastErrorMessage = "Invalid response (retry \(attempt)/\(maxAttempts))"
                    } else {
                        // C3 修复：响应非 HTTP（解析失败）→ PROTOCOL_PARSE_ERROR (2003)
                        emit(.error(code: .protocolParseError, message: "Invalid response"))
                    }
                    continue
                }

                let statusCode = httpResponse.statusCode
                if statusCode != 200 {
                    // 401/403 鉴权失败：不重试，标记断开
                    if statusCode == 401 || statusCode == 403 {
                        connectionState = AgentConnectionState(
                            isConnected: false,
                            serverUrl: config.serverUrl,
                            agentType: config.type,
                            latency: 0
                        )
                        // C3 修复：401/403 → TRANSPORT_AUTH_FAILED (1002)
                        emit(.error(code: .transportAuthFailed, message: "鉴权失败 (HTTP \(statusCode))"))
                        return
                    }
                    // 429 限流：读 Retry-After 头退避重试
                    if statusCode == 429 {
                        let retryAfter = (httpResponse.value(forHTTPHeaderField: "Retry-After")
                                          .flatMap(Double.init)) ?? 2.0
                        if attempt < maxAttempts {
                            lastErrorMessage = "HTTP 429 (retry \(attempt)/\(maxAttempts))"
                            try await Task.sleep(nanoseconds: UInt64(retryAfter * 1_000_000_000))
                        } else {
                            // C3 修复：429 重试耗尽 → TRANSPORT_CONNECT_FAILED (1001)
                            emit(.error(code: .transportConnectFailed, message: "请求被限流 (HTTP 429)"))
                        }
                        continue
                    }
                    // 4xx 客户端错误不重试
                    if (400...499).contains(statusCode) {
                        // C3 修复：4xx 客户端错误（请求格式问题）→ PROTOCOL_INVALID_MESSAGE (2001)
                        emit(.error(code: .protocolInvalidMessage, message: "HTTP \(statusCode)"))
                        return
                    }
                    // 5xx 服务端错误：可重试
                    if attempt < maxAttempts {
                        lastErrorMessage = "HTTP \(statusCode) (retry \(attempt)/\(maxAttempts))"
                    } else {
                        // C3 修复：5xx 重试耗尽 → TRANSPORT_CONNECT_FAILED (1001)
                        emit(.error(code: .transportConnectFailed, message: "HTTP \(statusCode)"))
                    }
                    continue
                }

                // SSE 流式解析
                var responseAccumulator = ""
                var dataBuffer = ""
                var sawSSEData = false   // 是否见到过 data: 行
                var singleJsonBuffer = "" // 非 SSE 整包回退累积器

                for try await line in bytes.lines {
                    // 用户取消 → for await 抛 URLError.cancelled → 跳出
                    if Task.isCancelled { break }

                    // SSE 注释行 (以 : 开头)
                    if line.hasPrefix(":") { continue }

                    // 空行：派发已累积的 data 并重置
                    if line.isEmpty {
                        if !dataBuffer.isEmpty {
                            sawSSEData = true
                            if dataBuffer == "[DONE]" {
                                dataBuffer = ""
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
                    } else {
                        // 非 SSE 行（服务端忽略 stream:true 返回单个 JSON 整包响应）
                        // 累积到 singleJsonBuffer 以便流结束后整包回退处理
                        singleJsonBuffer += line
                    }
                }

                // 处理最后缓冲的 SSE data
                if !dataBuffer.isEmpty && dataBuffer != "[DONE]" {
                    if let delta = parseDelta(dataBuffer) {
                        responseAccumulator += delta
                        emit(.messageReceived(content: delta, isDelta: true))
                    }
                }

                // 整包回退：服务端未发 SSE data 时，把 singleJsonBuffer 当作单个 JSON 响应解析
                if !sawSSEData && !singleJsonBuffer.isEmpty && responseAccumulator.isEmpty {
                    if let fullText = parseDelta(singleJsonBuffer) {
                        responseAccumulator = fullText
                        // 协议规定：全量回退分支应派发 isDelta=false
                        emit(.messageReceived(content: fullText, isDelta: false))
                    }
                }

                // 保存助手回复到历史
                if !responseAccumulator.isEmpty {
                    appendHistory(sessionId: sessionId, role: "assistant", content: responseAccumulator)
                }
                streamSucceeded = true
                emit(.streamComplete)

            } catch {
                // 用户主动取消：不重试，不报错（ChatView 已通过 stopGenerating 处理 UI）
                if Task.isCancelled {
                    return
                }
                // 网络异常 / 超时：可重试
                if attempt < maxAttempts {
                    lastErrorMessage = "\(error.localizedDescription) (retry \(attempt)/\(maxAttempts))"
                } else {
                    // C3 修复：网络异常重试耗尽 → TRANSPORT_CONNECT_FAILED (1001)
                    emit(.error(code: .transportConnectFailed, message: error.localizedDescription))
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
            // C3 修复：重试耗尽 → TRANSPORT_CONNECT_FAILED (1001)
            emit(.error(code: .transportConnectFailed, message: "Request failed after \(maxAttempts) attempts: \(lastErrorMessage!)"))
        }
    }

    /// 取消当前进行中的 HTTP 请求（用户点 Stop 时调用）。
    /// session.bytes(for:) 返回的 AsyncSequence 不响应 Task.cancel()，
    /// 唯一可靠方案是使 session 失效并重建。下一次 sendMessage 会用新 session。
    private func cancelCurrentRequest() {
        sessionLock.lock()
        defer { sessionLock.unlock() }
        // invalidateAndCancel 会使所有 in-flight task 立即 cancel，
        // bytes 的 for await 会抛 URLError(.cancelled) 退出
        session.invalidateAndCancel()
        // 重建 session 供下次使用
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 120
        cfg.timeoutIntervalForResource = 120
        cfg.waitsForConnectivity = true
        session = URLSession(configuration: cfg)
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
        // 终止任何 in-flight HTTP 流（用户取消生成 / 视图退出时）
        sessionLock.lock()
        session.invalidateAndCancel()
        sessionLock.unlock()
        continuationLock.lock()
        eventContinuation?.finish()
        continuationLock.unlock()
        // L-3 修复：显式清空 config / e2eKey，避免 transport 实例长生命周期持有
        // 导致 apiKey 在内存中残留（潜在泄漏面）
        self.config = nil
        self.e2eKey = nil
    }

    // MARK: - History Management

    func clearHistory(sessionId: String) async {
        // CI-fix: Swift 6 strict concurrency 下 `NSLock.lock()` / `.unlock()` 标记为
        // `@_unavailableFromAsync`，禁止从 async 上下文直接调用。改用 `NSLocking`
        // 扩展提供的 `withLock(_:)` —— 它是同步函数，内部调 lock/unlock 不会被
        // async 检查器拦截，且保证异常路径下也能 unlock（语义等价、行为更安全）。
        // 返回 Void 但 Swift 6 仍要求显式忽略结果（@_Concurrency 安全检查）。
        let _: Void = historyLock.withLock {
            history.removeValue(forKey: sessionId)
        }
    }

    func clearAllHistory() async {
        let _: Void = historyLock.withLock {
            history.removeAll()
        }
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

    // MARK: - URL Construction

    /// 构造 chat completions 端点 URL（参考 Android OpenAIHttpTransport.buildChatCompletionsUrl）。
    ///
    /// 兼容多种 base URL 形式：
    /// - `http://host:port` → `http://host:port/v1/chat/completions`（标准 OpenAI）
    /// - `http://host:port/v1` → `http://host:port/v1/chat/completions`（已含 /v1）
    /// - `http://host:port/api/v1` → `http://host:port/api/v1/chat/completions`（OpenWebUI）
    /// - `http://host:port/api` → `http://host:port/api/v1/chat/completions`（OpenWebUI 简写）
    private func buildChatCompletionsUrl(serverUrl: String) -> String {
        let base = serverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if base.hasSuffix("/api/v1") {
            return "\(base)/chat/completions"
        } else if base.hasSuffix("/v1") {
            return "\(base)/chat/completions"
        } else if base.hasSuffix("/api") {
            return "\(base)/v1/chat/completions"
        } else {
            return "\(base)/v1/chat/completions"
        }
    }

    // MARK: - Probe Endpoint

    /// 探活: GET {base}/v1/models
    /// 可达判定: 2xx/401/403/404 = 可达, 5xx/异常 = 不可达, 超时 5s
    //
    // L-6 TODO（区分可达与已鉴权）：当前仅返回 Bool（可达与否），
    // 未在 connectionState 中区分 isReachable 与 isAuthenticated。
    // 2xx 表示可达且已鉴权；401/403 表示可达但鉴权失败；404 表示可达但路径不存在。
    // 后续应将 AgentConnectionState 扩展 isAuthenticated 字段（或返回探活枚举：
    // reachable+authed / reachable+unauth / unreachable），让 UI 能区分提示
    // "无法连接服务器" vs "API Key 无效"。当前改动会牵涉 Equatable/Sendable 同步
    // 与所有 transport 的 connectionState 构造点，暂缓实现，仅标注 TODO。
    private func probeEndpoint(serverUrl: String, apiKey: String) async -> Bool {
        // 兼容 /v1、/api/v1、/api 前缀（OpenWebUI 用 /api/v1/models），
        // 与 buildChatCompletionsUrl 的 4 种 base URL 形式保持一致。
        let base = serverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let probePath: String
        if base.hasSuffix("/api/v1") {
            probePath = "\(base)/models"
        } else if base.hasSuffix("/v1") {
            probePath = "\(base)/models"
        } else if base.hasSuffix("/api") {
            probePath = "\(base)/v1/models"
        } else {
            probePath = "\(base)/v1/models"
        }
        // H-S2 修复：原代码用 URL(string:) 直接构造，未调用 URLValidator.validate，
        // 攻击者可配置 serverUrl=http://169.254.169.254/... 让 probe 携带 Bearer apiKey
        // 访问云元数据服务。改为统一过 URLValidator（与 sendMessage 一致）。
        guard let url = URLValidator.validate(probePath, allowLocalhost: true) else { return false }

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
        continuationLock.lock()
        defer { continuationLock.unlock() }
        eventContinuation?.yield(event)
    }
}
