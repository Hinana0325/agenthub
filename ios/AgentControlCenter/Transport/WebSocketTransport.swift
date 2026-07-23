import Foundation

// MARK: - WebSocket Transport
// 对应 Android WebSocketTransport
// 协议契约: transport/websocket-protocol.md

/// WebSocket 双向通信传输实现。
///
/// 协议规范：
/// - URL 构造: http→ws, https→wss, 追加 /ws
/// - 鉴权帧: {"type": "auth", "key": "<apiKey>"}
/// - 客户端→服务端: {"type": "message", "sessionId": "...", "content": "...", "role": "User"}
/// - 服务端→客户端: message/response/error/ping 四种帧类型
/// - E2E 加密: AH1: 前缀格式 (e2eKey 非空时启用)
/// - 重连: 最多 3 次，指数退避 1000ms×2，封顶 30000ms
final class WebSocketTransport: AgentTransport, @unchecked Sendable {

    private var webSocketTask: URLSessionWebSocketTask?
    private let session: URLSession

    private var config: AgentConfig?

    /// E2E 密钥的内部存储（锁保护，跨 actor 安全访问）。
    /// Swift 6 strict concurrency complete 下，e2eKey 在运行时可能被 AppState 订阅线程
    /// 调用 updateE2eKey 更新，同时被 sendMessage（加密）/ handleText（解密）读取，
    /// 存在数据竞争。改为通过计算属性 + NSLock 保护读写，对齐 Android @Volatile 语义：
    /// 写入立即可见，读取走锁避免撕裂。
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

    /// 重连状态
    private var isConnecting = false
    private var shouldReconnect = false
    private var retryCount = 0
    private let maxRetries = 3

    /// 连接状态
    // H15 修复：connectionState 跨 actor 并发读写无同步，存在数据竞争。
    // 用 NSLock 保护读写访问（与 continuationLock / historyLock 风格一致），
    // 通过 _connectionStateLock 进行所有访问。private(set) 暴露的 getter 改为
    // 计算属性走锁内读取，写入通过 setConnectionState(_:) 走锁内写入。
    private let connectionStateLock = NSLock()
    private var _connectionState = AgentConnectionState()
    var connectionState: AgentConnectionState {
        connectionStateLock.lock()
        defer { connectionStateLock.unlock() }
        return _connectionState
    }

    /// 在锁内更新 connectionState（H15 修复）
    private func setConnectionState(_ state: AgentConnectionState) {
        connectionStateLock.lock()
        _connectionState = state
        connectionStateLock.unlock()
    }

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

    /// 服务端推送消息的结构
    private struct ServerFrame: Decodable {
        let type: String
        let content: String?
        let delta: Bool?
        let sessionId: String?
        let message: String?
    }

    // MARK: - Init

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.waitsForConnectivity = true
        // v4.9.0 H16：注入 TLSPinningDelegate.shared 实现证书公钥锁定（SPKI Pinning）。
        // 对公网固定 API 端点校验证书 SPKI hash 与预置 pin 列表，防御 MITM 与伪造证书；
        // 本地 / 自定义端点及占位 pin 期间降级为系统默认 CA 校验，不阻塞连接。
        // 详见 TLSPinningDelegate 与 protocol/transport/tls-pinning.md。
        self.session = URLSession(
            configuration: config,
            delegate: TLSPinningDelegate.shared,
            delegateQueue: nil
        )
    }

    // MARK: - Connect

    func connect(config: AgentConfig, e2eKey: String?) async {
        self.config = config
        self.e2eKey = e2eKey
        shouldReconnect = true
        retryCount = 0
        await connectLoop()
    }

    // MARK: - 热更新 E2E 密钥

    /// 运行时热更新 E2E 密钥，无需断开重连。
    ///
    /// 与 Android WebSocketTransport.updateE2eKey 对齐：
    /// - 仅更新内部 e2eKey 字段（通过 e2eKeyLock 保护写入）
    /// - 不触发 reconnect / 重新鉴权帧
    /// - 后续 sendMessage 加密 / handleText 解密立即使用新值
    /// - 传 nil 表示禁用 E2E 加密（与关闭开关等效），后续消息明文收发
    func updateE2eKey(_ key: String?) {
        e2eKey = key
    }

    private func connectLoop() async {
        guard let config = config, shouldReconnect else { return }
        guard retryCount <= maxRetries else {
            // C3 修复：重连耗尽 → TRANSPORT_RECONNECT_FAILED (1005)
            emit(.error(code: .transportReconnectFailed, message: "Connection failed after \(maxRetries) retries"))
            return
        }

        isConnecting = true

        // URL 构造: http→ws, https→wss, 追加 /ws
        var wsUrl = config.serverUrl
            .replacingOccurrences(of: "http://", with: "ws://")
            .replacingOccurrences(of: "https://", with: "wss://")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        wsUrl += "/ws"

        // SSRF 校验：禁止 file:// / data:// / 链路本地 / 元数据服务等危险目标
        guard let url = URLValidator.validate(wsUrl, allowLocalhost: true) else {
            // C3 修复：URL 无效阻断连接 → TRANSPORT_CONNECT_FAILED (1001)
            // 注：注册表无 TRANSPORT_INVALID_URL，归入 1001（连接失败）
            emit(.error(code: .transportConnectFailed, message: "服务地址格式错误或不被允许"))
            return
        }

        // 如果是重试，发送 Reconnecting 事件
        if retryCount > 0 {
            emit(.reconnecting)
        }

        webSocketTask = session.webSocketTask(with: url)
        webSocketTask?.resume()

        // 等待连接建立
        do {
            // 发送鉴权帧
            if !config.apiKey.isEmpty {
                let authFrame: [String: Any] = ["type": "auth", "key": config.apiKey]
                let authData = try JSONSerialization.data(withJSONObject: authFrame)
                try await webSocketTask?.send(.data(authData))
            }

            isConnecting = false
            retryCount = 0
            // H15 修复：通过 setConnectionState 走锁写入，避免数据竞争
            setConnectionState(AgentConnectionState(
                isConnected: true,
                serverUrl: config.serverUrl,
                agentType: config.type,
                latency: 0
            ))
            emit(.connected(serverUrl: config.serverUrl, agentType: config.type))

            // 启动心跳任务：每 30 秒发送 ping 帧检测连接活性。
            // 若 send 抛异常（连接已断），receiveLoop 也会失败并触发重连。
            let heartbeatTask = Task {
                while !Task.isCancelled && connectionState.isConnected {
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                    guard !Task.isCancelled else { break }
                    let pingFrame: [String: Any] = ["type": "ping"]
                    if let data = try? JSONSerialization.data(withJSONObject: pingFrame) {
                        do {
                            try await webSocketTask?.send(.data(data))
                        } catch {
                            // 心跳发送失败意味着连接已断，
                            // 退出心跳循环，让 receiveLoop 触发重连
                            break
                        }
                    }
                }
            }

            // 开始接收消息循环
            await receiveLoop()
            heartbeatTask.cancel()

        } catch {
            isConnecting = false
            await handleDisconnect(reason: error.localizedDescription)
        }
    }

    // MARK: - Receive Loop

    private func receiveLoop() async {
        // 每次循环都检查 `Task.isCancelled`，确保外部 cancel() 能立即终止 receiveLoop，
        // 避免在用户切换会话或退出页面后仍在后台消费 WebSocket 消息。
        while !Task.isCancelled && shouldReconnect && connectionState.isConnected {
            do {
                let message = try await webSocketTask?.receive()
                // receive 返回后再次检查取消标志，避免在已取消的任务中处理消息
                if Task.isCancelled { break }
                switch message {
                case .data(let data):
                    handleData(data)
                case .string(let text):
                    handleText(text)
                case .none:
                    break  // webSocketTask 已关闭
                @unknown default:
                    break
                }
            } catch {
                if Task.isCancelled || webSocketTask == nil { break }  // 任务已取消或 transport 已关闭
                await handleDisconnect(reason: error.localizedDescription)
                break
            }
        }
    }

    // MARK: - Send Message

    func sendMessage(sessionId: String, content: String) async throws {
        guard connectionState.isConnected else {
            // C3 修复：未连接 → TRANSPORT_DISCONNECTED (1004)
            emit(.error(code: .transportDisconnected, message: "Not connected"))
            return
        }

        // E2E 加密 (如果启用)。加密失败时 emit error 且绝不发送明文，避免静默裸传。
        let actualContent: String
        if let e2eKey = e2eKey, !e2eKey.isEmpty {
            guard let encrypted = CryptoManager.encrypt(content, passphrase: e2eKey) else {
                // C3 修复：E2E 加密失败 → CRYPTO_E2E_KEY_MISMATCH (10003)
                // 注：注册表无 CRYPTO_ENCRYPT_FAILED，加密失败通常源于密钥派生失败，
                // 与密钥协商失败同属密钥问题，归入 10003。
                emit(.error(code: .cryptoE2eKeyMismatch, message: String(localized: "error.e2e.encrypt")))
                return
            }
            actualContent = encrypted
        } else {
            actualContent = content
        }

        // 构建 message 帧
        let frame: [String: Any] = [
            "type": "message",
            "sessionId": sessionId,
            "content": actualContent,
            "role": "User"  // 枚举名首字母大写 (与 Android 一致)
        ]

        let data = try JSONSerialization.data(withJSONObject: frame)
        try await webSocketTask?.send(.data(data))
    }

    // MARK: - Frame Handling

    private func handleData(_ data: Data) {
        guard let text = String(data: data, encoding: .utf8) else { return }
        handleText(text)
    }

    private func handleText(_ text: String) {
        guard let jsonData = text.data(using: .utf8) else {
            // 解析失败，回退为原始文本消息
            emit(.messageReceived(content: text, isDelta: false))
            return
        }

        do {
            let frame = try JSONDecoder().decode(ServerFrame.self, from: jsonData)
            switch frame.type {
            case "message", "response":
                guard var content = frame.content else { return }
                let isDelta = frame.delta ?? false

                // 尝试 E2E 解密
                if let e2eKey = e2eKey, !e2eKey.isEmpty {
                    if let decrypted = CryptoManager.decrypt(content, passphrase: e2eKey) {
                        content = decrypted
                    }
                }

                emit(.messageReceived(content: content, isDelta: isDelta))

            case "error":
                // C3 修复：服务端 error 帧 → PROTOCOL_INVALID_MESSAGE (2001)
                // 服务端推送的错误帧通常是协议级问题（消息格式 / 字段缺失 / 类型未知）
                emit(.error(code: .protocolInvalidMessage, message: frame.message ?? "Unknown server error"))

            case "ping":
                // 心跳，忽略
                break

            default:
                break
            }
        } catch {
            // JSON 解析失败，回退为原始文本
            emit(.messageReceived(content: text, isDelta: false))
        }
    }

    // MARK: - Reconnection

    private func handleDisconnect(reason: String) async {
        // H15 修复：通过 setConnectionState 走锁写入；读取 connectionState 也走锁
        let prev = connectionState
        setConnectionState(AgentConnectionState(
            isConnected: false,
            serverUrl: prev.serverUrl,
            agentType: prev.agentType,
            latency: 0
        ))

        if shouldReconnect && retryCount < maxRetries {
            retryCount += 1
            // 指数退避: 1000ms × 2^(retry-1), 封顶 30000ms
            let delay = min(1000 * (1 << (retryCount - 1)), 30000)
            try? await Task.sleep(nanoseconds: UInt64(delay) * 1_000_000)
            await connectLoop()
        } else {
            emit(.disconnected(reason: reason))
        }
    }

    // MARK: - Disconnect / Shutdown

    func disconnect() {
        shouldReconnect = false
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        // H15 修复：通过 setConnectionState 走锁写入；读取 connectionState 也走锁
        let prev = connectionState
        setConnectionState(AgentConnectionState(
            isConnected: false,
            serverUrl: prev.serverUrl,
            agentType: prev.agentType,
            latency: 0
        ))
        emit(.disconnected(reason: "User disconnected"))
    }

    func shutdown() {
        disconnect()
        continuationLock.lock()
        eventContinuation?.finish()
        continuationLock.unlock()
        // L-3 修复：显式清空 config / e2eKey，避免 transport 实例被复用或
        // 长生命周期持有导致 apiKey 在内存中残留（潜在泄漏面）。
        // config 已为 var（connect() 中赋值），e2eKey 同理。
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
