import SwiftUI

/// 会话列表视图
/// 展示所有会话(置顶优先),支持点击进入聊天、滑动删除 / 切换置顶、工具栏新建会话
struct SessionsView: View {
    // 全局应用状态(通过环境注入)
    @Environment(AppState.self) private var appState

    var body: some View {
        NavigationStack {
            // 会话列表(已排序:置顶在前,再按更新时间降序)
            List {
                ForEach(appState.sessionManager.sortedSessions) { session in
                    NavigationLink {
                        // 点击行进入对应会话的聊天视图
                        ChatView(sessionId: session.id)
                    } label: {
                        SessionRow(session: session)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        // 滑动:删除会话
                        Button(role: .destructive) {
                            appState.sessionManager.deleteSession(session.id)
                        } label: {
                            Label("删除", systemImage: "trash")
                        }

                        // 滑动:切换置顶状态
                        Button {
                            appState.sessionManager.togglePin(session.id)
                        } label: {
                            Label(
                                session.isPinned ? "取消置顶" : "置顶",
                                systemImage: session.isPinned ? "pin.slash" : "pin"
                            )
                        }
                        .tint(.yellow)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("会话")
            .toolbar {
                // 工具栏:新建会话(使用默认标题 "新对话")
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        _ = appState.sessionManager.createSession()
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("新建会话")
                }
            }
            // 空状态占位(iOS 17 ContentUnavailableView)
            .overlay {
                if appState.sessionManager.sortedSessions.isEmpty {
                    ContentUnavailableView(
                        "暂无会话",
                        systemImage: "bubble.left.and.bubble.right",
                        description: Text("点击右上角加号创建新会话")
                    )
                }
            }
        }
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
