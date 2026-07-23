import SwiftUI

/// 会话列表视图
/// 展示所有会话(置顶优先),支持点击进入聊天、滑动删除 / 切换置顶、工具栏新建会话
struct SessionsView: View {
    // 全局应用状态(通过环境注入)
    @Environment(AppState.self) private var appState

    // MARK: - 重命名状态(P1-10)
    /// 是否显示重命名 Sheet
    @State private var showRenameSheet: Bool = false  // 已废弃：sheet(item:) 接管，仅保留兼容
    /// 待重命名的会话
    @State private var sessionToRename: Session?
    /// 重命名输入框文本
    @State private var renameText: String = ""

    // MARK: - 搜索状态(P2-5)
    /// 搜索关键字(用于按标题过滤会话列表)
    @State private var searchText: String = ""

    // MARK: - 加载/错误态(v5.0 P0)
    /// 首屏骨架屏开关：true 时渲染 SessionSkeletonRow 占位行
    @State private var isLoading: Bool = true
    /// 加载错误信息：非 nil 时覆盖列表渲染 ErrorStateView
    @State private var errorMessage: String? = nil

    /// 根据搜索关键字过滤后的会话列表(置顶优先,再按更新时间降序)
    private var filteredSessions: [Session] {
        let keyword = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !keyword.isEmpty else {
            return appState.sessionManager.sortedSessions
        }
        return appState.sessionManager.sortedSessions.filter {
            $0.title.lowercased().contains(keyword) || $0.summary.lowercased().contains(keyword)
        }
    }

    var body: some View {
        NavigationStack {
            // 会话列表(已排序:置顶在前,再按更新时间降序)
            // v5.0 P0: isLoading 时渲染 SessionSkeletonRow×5 骨架屏
            List {
                if isLoading {
                    SkeletonList(repeat: 5) { SessionSkeletonRow() }
                } else {
                    ForEach(filteredSessions) { session in
                        NavigationLink {
                            // 点击行进入对应会话的聊天视图
                            ChatView(sessionId: session.id)
                        } label: {
                            SessionRow(session: session)
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            // 滑动:删除会话
                            Button(role: .destructive) {
                                HapticFeedback.medium()
                                // 先更新内存状态(SessionManager)
                                appState.sessionManager.deleteSession(session.id)
                                // 同步删除持久化层的消息记录与会话本体,避免 App 重启后数据残留
                                appState.dataController.deleteMessages(sessionId: session.id)
                                appState.dataController.deleteSession(session.id)
                            } label: {
                                Label(String(localized: "common.delete"), systemImage: "trash")
                            }

                            // 滑动:切换置顶状态
                            Button {
                                HapticFeedback.selection()
                                // 更新内存状态(SessionManager)
                                appState.sessionManager.togglePin(session.id)
                                // 持久化置顶状态:从 sessionManager 取回最新 session 对象后整体 upsert
                                if let updated = appState.sessionManager.sessions.first(where: { $0.id == session.id }) {
                                    appState.dataController.saveSession(updated)
                                }
                            } label: {
                                Label(
                                    session.isPinned ? String(localized: "session.unpin") : String(localized: "session.pin"),
                                    systemImage: session.isPinned ? "pin.slash" : "pin"
                                )
                            }
                            .tint(.yellow)

                            // 滑动:重命名会话(P1-10)
                            Button {
                                renameText = session.title
                                sessionToRename = session
                            } label: {
                                Label(String(localized: "session.rename"), systemImage: "pencil")
                            }
                            .tint(.blue)
                        }
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
            }
            .listStyle(.insetGrouped)
            .animation(.easeInOut(duration: 0.25), value: filteredSessions.count)
            .navigationTitle(String(localized: "tab.sessions"))
            // 会话列表搜索(P2-5):按标题/摘要过滤
            .searchable(text: $searchText, prompt: String(localized: "sessions.search.placeholder"))
            .toolbar {
                // 工具栏:新建会话(使用默认标题 "新对话")
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        HapticFeedback.light()
                        // 先创建内存会话(SessionManager)
                        let session = appState.sessionManager.createSession()
                        // 同步保存到 SwiftData,确保 App 重启后会话不丢失
                        appState.dataController.saveSession(session)
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel(Text(String(localized: "accessibility.new_session")))
                }
            }
            // 空状态占位(iOS 17 ContentUnavailableView)
            .overlay {
                if let errorMessage {
                    // v5.0 P0: 加载错误时覆盖列表展示 ErrorStateView + onRetry 重载
                    ErrorStateView(
                        icon: "bubble.left.and.bubble.right",
                        title: String(localized: "common.load_failed"),
                        message: errorMessage,
                        onRetry: { reloadSessions() }
                    )
                    .background(AppTheme.backgroundColor)
                } else if !isLoading && filteredSessions.isEmpty {
                    if !searchText.isEmpty {
                        // 有搜索关键字但无结果:提示无匹配会话
                        ContentUnavailableView(
                            String(localized: "sessions.search.empty.title"),
                            systemImage: "magnifyingglass",
                            description: Text(String(localized: "sessions.search.empty.description"))
                        )
                    } else {
                        // 无会话:引导新建
                        ContentUnavailableView(
                            String(localized: "session.empty.title"),
                            systemImage: "bubble.left.and.bubble.right",
                            description: Text(String(localized: "session.empty.description_alt"))
                        )
                    }
                }
            }
            // 重命名 Sheet(P1-10)
            // HIG：使用 sheet(item:) 而非 sheet(isPresented:) + 平行 @State item，
            // 保证 sheet 关闭时 item 自动 nil，避免下次开启显示陈旧数据
            .sheet(item: $sessionToRename) { session in
                renameSheet(for: session)
            }
            // v5.0 P0: 首屏加载骨架屏入口
            .task {
                if isLoading {
                    await loadSessions()
                }
            }
        }
    }

    // MARK: - 加载(v5.0 P0)

    /// 加载会话列表。
    /// 会话数据由 `appState.sessionManager` 持有（已在 App 启动时从 SwiftData 加载），
    /// 此处仅做骨架屏过渡 + 错误兜底：保留 try/catch 是为了未来切换异步数据源时不破坏 UI。
    private func loadSessions() async {
        // 短暂展示骨架屏，让用户感知「正在加载」（数据本身瞬间可得）
        try? await Task.sleep(nanoseconds: 250_000_000)
        // 当前数据源是同步可读的 @Observable 状态，无抛错路径；
        // 若未来切换为远程 API，在此处 do/catch 并设置 errorMessage
        isLoading = false
    }

    /// 错误重试入口：重置状态后重新加载（onRetry 闭包要求 () -> Void）
    private func reloadSessions() {
        isLoading = true
        errorMessage = nil
        Task { await loadSessions() }
    }

    // MARK: - 重命名 Sheet(P1-10)

    /// 会话重命名表单:更新内存标题并同步持久化
    private func renameSheet(for session: Session) -> some View {
        NavigationStack {
            Form {
                TextField(String(localized: "session.rename.placeholder"), text: $renameText)
                    .autocorrectionDisabled()
            }
            .navigationTitle(String(localized: "session.rename.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) {
                        sessionToRename = nil
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.save")) {
                        let trimmed = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else { return }
                        // 更新内存标题
                        appState.sessionManager.updateTitle(session.id, title: trimmed)
                        // 同步持久化:取回最新 session 对象后整体 upsert
                        if let updated = appState.sessionManager.sessions.first(where: { $0.id == session.id }) {
                            appState.dataController.saveSession(updated)
                        }
                        sessionToRename = nil
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// 单个会话行视图
private struct SessionRow: View {
    let session: Session

    var body: some View {
        HStack(spacing: 10) {
            // 置顶指示图标
            if session.isPinned {
                Image(systemName: "pin.fill")
                    .foregroundStyle(.yellow)
                    .font(.caption)
                    .accessibilityHidden(true)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(session.title)
                    .font(.headline)
                    .lineLimit(1)

                HStack(spacing: 10) {
                    // 消息数量
                    Label("\(session.messageCount)", systemImage: "message")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    // 最近更新时间(updatedAt 为毫秒时间戳)
                    Text(AppTheme.timeAgo(session.updatedAt))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }
}
