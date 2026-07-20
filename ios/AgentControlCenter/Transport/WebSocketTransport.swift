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
    private var e2eKey: String?

    /// 事件流续期
    private var eventContinuation: AsyncStream<AgentEvent>.Continuation?

    /// 重连状态
    private var isConnecting = false
    private var shouldReconnect = false
    private var retryCount = 0
    private let maxRetries = 3

    /// 连接状态
    private(set) var connectionState = AgentConnectionState()

    lazy var events: AsyncStream<AgentEvent> = {
        AsyncStream { continuation in
            self.eventContinuation = continuation
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
        self.session = URLSession(configuration: config)
    }

    // MARK: - Connect

    func connect(config: AgentConfig, e2eKey: String?) async {
        self.config = config
        self.e2eKey = e2eKey
        shouldReconnect = true
        retryCount = 0
        await connectLoop()
    }

    private func connectLoop() async {
        guard let config = config, shouldReconnect else { return }
        guard retryCount <= maxRetries else {
            emit(.error(message: "Connection failed after \(maxRetries) retries"))
            return
        }

        isConnecting = true

        // URL 构造: http→ws, https→wss, 追加 /ws
        var wsUrl = config.serverUrl
            .replacingOccurrences(of: "http://", with: "ws://")
            .replacingOccurrences(of: "https://", with: "wss://")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        wsUrl += "/ws"

        guard let url = URL(string: wsUrl) else {
            emit(.error(message: "Invalid WebSocket URL: \(wsUrl)"))
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
            connectionState = AgentConnectionState(
                isConnected: true,
                serverUrl: config.serverUrl,
                agentType: config.type,
                latency: 0
            )
            emit(.connected(serverUrl: config.serverUrl, agentType: config.type))

            // 开始接收消息循环
            await receiveLoop()

        } catch {
            isConnecting = false
            await handleDisconnect(reason: error.localizedDescription)
        }
    }

    // MARK: - Receive Loop

    private func receiveLoop() async {
        while shouldReconnect && connectionState.isConnected {
            do {
                let message = try await webSocketTask?.receive()
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
                if webSocketTask == nil { break }  // transport 已关闭，退出循环
                await handleDisconnect(reason: error.localizedDescription)
                break
            }
        }
    }

    // MARK: - Send Message

    func sendMessage(sessionId: String, content: String) async throws {
        guard connectionState.isConnected else {
            emit(.error(message: "Not connected"))
            return
        }

        // E2E 加密 (如果启用)
        let actualContent: String
        if let e2eKey = e2eKey, !e2eKey.isEmpty {
            actualContent = CryptoManager.encrypt(content, passphrase: e2eKey)
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
                emit(.error(message: frame.message ?? "Unknown server error"))

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
        connectionState = AgentConnectionState(
            isConnected: false,
            serverUrl: connectionState.serverUrl,
            agentType: connectionState.agentType,
            latency: 0
        )

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

    // MARK: - Event Emission

    private func emit(_ event: AgentEvent) {
        eventContinuation?.yield(event)
    }
}
