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
            List {
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
                            Label("删除", systemImage: "trash")
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
                                session.isPinned ? "取消置顶" : "置顶",
                                systemImage: session.isPinned ? "pin.slash" : "pin"
                            )
                        }
                        .tint(.yellow)

                        // 滑动:重命名会话(P1-10)
                        Button {
                            renameText = session.title
                            sessionToRename = session
                        } label: {
                            Label("重命名", systemImage: "pencil")
                        }
                        .tint(.blue)
                    }
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .listStyle(.insetGrouped)
            .animation(.easeInOut(duration: 0.25), value: filteredSessions.count)
            .navigationTitle("会话")
            // 会话列表搜索(P2-5):按标题/摘要过滤
            .searchable(text: $searchText, prompt: "搜索会话")
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
                    .accessibilityLabel("新建会话")
                }
            }
            // 空状态占位(iOS 17 ContentUnavailableView)
            .overlay {
                if filteredSessions.isEmpty {
                    if !searchText.isEmpty {
                        // 有搜索关键字但无结果:提示无匹配会话
                        ContentUnavailableView(
                            "无匹配会话",
                            systemImage: "magnifyingglass",
                            description: Text("尝试更换关键字")
                        )
                    } else {
                        // 无会话:引导新建
                        ContentUnavailableView(
                            "暂无会话",
                            systemImage: "bubble.left.and.bubble.right",
                            description: Text("点击右上角加号创建新会话")
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
        }
    }

    // MARK: - 重命名 Sheet(P1-10)

    /// 会话重命名表单:更新内存标题并同步持久化
    private func renameSheet(for session: Session) -> some View {
        NavigationStack {
            Form {
                TextField("会话名称", text: $renameText)
                    .autocorrectionDisabled()
            }
            .navigationTitle("重命名")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        sessionToRename = nil
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
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
