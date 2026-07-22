import Foundation

// MARK: - ComfyUITransport
// 对应 Android com.agentcontrolcenter.app.transport.comfyui.ComfyUITransport + ComfyApiClient
//
// ComfyUI 传输层 — 适配基于节点的图像生成工作流引擎。
//
// ComfyUI 无聊天接口，API 范式为「提交工作流 → 轮询结果 → 下载图片」。
// 本传输层提供两种模式（由 sendMessage 的 content 自动判断）：
//
// 1. 文本→图像模式（content 不以 `{` 开头）：
//    用户输入作为正向提示词，由 ComfyWorkflowBuilder 构造默认文生图工作流。
//
// 2. 工作流 JSON 模式（content 以 `{` 开头）：
//    用户直接提供 ComfyUI API 格式的工作流 JSON，原样提交到 `/prompt`。
//    适用于需要自定义节点/参数的高级用户。
//
// 结果图片通过 `/view` 端点下载，转 base64 后以 markdown 图片语法返回：
// `![ComfyUI](data:image/png;base64,...)`

/// ComfyUI 传输实现。
///
/// HTTP 操作（探活 / 提交 / 轮询 / 下载）均通过 URLSession 完成，
/// 工作流构造委托给 `ComfyWorkflowBuilder`，图片提取委托给 `ComfyImageOutputExtractor`。
final class ComfyUITransport: AgentTransport, @unchecked Sendable {

    private var session: URLSession

    /// 当前 Agent 配置。connect() 写入，disconnect()/shutdown() 清空。
    /// 与 OpenAIHTTPTransport 一致不加锁：HTTP 传输无持久连接，config 仅在
    /// sendMessage 起始处读取一次并捕获为局部常量，避免中途被清空。
    private var config: AgentConfig?

    /// E2E 密钥的内部存储（锁保护，跨 actor 安全访问）。
    /// 与 OpenAIHTTPTransport / WebSocketTransport 一致：Swift 6 strict concurrency 下
    /// e2eKey 可能被 AppState 订阅线程调用 updateE2eKey 更新，同时被 sendMessage 读取。
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

    /// continuation 锁，保护 eventContinuation 的读写（emit / shutdown / onTermination）
    private let continuationLock = NSLock()

    /// session 锁，保护 shutdown 路径的 session invalidate
    private let sessionLock = NSLock()

    /// 连接状态（与 OpenAIHTTPTransport 一致，不加锁：HTTP 传输仅在 connect 写入）
    private(set) var connectionState = AgentConnectionState()

    lazy var events: AsyncStream<AgentEvent> = {
        AsyncStream { continuation in
            self.continuationLock.lock()
            self.eventContinuation = continuation
            self.continuationLock.unlock()
            // 消费者取消迭代时（视图消失 / Task 取消 / finish() 调用）触发，
            // 清空存储的 continuation，避免后续 emit() 写入已结束的流。
            continuation.onTermination = { @Sendable [weak self] _ in
                guard let self else { return }
                self.continuationLock.lock()
                self.eventContinuation = nil
                self.continuationLock.unlock()
            }
        }
    }()

    // MARK: - Init

    init() {
        let config = URLSessionConfiguration.default
        // 单次请求超时 30s（覆盖探活 / 提交 / 单次轮询 / 下载）；
        // 探活另用 request.timeoutInterval = 5 覆盖。
        config.timeoutIntervalForRequest = 30
        // 整个资源超时 300s，给轮询循环（120 次 × 3s ≈ 6 分钟）留足空间
        config.timeoutIntervalForResource = 300
        config.waitsForConnectivity = true
        self.session = URLSession(configuration: config)
    }

    // MARK: - Connect

    func connect(config: AgentConfig, e2eKey: String?) async {
        self.config = config
        self.e2eKey = e2eKey

        let base = config.serverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let reachable = await checkHealth(base: base, apiKey: config.apiKey)
        connectionState = AgentConnectionState(
            isConnected: reachable,
            serverUrl: config.serverUrl,
            agentType: config.type,
            latency: 0
        )

        if reachable {
            emit(.connected(serverUrl: config.serverUrl, agentType: config.type))
        } else {
            emit(.error(code: .transportConnectFailed, message: "无法连接到 ComfyUI 服务端"))
        }
    }

    // MARK: - 热更新 E2E 密钥

    func updateE2eKey(_ key: String?) {
        e2eKey = key
    }

    // MARK: - Send Message

    func sendMessage(sessionId: String, content: String) async throws {
        guard let config = config else {
            emit(.error(code: .agentConfigMissing, message: "Not connected"))
            return
        }

        let base = config.serverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        // 1. 构造工作流 JSON
        guard let workflowJson = buildWorkflow(content: content, config: config) else {
            // buildWorkflow 已在解析失败时发送错误事件
            return
        }

        emit(.messageReceived(content: "正在提交工作流到 ComfyUI...\n", isDelta: true))

        // 2. 提交工作流 → 获取 prompt_id
        guard let promptId = await submitWorkflow(
            base: base,
            workflow: workflowJson,
            apiKey: config.apiKey
        ) else {
            // submitWorkflow 已在失败时发送错误事件
            return
        }

        emit(.messageReceived(
            content: "工作流已提交 (prompt_id: \(promptId))，等待执行完成...\n",
            isDelta: true
        ))

        // 3. 轮询结果 → 提取图片输出
        guard let images = await pollForImages(
            base: base,
            promptId: promptId,
            apiKey: config.apiKey
        ) else {
            // pollForImages 已在失败时发送错误事件
            return
        }

        // 4. 下载图片 → 转 base64 markdown
        await downloadAndEmitImages(images: images, base: base, apiKey: config.apiKey)
    }

    /// 根据输入内容构造工作流。
    ///
    /// - content 以 `{` 开头 → 解析为用户提供的 ComfyUI API 工作流 JSON
    /// - 否则 → 调用 ComfyWorkflowBuilder.buildTextToImageWorkflow 构造默认文生图工作流
    ///
    /// 解析失败时发送错误事件并返回 nil（调用方应直接 return）。
    private func buildWorkflow(content: String, config: AgentConfig) -> [String: Any]? {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.hasPrefix("{") {
            return ComfyWorkflowBuilder.buildTextToImageWorkflow(prompt: content, config: config)
        }
        // 工作流 JSON 模式：原样解析用户提供的 JSON
        guard let data = trimmed.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            emit(.error(code: .protocolParseError, message: "工作流 JSON 解析失败"))
            return nil
        }
        return parsed
    }

    // MARK: - Submit Workflow

    /// 提交工作流到 `/prompt`。
    ///
    /// - Returns: 成功返回 prompt_id；失败时发送错误事件并返回 nil
    private func submitWorkflow(
        base: String,
        workflow: [String: Any],
        apiKey: String
    ) async -> String? {
        let promptUrl = "\(base)/prompt"
        guard let url = URLValidator.validate(promptUrl, allowLocalhost: true) else {
            emit(.error(code: .transportConnectFailed, message: "Invalid or blocked server URL"))
            return nil
        }

        let clientId = UUID().uuidString
        let requestBody: [String: Any] = [
            "prompt": workflow,
            "client_id": clientId
        ]

        guard let bodyData = try? JSONSerialization.data(withJSONObject: requestBody) else {
            emit(.error(code: .protocolParseError, message: "工作流序列化失败"))
            return nil
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = bodyData
        request.timeoutInterval = 30

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                emit(.error(code: .protocolParseError, message: "Invalid response"))
                return nil
            }
            if !(200...299).contains(http.statusCode) {
                let bodyText = String(data: data, encoding: .utf8)?.prefix(200) ?? ""
                let code: AppErrorCode = (http.statusCode == 401 || http.statusCode == 403)
                    ? .transportAuthFailed
                    : .transportConnectFailed
                emit(.error(code: code, message: "ComfyUI 提交失败 (HTTP \(http.statusCode)): \(bodyText)"))
                return nil
            }
            // 解析 prompt_id
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let promptId = json["prompt_id"] as? String else {
                emit(.error(code: .transportConnectFailed, message: "ComfyUI 响应缺少 prompt_id"))
                return nil
            }
            return promptId
        } catch {
            if Task.isCancelled { return nil }
            emit(.error(code: .transportConnectFailed, message: "提交工作流失败：\(error.localizedDescription)"))
            return nil
        }
    }

    // MARK: - Poll History

    /// 轮询 `/history/{prompt_id}` 直到工作流执行完成或超时。
    ///
    /// - 每 3s 一次，最多 120 次（约 6 分钟），与 Android ComfyApiClient.pollUntilComplete 一致
    /// - Returns: 成功返回图片输出列表；失败时发送错误事件并返回 nil
    private func pollForImages(
        base: String,
        promptId: String,
        apiKey: String
    ) async -> [ComfyImageOutput]? {
        let historyUrl = "\(base)/history/\(promptId)"
        guard URLValidator.validate(historyUrl, allowLocalhost: true) != nil else {
            emit(.error(code: .transportConnectFailed, message: "Invalid history URL"))
            return nil
        }

        let maxAttempts = 120
        let intervalNs: UInt64 = 3_000_000_000  // 3s

        for _ in 0..<maxAttempts {
            // 用户取消（点 Stop / 视图退出）→ 直接返回，不报错
            if Task.isCancelled { return nil }

            try? await Task.sleep(nanoseconds: intervalNs)
            if Task.isCancelled { return nil }

            switch await checkHistoryOnce(historyUrl: historyUrl, promptId: promptId, apiKey: apiKey) {
            case .done(let images):
                return images
            case .workflowError:
                emit(.error(code: .transportConnectFailed, message: "ComfyUI 工作流执行出错"))
                return nil
            case .notReady:
                continue  // 尚未完成，继续轮询
            }
        }
        // 轮询超时
        emit(.error(code: .transportTimeout, message: "ComfyUI 工作流执行超时或未生成图片"))
        return nil
    }

    /// 单次轮询状态枚举
    private enum PollState {
        case notReady
        case done([ComfyImageOutput])
        case workflowError
    }

    /// 查询一次 history，返回当前状态。
    private func checkHistoryOnce(historyUrl: String, promptId: String, apiKey: String) async -> PollState {
        guard let url = URLValidator.validate(historyUrl, allowLocalhost: true) else {
            return .notReady
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        request.timeoutInterval = 10

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode) else {
                return .notReady
            }
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let promptEntry = json[promptId] as? [String: Any] else {
                // history 响应可能尚未包含该 promptId（工作流排队中）
                return .notReady
            }

            // 检查 status.status_str == "error"
            if let statusObj = promptEntry["status"] as? [String: Any],
               let statusStr = statusObj["status_str"] as? String,
               statusStr == "error" {
                return .workflowError
            }

            // completed == true 或已有 outputs 字段 → 尝试提取图片
            let isCompleted = (promptEntry["status"] as? [String: Any])?["completed"] as? Bool ?? false
            if isCompleted || promptEntry["outputs"] != nil {
                let images = ComfyImageOutputExtractor.extract(from: promptEntry)
                return images.isEmpty ? .notReady : .done(images)
            }
            return .notReady
        } catch {
            // 网络/超时异常 → 视为未就绪，继续轮询
            return .notReady
        }
    }

    // MARK: - Download Images

    /// 下载所有图片并以 markdown 形式输出；若全部失败则发送错误事件。
    private func downloadAndEmitImages(images: [ComfyImageOutput], base: String, apiKey: String) async {
        var markdown = ""
        for (index, img) in images.enumerated() {
            if Task.isCancelled { return }
            guard let md = await downloadImageAsMarkdown(output: img, base: base, apiKey: apiKey) else {
                continue
            }
            if index > 0 { markdown += "\n\n" }
            markdown += md
        }
        if markdown.isEmpty {
            emit(.error(code: .transportConnectFailed, message: "图片下载失败"))
            return
        }
        // 全量回复（isDelta=false），与 Android ComfyUITransport.downloadAndEmitImages 一致
        emit(.messageReceived(content: markdown, isDelta: false))
        emit(.streamComplete)
    }

    /// 下载单张图片并转为 markdown data URI。
    ///
    /// - Returns: `![ComfyUI](data:image/png;base64,...)` 或 nil（下载失败）
    private func downloadImageAsMarkdown(
        output: ComfyImageOutput,
        base: String,
        apiKey: String
    ) async -> String? {
        let viewUrl = output.viewUrl(base: base)
        guard let url = URLValidator.validate(viewUrl, allowLocalhost: true) else { return nil }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        request.timeoutInterval = 30

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode) else {
                return nil
            }
            let base64 = data.base64EncodedString()
            return "![ComfyUI](data:image/png;base64,\(base64))"
        } catch {
            if Task.isCancelled { return nil }
            return nil
        }
    }

    // MARK: - Health Check

    /// 健康检查：GET {base}/system_stats。
    ///
    /// 返回 2xx 视为可达；任何异常/非 2xx 视为不可达。
    /// 超时 5s（与 Android ComfyApiClient.checkHealth 的 withTimeout(5000) 一致）。
    private func checkHealth(base: String, apiKey: String) async -> Bool {
        let probeUrl = "\(base)/system_stats"
        guard let url = URLValidator.validate(probeUrl, allowLocalhost: true) else {
            return false
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        request.timeoutInterval = 5

        do {
            let (_, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return false }
            return (200...299).contains(http.statusCode)
        } catch {
            return false
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
        // 终止任何 in-flight HTTP 请求（用户取消生成 / 视图退出时）
        sessionLock.lock()
        session.invalidateAndCancel()
        sessionLock.unlock()
        continuationLock.lock()
        eventContinuation?.finish()
        continuationLock.unlock()
        // 显式清空 config / e2eKey，避免 transport 实例长生命周期持有
        // 导致 apiKey 在内存中残留（与 OpenAIHTTPTransport.shutdown 一致）
        self.config = nil
        self.e2eKey = nil
    }

    // MARK: - Event Emission

    private func emit(_ event: AgentEvent) {
        continuationLock.lock()
        defer { continuationLock.unlock() }
        eventContinuation?.yield(event)
    }
}
