import Foundation
import Observation

// MARK: - AnalyticsManager
// 对应 Android com.agentcontrolcenter.app.core.analytics.AnalyticsManager
//
// 职责：隐私优先的本地埋点系统
// - 纯本地埋点，不集成任何第三方 SDK（Firebase / Sentry Analytics / Amplitude 等）
// - 所有事件仅写入内存 ring buffer，不落盘，不网络上报
// - 用户可在设置页关闭埋点（analyticsEnabled），关闭后不再记录新事件
// - 已记录的事件可通过 exportEvents() 导出为 JSON

/// 隐私优先的本地埋点管理器。
///
/// 设计理念：
/// - 纯本地埋点，不集成任何第三方 SDK（Firebase / Sentry Analytics / Amplitude 等）
/// - 所有事件仅写入内存 ring buffer，不落盘，不网络上报
/// - 用户可在设置页关闭埋点（`analyticsEnabled`），关闭后不再记录新事件
/// - 已记录的事件可通过 `exportEvents()` 导出为 JSON 供用户自行查看或分享
///
/// Ring buffer 实现：
/// - 使用 `Array` 作为环形缓冲区，容量上限 `maxEvents`（1000 条）
/// - 超过容量时丢弃最旧的事件（`removeFirst()`），保证内存占用可控
/// - 通过 concurrent `DispatchQueue` + barrier 实现线程安全的读写
///
/// 持久化说明：
/// - `analyticsEnabled` 通过 `UserDefaults` 持久化（与 `@AppStorage` 等效）
/// - `@Observable` 类无法直接使用 `@AppStorage`（后者依赖 SwiftUI View 生命周期），
///   因此使用 `didSet` + `UserDefaults` 实现等效的自动持久化
@MainActor
@Observable
final class AnalyticsManager {

    // MARK: - 事件类型枚举

    /// 埋点事件类型枚举。
    ///
    /// 与 Android 端 `AnalyticsManager.AnalyticsEvent` 对齐，
    /// 事件名使用 snake_case 以保持跨平台一致性。
    enum AnalyticsEvent: String {
        /// 页面浏览 — 携带 screen_name 参数
        case screenView = "screen_view"
        /// 按钮点击 — 携带 button_id 参数
        case buttonClick = "button_click"
        /// Agent 连接成功
        case agentConnect = "agent_connect"
        /// Agent 断开连接
        case agentDisconnect = "agent_disconnect"
        /// 用户发送消息
        case messageSent = "message_sent"
        /// 收到 Agent 回复消息
        case messageReceived = "message_received"
        /// 错误事件 — 携带 error_type / error_message 参数
        case error = "error"
        /// 会话创建
        case sessionCreated = "session_created"
        /// 功能使用 — 携带 feature_name 参数
        case featureUsed = "feature_used"
    }

    // MARK: - 数据模型

    /// 一条埋点记录。
    ///
    /// - Note: `params` 使用 `[String: Any]` 而非 `Codable` 结构体，
    ///   因为不同事件的参数集合差异较大，使用字典更灵活。
    ///   导出时通过 `JSONSerialization` 序列化。
    struct AnalyticsRecord {
        /// 事件发生时间（epoch 毫秒，与 Android 跨平台兼容）
        let timestamp: Int64
        /// 事件名称（对应 `AnalyticsEvent.rawValue`）
        let name: String
        /// 附加参数，key 为参数名，value 为任意可 JSON 序列化的值
        let params: [String: Any]
    }

    // MARK: - 配置常量

    /// Ring buffer 最大容量 — 超出后丢弃最旧的事件。
    private let maxEvents = 1000

    /// `analyticsEnabled` 的 UserDefaults 存储键。
    private let analyticsEnabledKey = "analyticsEnabled"

    // MARK: - 可观察属性

    /// 是否启用本地埋点（默认 true）。
    ///
    /// 通过 `UserDefaults` 持久化（与 `@AppStorage` 等效）。
    /// `@Observable` 类无法直接使用 `@AppStorage`（后者依赖 SwiftUI View 生命周期），
    /// 因此使用 `didSet` + `UserDefaults` 实现自动持久化。
    ///
    /// - Note: 首次启动时通过 `register(defaults:)` 注册默认值 `true`。
    var analyticsEnabled: Bool {
        didSet {
            UserDefaults.standard.set(analyticsEnabled, forKey: analyticsEnabledKey)
        }
    }

    // MARK: - 内部状态（不参与 @Observable 追踪）

    /// Ring buffer — 使用 Array 实现环形缓冲。
    ///
    /// 标记 `@ObservationIgnored` 因为事件写入频繁（每次 logEvent），
    /// 不应触发 SwiftUI 视图重绘。UI 通过 `getEvents()` 获取快照。
    @ObservationIgnored
    private var ringBuffer: [AnalyticsRecord] = []

    /// Ring buffer 读写队列（concurrent + barrier 实现读者-写者锁）。
    @ObservationIgnored
    private let bufferQueue = DispatchQueue(
        label: "com.agentcontrolcenter.analytics.buffer",
        attributes: .concurrent
    )

    // MARK: - 初始化

    /// 初始化埋点管理器，从 UserDefaults 恢复开关状态。
    init() {
        // 注册默认值（首次启动时 analyticsEnabled 为 true）
        UserDefaults.standard.register(defaults: [analyticsEnabledKey: true])
        self.analyticsEnabled = UserDefaults.standard.bool(forKey: analyticsEnabledKey)
    }

    // MARK: - 公开接口

    /// 记录一条埋点事件。
    ///
    /// 当埋点开关关闭时（`analyticsEnabled == false`）直接返回，不写入 ring buffer。
    /// 写入操作通过 barrier 同步，超过 `maxEvents` 时丢弃最旧的事件。
    ///
    /// - Parameters:
    ///   - name: 事件名称，建议使用 `AnalyticsEvent.rawValue` 以保证一致性
    ///   - params: 附加参数，默认为空。value 可为 String / Int / Double / Bool 等可 JSON 序列化类型
    func logEvent(name: String, params: [String: Any] = [:]) {
        guard analyticsEnabled else { return }

        let record = AnalyticsRecord(
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            name: name,
            params: params
        )

        bufferQueue.async(flags: .barrier) {
            if self.ringBuffer.count >= self.maxEvents {
                self.ringBuffer.removeFirst()
            }
            self.ringBuffer.append(record)
        }
    }

    /// 记录页面浏览事件的便捷方法。
    ///
    /// 等价于 `logEvent(name: .screenView, params: ["screen_name": screenName])`。
    ///
    /// - Parameter screenName: 页面名称（如 "SettingsView" / "ChatView"）
    func logScreenView(_ screenName: String) {
        logEvent(name: AnalyticsEvent.screenView.rawValue, params: ["screen_name": screenName])
    }

    /// 返回 ring buffer 中所有事件的快照副本。
    ///
    /// 返回的是新数组，调用方修改不影响内部 ring buffer。
    /// 顺序为时间正序（最旧到最新）。
    ///
    /// - Returns: 事件列表的不可变快照
    ///
    /// - Note F29 修复：原实现使用 `bufferQueue.sync { ringBuffer }`，由于本类标记
    ///   `@MainActor`，`getEvents()` 默认在主线程被调用；`bufferQueue` 是 concurrent
    ///   队列，`sync` 不会死锁但会阻塞主线程等待 barrier 写入完成（高频 `logEvent`
    ///   时可能造成 UI 卡顿）。改为 `async`，内部用 `bufferQueue.async` 异步读取，
    ///   通过 `withCheckedContinuation` 把结果回传给调用协程。
    func getEvents() async -> [AnalyticsRecord] {
        await withCheckedContinuation { (continuation: CheckedContinuation<[AnalyticsRecord], Never>) in
            bufferQueue.async {
                continuation.resume(returning: self.ringBuffer)
            }
        }
    }

    /// 导出所有事件为 JSON 字符串。
    ///
    /// JSON 格式（数组，每个元素为一条事件）：
    /// ```json
    /// [
    ///   {
    ///     "timestamp": 1700000000000,
    ///     "name": "screen_view",
    ///     "params": { "screen_name": "SettingsView" }
    ///   }
    /// ]
    /// ```
    ///
    /// 导出后不自动清空 ring buffer，如需清空请显式调用 `clearEvents()`。
    ///
    /// - Returns: JSON 格式的事件列表字符串；序列化失败时返回 `"[]"`
    ///
    /// - Note F29：随 `getEvents()` 一并改为 `async`，避免在 `@MainActor` 上下文中
    ///   阻塞主线程。
    func exportEvents() async -> String {
        let events = await getEvents()
        let eventsArray: [[String: Any]] = events.map { event in
            [
                "timestamp": event.timestamp,
                "name": event.name,
                "params": event.params
            ]
        }

        guard let data = try? JSONSerialization.data(
            withJSONObject: eventsArray,
            options: [.prettyPrinted, .sortedKeys]
        ),
        let json = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return json
    }

    /// 清空 ring buffer 中的所有事件。
    ///
    /// 通常在用户导出事件后调用，或用于隐私清除。
    func clearEvents() {
        bufferQueue.async(flags: .barrier) {
            self.ringBuffer.removeAll()
        }
    }
}
