import Foundation
import Observation

// MARK: - SessionManager
// 对应 Android SessionManager

/// 会话管理器 — 管理用户与 Agent 之间的交互会话。
///
/// 职责：
/// - 创建、删除、切换会话
/// - 维护会话列表的响应式状态（通过 @Observable）
/// - 支持会话置顶、标题更新、消息计数等操作
/// - 提供排序后的会话列表（置顶优先，再按更新时间降序）
///
/// 与 Android 版的区别：
/// - Android 使用 StateFlow + Map<String, Session>，iOS 使用 @Observable + [Session]
/// - iOS 版增加了 title、isPinned、sortedSessions 等 UI 友好属性
/// - 数据模型复用 Models/Session.swift 中的 Session 结构体
///
/// `@MainActor` 隔离保证 `sessions` / `activeSessionId` 等响应式状态的读写
/// 均在主线程进行，避免 SwiftUI 视图读取时发生数据竞争。
@MainActor
@Observable
final class SessionManager {

    /// 所有会话列表（按创建顺序，最新在前）
    private(set) var sessions: [Session] = []

    /// 当前活跃会话
    private(set) var activeSession: Session?

    // MARK: - 会话生命周期

    /// 创建新会话
    /// - Parameter title: 会话标题，默认 "新对话"
    /// - Returns: 新创建的会话
    func createSession(title: String = "新对话") -> Session {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let session = Session(
            id: UUID().uuidString,
            title: title,
            createdAt: now,
            updatedAt: now
        )
        // 新会话插入到列表头部，确保 UI 中最先展示
        sessions.insert(session, at: 0)
        return session
    }

    /// 删除会话
    /// - Parameter sessionId: 要删除的会话 ID
    func deleteSession(_ sessionId: String) {
        sessions.removeAll { $0.id == sessionId }
        // 如果删除的是当前活跃会话，清空活跃引用
        if activeSession?.id == sessionId {
            activeSession = nil
        }
    }

    /// 设置活跃会话
    /// - Parameter sessionId: 要设为活跃的会话 ID
    func setActive(sessionId: String) {
        activeSession = sessions.first { $0.id == sessionId }
    }

    // MARK: - 会话属性更新

    /// 更新会话标题
    /// - Parameters:
    ///   - sessionId: 会话 ID
    ///   - title: 新标题
    func updateTitle(_ sessionId: String, title: String) {
        guard let index = sessions.firstIndex(where: { $0.id == sessionId }) else { return }
        var session = sessions[index]
        session.title = title
        session.updatedAt = Int64(Date().timeIntervalSince1970 * 1000)
        sessions[index] = session
    }

    /// 递增会话消息计数
    /// - Parameter sessionId: 会话 ID
    func incrementMessageCount(_ sessionId: String) {
        guard let index = sessions.firstIndex(where: { $0.id == sessionId }) else { return }
        var session = sessions[index]
        session.messageCount += 1
        session.updatedAt = Int64(Date().timeIntervalSince1970 * 1000)
        sessions[index] = session
    }

    /// 递减会话消息计数（删除消息时调用，最低为 0）
    /// - Parameter sessionId: 会话 ID
    func decrementMessageCount(_ sessionId: String) {
        guard let index = sessions.firstIndex(where: { $0.id == sessionId }) else { return }
        var session = sessions[index]
        session.messageCount = max(0, session.messageCount - 1)
        session.updatedAt = Int64(Date().timeIntervalSince1970 * 1000)
        sessions[index] = session
    }

    /// 切换会话置顶状态
    /// - Parameter sessionId: 会话 ID
    func togglePin(_ sessionId: String) {
        guard let index = sessions.firstIndex(where: { $0.id == sessionId }) else { return }
        var session = sessions[index]
        session.isPinned.toggle()
        sessions[index] = session
    }

    // MARK: - 查询

    /// 排序后的会话列表：置顶优先，再按更新时间降序
    var sortedSessions: [Session] {
        sessions.sorted { a, b in
            // 置顶的排前面
            if a.isPinned != b.isPinned { return a.isPinned }
            // 同为置顶或非置顶时，按更新时间降序
            return a.updatedAt > b.updatedAt
        }
    }
}
