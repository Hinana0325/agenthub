import Foundation
import Observation

/// 协作管理器 — 多人 WebSocket 协作会话
/// 对应 Android CollaborationManager
///
/// 提供协作会话的基本数据结构和会话生命周期管理。
/// 实际的 WebSocket 信令（消息广播、光标同步、冲突解决等）
/// 需要配套的后端信令服务支持，此模块仅作为客户端框架。
@Observable
final class CollaborationManager {

    // MARK: - 数据模型

    /// 协作参与者角色
    enum ParticipantRole: String, Codable {
        case host       // 主持人：可管理会话和参与者
        case editor     // 编辑者：可修改会话内容
        case viewer     // 观察者：只读权限
    }

    /// 协作参与者
    struct Participant: Identifiable, Codable {
        /// 参与者唯一标识
        let id: String
        /// 显示名称
        let displayName: String
        /// 参与者角色
        let role: ParticipantRole
        /// 是否在线
        var isOnline: Bool
    }

    /// 协作会话
    struct CollabSession: Identifiable, Codable {
        /// 本地记录唯一标识
        let id: String
        /// 远端会话标识（用于 WebSocket 信令匹配）
        let sessionId: String
        /// 当前参与者列表
        var participants: [Participant]
        /// 会话创建时间
        let createdAt: Date
    }

    // MARK: - 属性

    /// 当前活跃的协作会话（nil 表示未加入任何会话）
    var activeCollabSession: CollabSession?

    /// 当前会话的参与者列表（便捷视图，与 activeCollabSession.participants 同步）
    var participants: [Participant] {
        get { activeCollabSession?.participants ?? [] }
        set {
            guard let session = activeCollabSession else { return }
            activeCollabSession = CollabSession(
                id: session.id,
                sessionId: session.sessionId,
                participants: newValue,
                createdAt: session.createdAt
            )
        }
    }

    /// 本地缓存的协作配置（sessionId -> 映射数据）
    ///
    /// 使用 UserDefaults 持久化已加入的会话标识，
    /// 以便应用重启后可以恢复连接状态。
    private var sessionStore: [String: [String: Any]] {
        get {
            guard let data = UserDefaults.standard.data(forKey: "collab_sessions"),
                  let dict = try? JSONSerialization.jsonObject(with: data) as? [String: [String: Any]]
            else { return [:] }
            return dict
        }
        set {
            if let data = try? JSONSerialization.data(withJSONObject: newValue) {
                UserDefaults.standard.set(data, forKey: "collab_sessions")
            }
        }
    }

    // MARK: - 会话生命周期

    /// 创建新的协作会话。
    ///
    /// 创建者自动成为 host 角色的参与者。
    /// - Parameters:
    ///   - sessionId: 会话唯一标识
    ///   - userId: 创建者用户 ID
    ///   - displayName: 创建者显示名称
    func createSession(sessionId: String, userId: String = UUID().uuidString, displayName: String = "我") {
        let host = Participant(
            id: userId,
            displayName: displayName,
            role: .host,
            isOnline: true
        )

        let session = CollabSession(
            id: UUID().uuidString,
            sessionId: sessionId,
            participants: [host],
            createdAt: Date()
        )

        activeCollabSession = session
        saveSessionToStore(sessionId)

        // TODO: 建立与后端信令服务的 WebSocket 连接
        // TODO: 广播 session:created 事件给其他潜在参与者
    }

    /// 加入已有的协作会话。
    ///
    /// - Parameters:
    ///   - sessionId: 目标会话 ID
    ///   - code: 邀请码（用于后端验证）
    ///   - userId: 加入者用户 ID
    ///   - displayName: 加入者显示名称
    func joinSession(sessionId: String, code: String, userId: String = UUID().uuidString, displayName: String = "参与者") {
        // 以 editor 角色加入
        let participant = Participant(
            id: userId,
            displayName: displayName,
            role: .editor,
            isOnline: true
        )

        let session = CollabSession(
            id: UUID().uuidString,
            sessionId: sessionId,
            participants: [participant],
            createdAt: Date()
        )

        activeCollabSession = session
        saveSessionToStore(sessionId)

        // TODO: 通过 WebSocket 发送 join 请求到后端信令服务
        // TODO: 后端返回当前参与者列表后更新 session.participants
        // TODO: 验证邀请码 code 的有效性
    }

    /// 离开当前协作会话。
    ///
    /// 清除本地会话状态，通知后端信令服务移除参与者。
    func leaveSession() {
        guard let session = activeCollabSession else { return }

        // TODO: 通过 WebSocket 发送 leave 通知给后端信令服务
        // TODO: 若为 host 且会话无其他参与者，向后端请求销毁会话

        removeFromStore(sessionId: session.sessionId)
        activeCollabSession = nil
    }

    // MARK: - 参与者管理

    /// 向当前会话添加参与者。
    ///
    /// 仅 host 角色有权限添加参与者。
    /// - Parameter participant: 待添加的参与者
    func addParticipant(_ participant: Participant) {
        guard var session = activeCollabSession else { return }

        // 权限检查：仅 host 可添加参与者
        guard session.participants.contains(where: { $0.role == .host && $0.isOnline }) else {
            // TODO: 通过 error 回调通知 UI 层权限不足
            return
        }

        // 避免重复添加
        guard !session.participants.contains(where: { $0.id == participant.id }) else { return }

        session.participants.append(participant)
        activeCollabSession = session

        // TODO: 通过 WebSocket 广播 participant:joined 事件
    }

    /// 从当前会话移除参与者。
    ///
    /// 仅 host 角色有权限移除参与者。
    /// - Parameter id: 待移除参与者的 ID
    func removeParticipant(id: String) {
        guard var session = activeCollabSession else { return }

        // 权限检查：仅 host 可移除参与者
        guard session.participants.contains(where: { $0.role == .host && $0.isOnline }) else {
            return
        }

        session.participants.removeAll { $0.id == id }
        activeCollabSession = session

        // TODO: 通过 WebSocket 广播 participant:left 事件
        // TODO: 若被移除的参与者有活跃连接，后端应主动断开其 WebSocket
    }

    // MARK: - 私有方法

    /// 将会话 ID 保存到本地缓存
    private func saveSessionToStore(_ sessionId: String) {
        var store = sessionStore
        store[sessionId] = [
            "joinedAt": ISO8601DateFormatter().string(from: Date()),
            "sessionId": sessionId
        ]
        sessionStore = store
    }

    /// 从本地缓存移除会话
    private func removeFromStore(sessionId: String) {
        var store = sessionStore
        store.removeValue(forKey: sessionId)
        sessionStore = store
    }
}
