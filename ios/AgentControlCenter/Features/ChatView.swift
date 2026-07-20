import SwiftUI
import PhotosUI

/// 聊天视图
/// 展示某个会话的消息列表,并提供输入栏发送消息(支持图片附件)。
/// 通过 TransportFactory 创建活跃 Agent 的传输层,流式接收回复。
struct ChatView: View {
    // 全局应用状态
    @Environment(AppState.self) private var appState

    /// 目标会话 ID
    let sessionId: String

    /// 本地消息列表(从 dataController 拉取)
    @State private var messages: [Message] = []
    /// 输入框文本
    @State private var inputText: String = ""
    /// 是否正在等待 Agent 响应
    @State private var isWaiting: Bool = false
    /// PhotosPicker 选中项
    @State private var photoItem: PhotosPickerItem?
    /// 附件名称(简化:仅展示名称)
    @State private var attachmentName: String?

    /// 活跃 Agent 的传输层实例
    @State private var transport: AgentTransport?
    /// 当前流式响应累积的文本
    @State private var streamingText: String = ""
    /// 事件消费任务(用于退出时取消)
    @State private var eventTask: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 0) {
            // 消息列表区域
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(messages) { message in
                            MessageBubble(message: message)
                                .id(message.id)
                        }

                        // 等待响应时:尚无内容显示打字指示器,有内容显示流式气泡
                        if isWaiting {
                            if streamingText.isEmpty {
                                TypingIndicator()
                                    .id("typing-indicator")
                            } else {
                                StreamingBubble(text: streamingText)
                                    .id("streaming-bubble")
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                }
                .onAppear {
                    loadMessages()
                    setupTransport()
                    scrollToBottom(proxy: proxy, animated: false)
                }
                .onDisappear {
                    // 退出聊天:取消事件任务并断开传输
                    eventTask?.cancel()
                    transport?.disconnect()
                }
                .onChange(of: messages.count) { _, _ in
                    scrollToBottom(proxy: proxy, animated: true)
                }
                .onChange(of: isWaiting) { _, _ in
                    scrollToBottom(proxy: proxy, animated: true)
                }
                .onChange(of: streamingText) { _, _ in
                    scrollToBottom(proxy: proxy, animated: true)
                }
            }

            Divider()

            // 底部输入栏
            inputBar
        }
        .navigationTitle(sessionTitle)
        .navigationBarTitleDisplayMode(.inline)
    }

    // 顶部标题:从 sessionManager 中查找会话标题,兜底 "会话"
    private var sessionTitle: String {
        appState.sessionManager.sessions.first { $0.id == sessionId }?.title ?? "会话"
    }

    /// 底部输入栏:图片选择 + 文本输入 + 发送按钮
    private var inputBar: some View {
        HStack(spacing: 8) {
            // 图片附件选择器(简化:仅记录附件名)
            PhotosPicker(selection: $photoItem, matching: .images) {
                Image(systemName: "photo")
                    .font(.title3)
                    .foregroundStyle(AppTheme.primaryColor)
            }
            .onChange(of: photoItem) { _, newValue in
                Task {
                    if let newValue,
                       (try? await newValue.loadTransferable(type: Data.self)) != nil {
                        attachmentName = newValue.itemIdentifier ?? "image.jpg"
                    } else {
                        attachmentName = nil
                    }
                }
            }
            .disabled(isWaiting)

            // 输入框(支持多行)
            TextField("输入消息…", text: $inputText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(1...5)
                .disabled(isWaiting)

            // 发送按钮
            Button {
                sendMessage()
            } label: {
                Image(systemName: "paperplane.fill")
                    .font(.title3)
                    .foregroundStyle(canSend ? .white : .secondary)
                    .padding(8)
                    .background(
                        canSend ? AppTheme.primaryColor : Color.gray.opacity(0.3),
                        in: Circle()
                    )
            }
            .disabled(!canSend)
            .accessibilityLabel("发送")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(AppTheme.secondaryBackground)
    }

    // 是否允许发送:有非空文本且未在等待
    private var canSend: Bool {
        !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isWaiting
    }

    /// 滚动到底部(消息 / 打字指示器 / 流式气泡)
    private func scrollToBottom(proxy: ScrollViewProxy, animated: Bool) {
        let targetId: String? = isWaiting
            ? (streamingText.isEmpty ? "typing-indicator" : "streaming-bubble")
            : messages.last?.id
        guard let targetId else { return }
        if animated {
            withAnimation { proxy.scrollTo(targetId, anchor: .bottom) }
        } else {
            proxy.scrollTo(targetId, anchor: .bottom)
        }
    }

    /// 从 dataController 加载当前会话的全部消息(按时间升序)
    private func loadMessages() {
        messages = appState.dataController.fetchMessages(sessionId: sessionId)
    }

    /// 创建并连接活跃 Agent 的传输层,启动事件消费循环
    private func setupTransport() {
        guard transport == nil else { return }
        // 仅当活跃 Agent 提供了配置时才建立传输
        guard let config = appState.agentManager.activeAgent?.config else { return }
        let t = TransportFactory.create(config.type)
        transport = t
        let sid = sessionId
        eventTask = Task {
            // 建立连接(忽略 e2e 密钥,简化处理)
            await t.connect(config: config, e2eKey: nil)
            // 持续消费传输层事件流,直到视图退出取消任务
            for await event in t.events {
                if Task.isCancelled { break }
                await MainActor.run {
                    handleEvent(event, sessionId: sid)
                }
            }
        }
    }

    /// 处理来自传输层的实时事件(在主线程执行)
    private func handleEvent(_ event: AgentEvent, sessionId: String) {
        switch event {
        case .messageReceived(let content, let isDelta):
            // isDelta=true 表示增量片段(累加);false 表示完整内容(覆盖)
            if isDelta {
                streamingText += content
            } else {
                streamingText = content
            }
        case .streamComplete:
            // 流结束:把累积文本固化为一条助手消息
            finalizeAssistantMessage(sessionId: sessionId)
        case .error(let message):
            streamingText = "❌ \(message)"
            finalizeAssistantMessage(sessionId: sessionId)
        default:
            // 连接级事件(connected / disconnected / reconnecting)不影响消息展示
            break
        }
    }

    /// 流式结束:把累积文本固化为一条助手消息并持久化
    private func finalizeAssistantMessage(sessionId: String) {
        let content = streamingText.isEmpty ? "(无回复)" : streamingText
        let msg = Message(
            id: UUID().uuidString,
            sessionId: sessionId,
            role: .assistant,
            content: content,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            status: .received
        )
        messages.append(msg)
        appState.dataController.saveMessage(msg)
        appState.sessionManager.incrementMessageCount(sessionId)
        streamingText = ""
        isWaiting = false
    }

    /// 发送消息:创建用户消息 -> 通过传输层发送 -> 由事件流异步接收回复
    private func sendMessage() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // 1. 创建并展示用户消息(同时持久化)
        let userMessage = Message(
            id: UUID().uuidString,
            sessionId: sessionId,
            role: .user,
            content: text,
            timestamp: now,
            status: .sent,
            attachmentName: attachmentName
        )
        messages.append(userMessage)
        appState.dataController.saveMessage(userMessage)
        appState.sessionManager.incrementMessageCount(sessionId)

        // 重置输入
        inputText = ""
        attachmentName = nil
        photoItem = nil
        streamingText = ""

        // 2. 若未连接到任何 Agent 的传输层,直接给出提示消息
        guard let transport else {
            let hint = Message(
                id: UUID().uuidString,
                sessionId: sessionId,
                role: .assistant,
                content: "⚠️ 未配置活跃 Agent,请先在「Agent」页面选择并配置一个 Agent。",
                timestamp: Int64(Date().timeIntervalSince1970 * 1000),
                status: .received
            )
            messages.append(hint)
            appState.dataController.saveMessage(hint)
            appState.sessionManager.incrementMessageCount(sessionId)
            return
        }

        isWaiting = true

        // 3. 通过传输层发送消息(回复由事件流异步返回并展示)
        Task {
            do {
                try await transport.sendMessage(sessionId: sessionId, content: text)
            } catch {
                await MainActor.run {
                    streamingText = "❌ 发送失败: \(error.localizedDescription)"
                    finalizeAssistantMessage(sessionId: sessionId)
                }
            }
        }
    }
}

/// 单条消息气泡:用户右对齐蓝色 / 助手左对齐灰色
private struct MessageBubble: View {
    let message: Message

    var isUser: Bool { message.role == .user }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 60) }

            VStack(
                alignment: isUser ? .trailing : .leading,
                spacing: 4
            ) {
                // 附件展示(简化:仅显示附件名)
                if let attachment = message.attachmentName {
                    Label(attachment, systemImage: "paperclip")
                        .font(.caption)
                        .padding(8)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8))
                }

                // 消息正文气泡
                Text(message.content)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        isUser ? AppTheme.userBubbleColor : AppTheme.assistantBubbleColor,
                        in: RoundedRectangle(cornerRadius: 14)
                    )
                    .foregroundStyle(isUser ? .white : .primary)
                    .textSelection(.enabled)
            }

            if !isUser { Spacer(minLength: 60) }
        }
    }
}

/// 流式回复气泡:左侧灰色气泡 + 闪烁光标,实时展示累积文本
private struct StreamingBubble: View {
    let text: String
    @State private var showCursor = true

    var body: some View {
        HStack {
            Text(text + (showCursor ? "▍" : " "))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    AppTheme.assistantBubbleColor,
                    in: RoundedRectangle(cornerRadius: 14)
                )
                .foregroundStyle(.primary)
            Spacer()
        }
        .onAppear {
            // 光标闪烁动画
            withAnimation(.easeInOut(duration: 0.5).repeatForever()) {
                showCursor.toggle()
            }
        }
    }
}

/// 打字指示器:三个跳动的圆点
private struct TypingIndicator: View {
    @State private var animate = false

    var body: some View {
        HStack {
            HStack(spacing: 5) {
                ForEach(0..<3, id: \.self) { _ in
                    Circle()
                        .fill(.secondary)
                        .frame(width: 8, height: 8)
                        .scaleEffect(animate ? 1.0 : 0.5)
                        .opacity(animate ? 1.0 : 0.3)
                        .animation(
                            .easeInOut(duration: 0.6).repeatForever(),
                            value: animate
                        )
                }
            }
            .padding(12)
            .background(
                AppTheme.assistantBubbleColor,
                in: RoundedRectangle(cornerRadius: 14)
            )
            Spacer()
        }
        .onAppear { animate = true }
    }
}
