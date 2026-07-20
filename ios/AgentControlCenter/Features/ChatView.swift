import SwiftUI
import PhotosUI

/// 聊天视图 — 完整实现，对齐 Android ChatScreen 核心功能
///
/// 包含：消息列表（LazyVStack + ScrollViewReader）、流式渲染、输入栏
///       （图片附件/多行文本/语音输入/发送/停止）、斜杠命令、Markdown 渲染、
///       会话切换（左右滑动）、删除消息、连接向导、E2E 加密
struct ChatView: View {
    // MARK: - 环境
    @Environment(AppState.self) private var appState
    /// 导航退出标记（用于会话切换时自动导航）
    @Environment(\.dismiss) private var dismiss
    /// E2E 加密开关
    @AppStorage("encryptionEnabled") private var encryptionEnabled: Bool = false
    /// E2E 加密密钥
    @AppStorage("encryptionPassphrase") private var encryptionPassphrase: String = ""

    // MARK: - 参数
    /// 目标会话 ID
    let sessionId: String

    // MARK: - 状态 — 消息与传输
    /// 本地消息列表
    @State private var messages: [Message] = []
    /// 输入框文本
    @State private var inputText: String = ""
    /// 是否正在等待 Agent 响应
    @State private var isWaiting: Bool = false
    /// PhotosPicker 选中项
    @State private var photoItem: PhotosPickerItem?
    /// 附件名称
    @State private var attachmentName: String?
    /// 附件 Base64 数据
    @State private var attachmentBase64: String?
    /// 附件类型
    @State private var attachmentType: AttachmentType?

    /// 活跃 Agent 的传输层实例
    @State private var transport: AgentTransport?
    /// 当前流式响应累积的文本
    @State private var streamingText: String = ""
    /// 事件消费任务
    @State private var eventTask: Task<Void, Never>?

    // MARK: - 状态 — 交互
    /// 正在回复的消息 ID
    @State private var replyToId: String?
    /// 斜杠命令匹配（输入框以 / 开头时）
    @State private var showSlashCommands: Bool = false
    /// 是否显示帮助 Sheet
    @State private var showHelpSheet: Bool = false
    /// 帮助文本内容
    @State private var helpText: String = ""
    /// 语音输入管理器
    @State private var voiceManager = VoiceInputManager()

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // MARK: 消息列表区域
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(messages) { message in
                            MessageBubble(
                                message: message,
                                allMessages: messages,
                                onReply: { replyToMessage($0) },
                                onDelete: { deleteMessage($0) }
                            )
                            .id(message.id)
                        }

                        // 等待响应时：无内容显示打字指示器，有内容显示流式气泡
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
                    cleanup()
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

            // MARK: 底部输入栏
            inputBar
        }
        .navigationTitle(sessionTitle)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showSlashCommands) {
            slashCommandSheet
        }
        .sheet(isPresented: $showHelpSheet) {
            helpSheetView
        }
    }

    // MARK: - 会话标题

    /// 从 sessionManager 中查找会话标题，兜底 "会话"
    private var sessionTitle: String {
        appState.sessionManager.sessions.first { $0.id == sessionId }?.title ?? "会话"
    }

    // MARK: - 是否有活跃 Agent

    /// 检查是否配置了活跃 Agent
    private var hasActiveAgent: Bool {
        appState.agentManager.activeAgent?.config != nil
    }

    // MARK: - 输入栏

    /// 底部输入栏：回复引用 + 图片选择 + 多行文本 + 语音 + 发送/停止
    private var inputBar: some View {
        VStack(spacing: 0) {
            // 回复引用预览条
            if let replyId = replyToId,
               let repliedMessage = messages.first(where: { $0.id == replyId }) {
                replyPreviewBar(repliedMessage)
            }

            HStack(alignment: .bottom, spacing: 8) {
                // 图片附件选择器
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Image(systemName: "photo")
                        .font(.title3)
                        .foregroundStyle(AppTheme.primaryColor)
                }
                .onChange(of: photoItem) { _, newValue in
                    Task { await handlePhotoSelection(newValue) }
                }
                .disabled(isWaiting)

                // 多行文本输入框
                TextField("输入消息…", text: $inputText, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...5)
                    .onChange(of: inputText) { _, newValue in
                        // 输入 `/` 时弹出斜杠命令列表
                        showSlashCommands = newValue.hasPrefix("/") && newValue.trimmingCharacters(in: .whitespaces).count <= 1
                    }
                    .submitLabel(.send)
                    .onSubmit {
                        if showSlashCommands {
                            showSlashCommands = false
                        } else {
                            sendMessage()
                        }
                    }

                // 语音输入按钮
                voiceInputButton

                // 发送按钮 / 停止按钮
                if isWaiting {
                    stopButton
                } else {
                    sendButton
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(AppTheme.secondaryBackground)
        }
        // 左右滑动切换会话
        .gesture(
            DragGesture(minimumDistance: 50)
                .onEnded { value in
                    handleSwipe(value: value)
                }
        )
    }

    // MARK: - 回复引用预览条

    /// 显示正在回复的消息预览，可点 X 取消
    private func replyPreviewBar(_ message: Message) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(replyRoleName(message.role))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(String(message.content.prefix(50)))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                replyToId = nil
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color(.systemBackground))
    }

    /// 回复角色的显示名称
    private func replyRoleName(_ role: MessageRole) -> String {
        switch role {
        case .user: return "回复你"
        case .assistant: return "回复助手"
        case .system: return "回复系统"
        case .tool: return "回复工具"
        }
    }

    // MARK: - 语音输入按钮

    /// 点击后调用 VoiceInputManager 进行语音识别
    private var voiceInputButton: some View {
        Button {
            Task { await toggleVoiceInput() }
        } label: {
            ZStack {
                Circle()
                    .fill(voiceManager.state == .listening ? Color.red.opacity(0.2) : Color.clear)
                    .frame(width: 36, height: 36)
                Image(systemName: voiceManager.state == .listening ? "mic.fill" : "mic")
                    .font(.title3)
                    .foregroundStyle(voiceManager.state == .listening ? .red : AppTheme.primaryColor)
            }
        }
        .disabled(isWaiting)
        .accessibilityLabel(voiceManager.state == .listening ? "停止语音输入" : "语音输入")
    }

    /// 切换语音输入状态
    private func toggleVoiceInput() async {
        switch voiceManager.state {
        case .idle:
            await voiceManager.startListening()
            // 语音识别结束后将结果填入输入框
            let currentState = voiceManager.state
            if case .idle = currentState {
                // 已经停止，检查是否有结果
                if !voiceManager.recognizedText.isEmpty {
                    inputText += voiceManager.recognizedText
                    voiceManager.recognizedText = ""
                }
            }
        case .listening:
            voiceManager.stopListening()
            if !voiceManager.recognizedText.isEmpty {
                inputText += voiceManager.recognizedText
                voiceManager.recognizedText = ""
            }
        case .processing:
            voiceManager.stopListening()
        case .error(let msg):
            // 错误时重新开始
            voiceManager.recognizedText = ""
            await voiceManager.startListening()
            _ = msg
        }
    }

    // MARK: - 发送按钮

    private var sendButton: some View {
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

    // MARK: - 停止按钮

    /// 红色停止按钮，点击取消 eventTask
    private var stopButton: some View {
        Button {
            stopGenerating()
        } label: {
            Image(systemName: "stop.fill")
                .font(.title3)
                .foregroundStyle(.white)
                .padding(8)
                .background(Color.red, in: Circle())
        }
        .accessibilityLabel("停止生成")
    }

    // MARK: - 是否允许发送

    private var canSend: Bool {
        !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isWaiting
    }

    // MARK: - 斜杠命令 Sheet

    /// 斜杠命令列表（用 sheet 展示，不弹 alert）
    private var slashCommandSheet: some View {
        NavigationStack {
            List {
                slashCommandRow(
                    command: "/clear",
                    description: "清空当前会话的所有消息"
                )
                slashCommandRow(
                    command: "/new",
                    description: "新建会话并跳转"
                )
                slashCommandRow(
                    command: "/reconnect",
                    description: "重新连接传输层"
                )
                slashCommandRow(
                    command: "/help",
                    description: "显示帮助文本"
                )
            }
            .navigationTitle("斜杠命令")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        showSlashCommands = false
                        inputText = ""
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }

    /// 单条斜杠命令行
    private func slashCommandRow(command: String, description: String) -> some View {
        Button {
            showSlashCommands = false
            inputText = ""
            executeSlashCommand(command)
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(command)
                    .font(.headline)
                Text(description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .tint(.primary)
    }

    // MARK: - 帮助 Sheet

    private var helpSheetView: some View {
        NavigationStack {
            ScrollView {
                Text(helpText)
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("帮助")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("关闭") {
                        showHelpSheet = false
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - 斜杠命令执行

    /// 执行斜杠命令
    private func executeSlashCommand(_ command: String) {
        switch command {
        case "/clear":
            clearMessages()
        case "/new":
            createNewSession()
        case "/reconnect":
            reconnect()
        case "/help":
            helpText = """
            AgentControlCenter 聊天帮助
            ========================

            斜杠命令：
            /clear     清空当前会话的所有消息
            /new       新建会话并跳转
            /reconnect 重新连接传输层
            /help      显示本帮助文本

            基本操作：
            - 输入消息后按回车或点击发送按钮发送
            - 点击 📷 选择图片作为附件发送
            - 点击 🎤 进行语音输入
            - 长按消息可复制、删除或回复
            - 在输入栏区域左右滑动可切换会话

            E2E 加密：
            - 在设置中启用加密并设置密钥
            - 连接时会自动使用 E2E 密钥
            """
            showHelpSheet = true
        default:
            break
        }
    }

    /// 清空当前会话消息
    private func clearMessages() {
        appState.dataController.deleteMessages(sessionId: sessionId)
        messages.removeAll()
    }

    /// 新建会话并跳转
    private func createNewSession() {
        let session = appState.sessionManager.createSession()
        appState.dataController.saveSession(session)
        // 退出当前视图，由上层导航处理跳转
        dismiss()
    }

    /// 重新连接传输层
    private func reconnect() {
        eventTask?.cancel()
        transport?.disconnect()
        transport?.shutdown()
        transport = nil
        isWaiting = false
        streamingText = ""
        setupTransport()
    }

    // MARK: - 图片选择处理

    /// 处理 PhotosPicker 选择的图片：压缩到 720p，转 Base64
    private func handlePhotoSelection(_ item: PhotosPickerItem?) async {
        guard let item else {
            attachmentName = nil
            attachmentBase64 = nil
            attachmentType = nil
            return
        }

        guard let data = try? await item.loadTransferable(type: Data.self),
              let uiImage = UIImage(data: data) else {
            attachmentName = nil
            attachmentBase64 = nil
            attachmentType = nil
            return
        }

        // 压缩到 720p（取长边 720）
        let maxSize: CGFloat = 720
        let scale = min(maxSize / uiImage.size.width, maxSize / uiImage.size.height, 1.0)
        let newSize = CGSize(width: uiImage.size.width * scale, height: uiImage.size.height * scale)

        let renderer = UIGraphicsImageRenderer(size: newSize)
        let resized = renderer.image { _ in
            uiImage.draw(in: CGRect(origin: .zero, size: newSize))
        }

        guard let jpegData = resized.jpegData(compressionQuality: 0.7) else {
            attachmentName = nil
            attachmentBase64 = nil
            attachmentType = nil
            return
        }

        attachmentName = item.itemIdentifier ?? "image.jpg"
        attachmentBase64 = jpegData.base64EncodedString()
        attachmentType = .image
    }

    // MARK: - 会话切换（左右滑动）

    /// 处理输入栏区域的左右滑动手势
    private func handleSwipe(value: DragGesture.Value) {
        let sorted = appState.sessionManager.sortedSessions
        guard let currentIndex = sorted.firstIndex(where: { $0.id == sessionId }) else { return }

        let horizontal = value.translation.width
        if horizontal > 50 {
            // 右滑 → 上一个会话（索引 +1，sorted 是降序所以 +1 表示更早的会话）
            let prevIndex = currentIndex + 1
            if prevIndex < sorted.count {
                navigateToSession(sorted[prevIndex].id)
            }
        } else if horizontal < -50 {
            // 左滑 → 下一个会话（索引 -1，sorted 是降序所以 -1 表示更新的会话）
            let nextIndex = currentIndex - 1
            if nextIndex >= 0 {
                navigateToSession(sorted[nextIndex].id)
            }
        }
    }

    /// 导航到指定会话（退出当前视图，由上层 NavigationStack 处理）
    private func navigateToSession(_ targetSessionId: String) {
        // 通过退出当前视图触发重新导航
        // 上层 SessionsView 或 ContentView 会根据 activeSession 处理跳转
        appState.sessionManager.setActive(sessionId: targetSessionId)
        dismiss()
    }

    // MARK: - 删除消息

    /// 删除指定消息
    private func deleteMessage(_ message: Message) {
        appState.dataController.deleteMessage(id: message.id)
        messages.removeAll { $0.id == message.id }
    }

    // MARK: - 回复消息

    /// 设置回复目标
    private func replyToMessage(_ message: Message) {
        replyToId = message.id
    }

    // MARK: - 停止生成

    /// 取消当前事件任务并停止生成
    private func stopGenerating() {
        eventTask?.cancel()
        isWaiting = false
        // 如果有流式文本但未固化，直接清除
        streamingText = ""
    }

    // MARK: - 滚动到底部

    /// 滚动到底部（消息 / 打字指示器 / 流式气泡）
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

    // MARK: - 加载消息

    /// 从 dataController 加载当前会话的全部消息（按时间升序）
    private func loadMessages() {
        messages = appState.dataController.fetchMessages(sessionId: sessionId)
    }

    // MARK: - 清理（onDisappear）

    /// 退出视图时：取消事件任务、断开传输、释放资源（修复 W-16）
    private func cleanup() {
        eventTask?.cancel()
        transport?.disconnect()
        transport?.shutdown()
        voiceManager.stopListening()
    }

    // MARK: - 传输层设置

    /// 创建并连接活跃 Agent 的传输层，启动事件消费循环
    private func setupTransport() {
        guard transport == nil else { return }

        // 仅当活跃 Agent 提供了配置时才建立传输
        guard let config = appState.agentManager.activeAgent?.config else { return }

        let t = TransportFactory.create(config.type)
        transport = t

        // E2E 加密密钥
        let e2eKey: String? = encryptionEnabled && !encryptionPassphrase.isEmpty
            ? encryptionPassphrase
            : nil

        let sid = sessionId
        eventTask = Task {
            await t.connect(config: config, e2eKey: e2eKey)
            // 持续消费传输层事件流，直到视图退出取消任务
            for await event in t.events {
                if Task.isCancelled { break }
                await MainActor.run {
                    handleEvent(event, sessionId: sid)
                }
            }
        }
    }

    // MARK: - 事件处理

    /// 处理来自传输层的实时事件（在主线程执行）
    private func handleEvent(_ event: AgentEvent, sessionId: String) {
        switch event {
        case .messageReceived(let content, let isDelta):
            // isDelta=true 表示增量片段（累加）；false 表示完整内容（覆盖）
            if isDelta {
                streamingText += content
            } else {
                streamingText = content
            }
        case .streamComplete:
            // 流结束：把累积文本固化为一条助手消息
            finalizeAssistantMessage(sessionId: sessionId)
        case .error(let message):
            streamingText = "错误: \(message)"
            finalizeAssistantMessage(sessionId: sessionId)
        case .connected:
            // 连接成功，无需特殊处理
            break
        case .disconnected:
            // 断开连接，无需特殊处理
            break
        case .reconnecting:
            // 重连中，无需特殊处理
            break
        }
    }

    /// 流式结束：把累积文本固化为一条助手消息并持久化
    private func finalizeAssistantMessage(sessionId: String) {
        let content = streamingText.isEmpty ? "(无回复)" : streamingText
        let msg = Message(
            id: UUID().uuidString,
            sessionId: sessionId,
            role: .assistant,
            content: content,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            status: .received,
            replyToId: replyToId
        )
        messages.append(msg)
        appState.dataController.saveMessage(msg)
        appState.sessionManager.incrementMessageCount(sessionId)
        streamingText = ""
        isWaiting = false
        replyToId = nil
    }

    // MARK: - 发送消息

    /// 发送消息：创建用户消息 → 通过传输层发送 → 由事件流异步接收回复
    private func sendMessage() {
        // 检查是否为斜杠命令
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("/") {
            executeSlashCommand(text)
            inputText = ""
            return
        }
        guard !text.isEmpty else { return }

        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // 1. 创建并展示用户消息（同时持久化）
        let userMessage = Message(
            id: UUID().uuidString,
            sessionId: sessionId,
            role: .user,
            content: text,
            timestamp: now,
            status: .sent,
            attachmentName: attachmentName,
            attachmentData: attachmentBase64,
            attachmentType: attachmentType,
            replyToId: replyToId
        )
        messages.append(userMessage)
        appState.dataController.saveMessage(userMessage)
        appState.sessionManager.incrementMessageCount(sessionId)

        // 重置输入
        inputText = ""
        attachmentName = nil
        attachmentBase64 = nil
        attachmentType = nil
        photoItem = nil
        streamingText = ""

        // 2. 若未连接到任何 Agent 的传输层，直接给出提示消息
        guard let transport else {
            let hint = Message(
                id: UUID().uuidString,
                sessionId: sessionId,
                role: .assistant,
                content: "未配置活跃 Agent，请先在「Agent」页面选择并配置一个 Agent。",
                timestamp: Int64(Date().timeIntervalSince1970 * 1000),
                status: .received
            )
            messages.append(hint)
            appState.dataController.saveMessage(hint)
            appState.sessionManager.incrementMessageCount(sessionId)
            return
        }

        isWaiting = true

        // 3. 通过传输层发送消息（回复由事件流异步返回并展示）
        Task {
            do {
                try await transport.sendMessage(sessionId: sessionId, content: text)
            } catch {
                await MainActor.run {
                    streamingText = "发送失败: \(error.localizedDescription)"
                    finalizeAssistantMessage(sessionId: sessionId)
                }
            }
        }
    }
}

// MARK: - 消息气泡视图

/// 单条消息气泡：用户右对齐蓝色 / 助手左对齐灰色 / 系统居中灰色小字
/// 支持附件、回复引用、时间戳、反应、长按上下文菜单
private struct MessageBubble: View {
    let message: Message
    let allMessages: [Message]
    var onReply: (Message) -> Void
    var onDelete: (Message) -> Void

    private var isUser: Bool { message.role == .user }
    private var isSystem: Bool { message.role == .system }

    var body: some View {
        if isSystem {
            // 系统消息：居中灰色小字
            systemMessageView
        } else {
            // 用户/助手消息
            chatMessageView
                .contextMenu {
                    contextMenuContent
                }
        }
    }

    // MARK: - 系统消息

    private var systemMessageView: some View {
        Text(message.content)
            .font(.caption)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .background(Color(.systemBackground), in: Capsule())
            .frame(maxWidth: .infinity)
    }

    // MARK: - 聊天消息

    private var chatMessageView: some View {
        HStack {
            if isUser { Spacer(minLength: 60) }

            VStack(alignment: isUser ? .trailing : .leading, spacing: 4) {
                // 回复引用
                if let replyId = message.replyToId,
                   let repliedMessage = allMessages.first(where: { $0.id == replyId }) {
                    replyQuoteView(repliedMessage)
                }

                // 附件展示
                if let attachmentName = message.attachmentName {
                    attachmentView(name: attachmentName, type: message.attachmentType)
                }

                // 消息正文气泡
                bubbleContent
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        isUser ? AppTheme.userBubbleColor : AppTheme.assistantBubbleColor,
                        in: RoundedRectangle(cornerRadius: 14)
                    )
                    .foregroundStyle(isUser ? .white : .primary)
                    .textSelection(.enabled)

                // 时间戳
                Text(AppTheme.timeAgo(message.timestamp))
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                // 反应 emoji 标签
                if !message.reaction.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(Array(message.reaction), id: \.self) { emoji in
                            Text(String(emoji))
                                .font(.caption)
                        }
                    }
                }
            }

            if !isUser { Spacer(minLength: 60) }
        }
    }

    // MARK: - 气泡内容（Markdown 或纯文本）

    @ViewBuilder
    private var bubbleContent: some View {
        if isUser {
            // 用户消息保持纯文本
            Text(message.content)
        } else {
            // 助手消息使用 Markdown 渲染
            MarkdownText(message.content, isUser: false)
        }
    }

    // MARK: - 回复引用视图

    /// 显示被引用消息的预览
    private func replyQuoteView(_ replied: Message) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "arrowshape.turn.up.left.fill")
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(String(replied.content.prefix(40)))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer()
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 6))
    }

    // MARK: - 附件视图

    /// 根据附件类型显示图片缩略图或文件图标
    private func attachmentView(name: String, type: AttachmentType?) -> some View {
        Group {
            if type == .image {
                // 图片附件：显示图片名称 + 图标
                Label(name, systemImage: "photo")
                    .font(.caption)
            } else {
                // 文件附件：显示文件图标 + 名称
                Label(name, systemImage: "doc")
                    .font(.caption)
            }
        }
        .padding(8)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - 上下文菜单

    /// 长按菜单：复制 / 删除 / 回复
    @ViewBuilder
    private var contextMenuContent: some View {
        Button {
            UIPasteboard.general.string = message.content
        } label: {
            Label("复制", systemImage: "doc.on.doc")
        }

        Button {
            onDelete(message)
        } label: {
            Label("删除", systemImage: "trash")
        }

        Button {
            onReply(message)
        } label: {
            Label("回复", systemImage: "arrowshape.turn.up.left")
        }
    }
}

// MARK: - 流式回复气泡

/// 流式回复气泡：左侧灰色气泡 + 闪烁光标，实时展示累积文本
/// 使用 MarkdownText 渲染流式内容
private struct StreamingBubble: View {
    let text: String
    @State private var showCursor = true

    var body: some View {
        HStack {
            MarkdownText(text + (showCursor ? "▍" : " "))
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

// MARK: - 打字指示器

/// 打字指示器：三个跳动的圆点
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

// MARK: - 空状态引导页面

/// 无活跃 Agent 时显示的连接向导
struct ChatEmptyState: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "bubble.left.and.text.bubble.right")
                .font(.system(size: 64))
                .foregroundStyle(.secondary)

            VStack(spacing: 8) {
                Text("开始对话")
                    .font(.title2)
                    .fontWeight(.bold)
                Text("请先在「Agent」页面配置并启用一个 Agent，即可开始对话。")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            NavigationLink {
                AgentsView()
            } label: {
                Text("前往配置 Agent")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(AppTheme.primaryColor, in: RoundedRectangle(cornerRadius: 12))
            }

            Spacer()
        }
        .padding()
    }
}
