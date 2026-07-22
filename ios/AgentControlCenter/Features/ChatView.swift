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
    /// E2E 加密密钥（从 Keychain 读取，不进入 UserDefaults；视图出现时加载到内存）
    @State private var encryptionPassphrase: String = ""
    /// 字体大小偏好(P1-4):与设置页共用 UserDefaults 键,注入环境后供 MessageBubble / MarkdownText 使用
    //
    // CI-fix: 原 `@AppStorage("fontSize") private var fontSize: FontSize = .medium`
    // 在 Xcode 16.4 下报 "no exact matches in call to initializer"。改为手动
    // UserDefaults 桥接（@State + .onChange 写回），与 SettingsView 保持一致。
    @State private var fontSize: FontSize = FontSize.loadFromUserDefaults()

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
    /// 输入栏玻璃 morph 命名空间（send ↔ stop 形变过渡）
    @Namespace private var inputGlassNS
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
    /// 发送任务（修复 C1: 原 sendMessage 的 Task 是孤儿任务未保存，
    /// stopGenerating 只 cancel eventTask 不 cancel 它，导致 Stop 按钮失效，
    /// transport.sendMessage 的 HTTP/SSE 流仍在后台消耗带宽和 token）
    @State private var sendTask: Task<Void, Never>?

    // MARK: - 状态 — 交互
    /// 正在回复的消息 ID
    @State private var replyToId: String?
    /// 当前流式响应对应的用户消息 ID（修复 H3: 用于 finalizeAssistantMessage
    /// 设置助手消息的 replyToId。原实现把用户消息的 replyToId 直接赋给助手消息，
    /// 导致助手气泡引用的是"用户回复的目标"而非"用户刚发的那条"）
    @State private var currentUserMessageId: String?
    /// 斜杠命令匹配（输入框以 / 开头时）
    @State private var showSlashCommands: Bool = false
    /// 是否显示帮助 Sheet
    @State private var showHelpSheet: Bool = false
    /// 帮助文本内容
    @State private var helpText: String = ""
    /// 语音输入管理器
    @State private var voiceManager = VoiceInputManager()
    /// 是否显示 Agent 配置 Sheet（空状态引导时触发）
    @State private var showAgentConfig: Bool = false
    /// 错误提示文本（流式错误等场景，不固化为消息）
    @State private var errorMessage: String?
    /// 输入框焦点状态（用于键盘完成按钮收起键盘）
    @FocusState private var isInputFocused: Bool

    // MARK: - Body

    var body: some View {
        VStack(spacing: 0) {
            // MARK: 消息列表区域
            // 无活跃 Agent 且无消息时显示空状态引导，否则显示消息列表
            if !hasActiveAgent && messages.isEmpty {
                ChatEmptyState(configureAction: { showAgentConfig = true })
            } else {
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
                // 滚动时交互式收起键盘
                .scrollDismissesKeyboard(.interactively)
                // SW-M2: 使用 .task 替代 .onAppear — setupTransport 会启动事件消费 Task，
                // .task 在视图销毁时自动取消，与 .onDisappear 的 cleanup() 双重保障
                .task {
                    // E2E passphrase 从 Keychain 加载到内存，避免持久化于 UserDefaults
                    encryptionPassphrase = KeychainManager.loadPassphrase()
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
                    // 流式滚动不用动画，避免高频更新导致的卡顿
                    scrollToBottom(proxy: proxy, animated: false)
                }
            }
            } // 结束 else（消息列表分支）

            Divider()

            // MARK: 底部输入栏
            inputBar
        }
        // 注入字体大小偏好到环境,MessageBubble 内的 Text 与 MarkdownText 均可读取(P1-4)
        .environment(\.appFontSize, fontSize)
        // CI-fix: fontSize 改为手动 UserDefaults 桥接（替代 @AppStorage）
        .onChange(of: fontSize) { _, newValue in
            newValue.saveToUserDefaults()
        }
        // 修复 5: 监听 SettingsView 广播的 fontSize 变更通知。
        // 已打开的 ChatView 不会自动响应 UserDefaults 外部写入（@State 仅初始化一次），
        // 这里监听通知后重新 loadFromUserDefaults 刷新 @State，再触发 .environment 注入。
        .onReceive(NotificationCenter.default.publisher(for: FontSize.didChangeNotification)) { _ in
            let newFont = FontSize.loadFromUserDefaults()
            if newFont != fontSize {
                fontSize = newFont
            }
        }
        .navigationTitle(sessionTitle)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showHelpSheet) {
            helpSheetView
        }
        .sheet(isPresented: $showAgentConfig) {
            // 空状态引导跳转的 Agent 配置页
            NavigationStack {
                AgentsView()
            }
        }
        // 流式错误等场景的错误提示 banner
        .alert(String(localized: "common.error.title"),
               isPresented: Binding(
                   get: { errorMessage != nil },
                   set: { if !$0 { errorMessage = nil } }
               ),
               presenting: errorMessage
        ) { _ in
            Button(String(localized: "common.ok"), role: .cancel) { errorMessage = nil }
        } message: { msg in
            Text(msg)
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

            // 图片附件缩略图预览条 — 选中图片后在输入栏顶部展示，可点 X 清除
            if let base64 = attachmentBase64,
               let data = Data(base64Encoded: base64),
               let uiImage = UIImage(data: data) {
                HStack(spacing: AppTheme.Spacing.sm) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 48, height: 48)
                        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.sm))
                    Text("已选择图片")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button {
                        attachmentBase64 = nil
                        attachmentName = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityLabel("清除已选图片")
                }
                .padding(.horizontal, AppTheme.Spacing.lg)
                .padding(.top, AppTheme.Spacing.sm)
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
                    .focused($isInputFocused)
                    .toolbar {
                        // 键盘上方添加完成按钮，用于收起键盘
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button("完成") { isInputFocused = false }
                        }
                    }
                    .onChange(of: inputText) { _, newValue in
                        // 输入 `/` 开头时弹出斜杠命令内联列表（支持边输入边过滤）
                        showSlashCommands = newValue.hasPrefix("/")
                    }
                    .submitLabel(.send)
                    .onSubmit {
                        if showSlashCommands {
                            showSlashCommands = false
                        } else if !isWaiting {
                            // 修复 H5: 原实现不检查 isWaiting，回车键绕过 canSend 禁用逻辑，
                            // 用户在等待响应时按回车会再创建一条 user message 并再发起一次
                            // transport.sendMessage，多个并发流同时往同一个 streamingText
                            // 累积，UI 完全错乱。
                            sendMessage()
                        }
                    }

                // 语音输入按钮 + 发送/停止按钮：放入同一个 GlassContainer
                // 使三块玻璃融合为整体，且 send ↔ stop 通过共享 glassMorphID 实现 morph
                // R4: 改用 glassMorphID 包装，iOS 18 回退为无 morph（仅按钮正常显示）
                GlassContainer {
                    voiceInputButton
                        .glassMorphID("voice", in: inputGlassNS)

                    // 发送按钮 / 停止按钮（共享 "action" ID，isWaiting 切换时玻璃形变过渡）
                    if isWaiting {
                        stopButton
                            .glassMorphID("action", in: inputGlassNS)
                    } else {
                        sendButton
                            .glassMorphID("action", in: inputGlassNS)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(AppTheme.secondaryBackground)
        }
        // 斜杠命令内联悬浮列表（替代 sheet，在输入栏上方显示）
        .overlay(alignment: .top) {
            if showSlashCommands {
                SlashCommandList(commands: filteredCommands) { cmd in
                    executeSlashCommand(cmd)
                    inputText = ""
                    showSlashCommands = false
                }
                .padding(.bottom, 4)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: showSlashCommands)
        // 左右滑动切换会话 — 使用 simultaneousGesture 避免拦截输入框交互
        .simultaneousGesture(
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
            .accessibilityLabel("取消回复")
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
    ///
    /// HIG (iOS 26 Liquid Glass)：
    /// - 玻璃 tint 通过 `Glass.tint(_:)` 注入颜色，避免在玻璃下垫不透明 Circle 遮挡采样
    /// - 玻璃直接修饰 Button（不是 label 内部），使 glassEffectID 与效果共享 anchor，触发 send↔stop morph
    /// - 触控区固定 44×44pt（HIG 最低）
    private var voiceInputButton: some View {
        Button {
            Task { await toggleVoiceInput() }
        } label: {
            Image(systemName: voiceManager.state == .listening ? "mic.fill" : "mic")
                .font(.title3)
                .foregroundStyle(voiceManager.state == .listening ? .red : AppTheme.primaryColor)
                .frame(width: 44, height: 44)
        }
        .disabled(isWaiting)
        // R4: glassTinted 内部 if #available(iOS 26, *) 守卫 .glassEffect/.tint，
        // iOS 18 走 ultraThinMaterial + tint 色块叠加回退
        .glassTinted(
            voiceManager.state == .listening ? Color.red.opacity(0.25) : .clear,
            in: GlassTokens.circleShape
        )
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
                .frame(width: 44, height: 44)
        }
        .disabled(!canSend)
        // R4: glassTinted 包装守卫
        .glassTinted(
            canSend ? AppTheme.primaryColor : Color.gray.opacity(0.3),
            in: GlassTokens.circleShape
        )
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
                .frame(width: 44, height: 44)
        }
        // R4: glassTinted 包装守卫
        .glassTinted(
            Color.red,
            in: GlassTokens.circleShape
        )
        .accessibilityLabel("停止生成")
    }

    // MARK: - 是否允许发送

    private var canSend: Bool {
        !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isWaiting
    }

    // MARK: - 斜杠命令数据

    /// 全部可用的斜杠命令
    private var allSlashCommands: [SlashCommandItem] {
        [
            SlashCommandItem(command: "/clear", description: "清空当前会话的所有消息"),
            SlashCommandItem(command: "/new", description: "新建会话并跳转"),
            SlashCommandItem(command: "/reconnect", description: "重新连接传输层"),
            SlashCommandItem(command: "/help", description: "显示帮助文本")
        ]
    }

    /// 根据输入框文本过滤的斜杠命令列表（用于内联悬浮列表展示）
    private var filteredCommands: [SlashCommandItem] {
        let input = inputText.lowercased()
        // 仅输入 "/" 或为空时展示全部
        if input.isEmpty || input == "/" {
            return allSlashCommands
        }
        return allSlashCommands.filter { $0.command.lowercased().contains(input) }
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
        // 修复 H4: 原实现只清 DB + messages 数组，不重置流式状态。如果在流式生成中
        // 执行 /clear，UI 列表变空但 isWaiting 仍为 true、streamingText 仍累积、
        // eventTask 仍在跑，下一个 delta 到来时 StreamingBubble 会从空列表里冒出。
        sendTask?.cancel()
        eventTask?.cancel()
        isWaiting = false
        streamingText = ""
        errorMessage = nil
        replyToId = nil
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

        // H9 修复：协议要求附件大小上限 10MB（10485760 字节，压缩前判定）。
        // 参考 Android ChatViewModel.kt:1095 实现。超出上限直接拒绝并提示用户，
        // 不进入后续压缩流程（避免大文件占内存与解码耗时）。
        if data.count > 10 * 1024 * 1024 {
            errorMessage = String(
                format: NSLocalizedString("error.attachment.too_large", comment: "附件过大提示"),
                "10MB"
            )
            attachmentName = nil
            attachmentBase64 = nil
            attachmentType = nil
            photoItem = nil
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
    /// 仅当严格水平滑动（水平位移远大于垂直位移）时才触发会话切换，
    /// 避免与输入框/滚动等垂直操作冲突。
    private func handleSwipe(value: DragGesture.Value) {
        // 方向判断：水平位移需大于垂直位移的 2 倍且超过 50pt 才视为水平滑动
        let isHorizontalSwipe = abs(value.translation.width) > abs(value.translation.height) * 2
            && abs(value.translation.width) > 50
        guard isHorizontalSwipe else { return }

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

    /// 删除指定消息（同时同步会话消息计数，避免显示与实际数量不一致）
    private func deleteMessage(_ message: Message) {
        HapticFeedback.medium()
        appState.dataController.deleteMessage(id: message.id)
        messages.removeAll { $0.id == message.id }
        appState.sessionManager.decrementMessageCount(sessionId)
    }

    // MARK: - 回复消息

    /// 设置回复目标
    private func replyToMessage(_ message: Message) {
        replyToId = message.id
    }

    // MARK: - 停止生成

    /// 取消当前事件任务并停止生成
    private func stopGenerating() {
        // 修复 C1: 同时 cancel sendTask，否则 transport.sendMessage 的 HTTP/SSE
        // 流仍在后台运行，继续消耗带宽和 token，Stop 按钮实际失效。
        sendTask?.cancel()
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
        // 修复 C1: 同时 cancel sendTask，避免视图销毁后 sendMessage Task 仍持有
        // transport + URLSession + in-flight data task 继续运行（资源泄漏）
        sendTask?.cancel()
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
                // SW-M7: Task 闭包通过 self.appState 访问继承 MainActor 隔离，
                // 无需 MainActor.run；handleEvent 本身也是 MainActor 隔离的方法
                handleEvent(event, sessionId: sid)
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
        case .error(_, let message):
            // C3 修复：AgentEvent.error 新增 code 关联值，此处忽略 code 仅用 message
            HapticFeedback.error()
            // 错误不固化为助手消息，更新最后一条用户消息状态为 failed
            if let lastUserMsg = messages.last(where: { $0.role == .user }),
               let idx = messages.firstIndex(where: { $0.id == lastUserMsg.id }) {
                messages[idx].status = .failed
                appState.dataController.saveMessage(messages[idx])
            }
            // 显示错误 toast/banner
            errorMessage = message
            isWaiting = false
            streamingText = ""
            // 修复 M6: 错误路径也需重置 replyToId 和 currentUserMessageId，
            // 否则发送失败后用户下一条消息会带着上次的回复上下文
            replyToId = nil
            currentUserMessageId = nil
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
        HapticFeedback.success()
        let content = streamingText.isEmpty ? "(无回复)" : streamingText
        let msg = Message(
            id: UUID().uuidString,
            sessionId: sessionId,
            role: .assistant,
            content: content,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            status: .received,
            // 修复 H3: 助手消息的 replyToId 语义为"助手在回复哪条用户消息"，
            // 应该是 currentUserMessageId，而非用户消息的 replyToId（用户回复的目标）。
            replyToId: currentUserMessageId
        )
        messages.append(msg)
        appState.dataController.saveMessage(msg)
        appState.sessionManager.incrementMessageCount(sessionId)
        streamingText = ""
        isWaiting = false
        replyToId = nil
        currentUserMessageId = nil
    }

    // MARK: - 发送消息

    /// 发送消息：创建用户消息 → 通过传输层发送 → 由事件流异步接收回复
    private func sendMessage() {
        HapticFeedback.light()
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
            attachmentType: attachmentType,
            attachmentData: attachmentBase64,
            attachmentName: attachmentName,
            replyToId: replyToId
        )
        messages.append(userMessage)
        appState.dataController.saveMessage(userMessage)
        appState.sessionManager.incrementMessageCount(sessionId)
        // 修复 H3: 保存当前用户消息 ID，finalizeAssistantMessage 时用于设置
        // 助手消息的 replyToId（语义为"助手在回复哪条用户消息"）
        currentUserMessageId = userMessage.id

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
        // 修复 C1: 保存 Task 到 sendTask，stopGenerating / cleanup 时 cancel，
        // 否则 Stop 按钮只 cancel eventTask，transport.sendMessage 的 HTTP/SSE
        // 流仍在后台运行，继续消耗带宽和 token。
        sendTask = Task {
            do {
                try await transport.sendMessage(sessionId: sessionId, content: text)
            } catch {
                // SW-M7: Task 闭包通过 self.transport 访问 MainActor-isolated 状态，
                // 闭包本身已隔离到 MainActor，无需 MainActor.run
                // 发送失败不固化为助手消息：清空流式文本、退出 waiting，
                // 通过 errorMessage banner 提示用户可重试
                streamingText = ""
                isWaiting = false
                errorMessage = String(
                    format: NSLocalizedString("error.send.failed", comment: ""),
                    error.localizedDescription
                )
                // 修复 M6: 错误路径也需重置 replyToId 和 currentUserMessageId
                replyToId = nil
                currentUserMessageId = nil
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

    /// 字体大小偏好(P1-4):由 ChatView 注入,用于用户消息 Text 的字号
    @Environment(\.appFontSize) private var fontSize

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

                // 气泡底部状态行 — 仅用户消息显示发送状态图标
                HStack(spacing: 4) {
                    if message.role == .user {
                        switch message.status {
                        case .sending:
                            Image(systemName: "clock").foregroundStyle(.secondary)
                        case .sent:
                            Image(systemName: "checkmark").foregroundStyle(.secondary)
                        case .received:
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                        case .failed:
                            Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red)
                        }
                    }
                }
                .font(.caption2)

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
            // 用户消息保持纯文本,应用字体大小偏好(P1-4)
            Text(message.content)
                .font(fontSize.font)
        } else {
            // 助手消息使用 Markdown 渲染(MarkdownText 内部已读取环境字体大小)
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
        // 黑框修复: 原 `.thinMaterial` 在深色模式下有暗色基底，叠加在消息气泡上
        // 会形成近黑色小色块。改用 secondaryBackground 不透明色，深浅模式自适应。
        .background(AppTheme.secondaryBackground, in: RoundedRectangle(cornerRadius: 8))
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

// MARK: - 斜杠命令内联悬浮列表

/// 斜杠命令数据项
private struct SlashCommandItem: Identifiable {
    let id = UUID()
    let command: String
    let description: String
}

/// 斜杠命令内联悬浮列表（替代 sheet，轻量地在输入栏上方展示）
private struct SlashCommandList: View {
    let commands: [SlashCommandItem]
    var onSelect: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(commands.enumerated()), id: \.element.id) { index, item in
                Button {
                    onSelect(item.command)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.command)
                            .font(.headline)
                        Text(item.description)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, AppTheme.Spacing.md)
                    .padding(.vertical, AppTheme.Spacing.sm)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .tint(.primary)

                // 非最后一项显示分隔线
                if index < commands.count - 1 {
                    Divider()
                }
            }
        }
        .background(
            AppTheme.secondaryBackground,
            in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md)
        )
        .shadow(color: .black.opacity(0.1), radius: 4, y: 2)
        .padding(.horizontal, AppTheme.Spacing.md)
    }
}

// MARK: - 空状态引导页面

/// 无活跃 Agent 时显示的连接向导
struct ChatEmptyState: View {
    @Environment(AppState.self) private var appState

    /// 可选的配置回调；若提供则用按钮触发（便于以 sheet 形式弹出），否则使用 NavigationLink
    var configureAction: (() -> Void)? = nil

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

            if let configureAction {
                // 通过闭包触发配置（例如弹出 sheet）
                Button {
                    configureAction()
                } label: {
                    Text("前往配置 Agent")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 12)
                        .background(AppTheme.primaryColor, in: RoundedRectangle(cornerRadius: 12))
                }
            } else {
                // 默认使用 NavigationLink 直接跳转
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
            }

            Spacer()
        }
        .padding()
    }
}
