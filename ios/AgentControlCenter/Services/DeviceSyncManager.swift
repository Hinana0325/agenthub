import Foundation
import Observation

/// 设备同步管理器 — P2P 设备发现与数据同步
/// 对应 Android DeviceSyncManager
///
/// 基于 MultipeerConnectivity 框架实现局域网内设备发现与数据传输。
///
/// 重要说明：
/// - MultipeerConnectivity 仅在真机上运行，iOS 模拟器不支持。
/// - 实际的设备广播、发现、连接和数据传输逻辑需要集成 MCSession /
///   MCAdvertiserAssistant / MCBrowserViewController，此模块提供数据层框架。
/// - 数据导出/导入使用 JSON 序列化，结构兼容 Android 端的 Room 导出格式。
@MainActor
@Observable
final class DeviceSyncManager {

    // MARK: - 数据模型

    /// 已发现的同步设备
    struct SyncDevice: Identifiable, Codable {
        /// 设备唯一标识（MultipeerConnectivity MCPeerID.displayName 或自定义 ID）
        let id: String
        /// 设备显示名称
        let deviceName: String
        /// 上次同步时间（nil 表示尚未同步）
        var lastSyncTime: Date?
        /// 是否在线
        var isOnline: Bool
    }

    /// 同步状态
    enum SyncStatus: Equatable {
        case idle               // 空闲，未进行任何操作
        case scanning            // 正在扫描附近设备
        case syncing            // 正在与设备同步数据
        case error(String)      // 同步过程中发生错误

        // Equatable 仅比较 case（忽略关联值），用于 UI 判断状态类型
        static func == (lhs: SyncStatus, rhs: SyncStatus) -> Bool {
            switch (lhs, rhs) {
            case (.idle, .idle), (.scanning, .scanning),
                 (.syncing, .syncing):
                return true
            case (.error, .error):
                return true
            default:
                return false
            }
        }
    }

    // MARK: - 属性

    /// 已发现的设备列表
    var discoveredDevices: [SyncDevice] = []

    /// 当前同步状态
    var syncStatus: SyncStatus = .idle

    /// 已发现的 MultipeerConnectivity peer 缓存（peerID -> SyncDevice 映射）
    private var peerDeviceMap: [String: SyncDevice] = [:]

    // MARK: - 设备扫描

    /// 开始扫描附近的可同步设备。
    ///
    /// 使用 MultipeerConnectivity 的 MCAdvertiserAssistant 进行广播，
    /// 同时通过 MCBrowserViewController 或 MCSession delegate 发现对端设备。
    ///
    /// 注意：此方法仅更新状态和设备列表，实际的 MultipeerConnectivity 初始化
    /// 需要在真机上通过 MCSession + MCAdvertiserAssistant 完成。
    ///
    /// 模拟器环境：
    /// - MultipeerConnectivity 在模拟器上无法正常工作
    /// - 此方法在模拟器上仅设置状态为 .scanning，不会发现任何设备
    /// - 开发调试时可注入模拟设备进行 UI 测试
    func startScanning() {
        guard syncStatus == .idle else { return }

        syncStatus = .scanning
        discoveredDevices = []
        peerDeviceMap = [:]

        // TODO: 实际 MultipeerConnectivity 集成步骤：
        // 1. 创建 MCPeerID（使用设备名称）
        // 2. 创建 MCSession 并设置 delegate
        // 3. 创建 MCAdvertiserAssistant（广播自身）
        // 4. 创建 MCBrowserViewController 或使用 MCBrowserViewController.programmaticDiscovery
        //    进行设备搜索
        // 5. 在 MCSessionDelegate.browser(_:foundPeer:) 回调中添加到 discoveredDevices
        // 6. 在 MCSessionDelegate.browser(_:lostPeer:) 回调中标记为离线
        //
        // 示例代码结构（真机）:
        //
        // let peerID = MCPeerID(displayName: UIDevice.current.name)
        // let session = MCSession(peer: peerID, securityIdentity: nil, encryptionPreference: .none)
        // session.delegate = self
        // let advertiser = MCAdvertiserAssistant(serviceType: "acc-sync", discoveryInfo: nil, session: session)
        // advertiser.start()
        // let browser = MCBrowserViewController(serviceType: "acc-sync", session: session)
        // browser.delegate = self

        // 模拟器兼容：延迟后自动停止扫描
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            if self?.syncStatus == .scanning {
                self?.syncStatus = .idle
            }
        }
    }

    /// 停止扫描
    func stopScanning() {
        guard syncStatus == .scanning else { return }

        // TODO: 停止 MCAdvertiserAssistant
        // TODO: 断开所有未建立的 MCSession 连接

        syncStatus = .idle
        discoveredDevices = []
        peerDeviceMap = [:]
    }

    // MARK: - 数据同步

    /// 与指定设备发起数据同步。
    ///
    /// 流程：
    /// 1. 导出本地数据为 JSON
    /// 2. 通过 MultipeerConnectivity MCSession.sendResource(at:withName:) 发送
    /// 3. 对端接收后调用 importData 进行数据导入
    ///
    /// - Parameter device: 目标同步设备
    func syncWithDevice(_ device: SyncDevice) {
        guard syncStatus == .idle || syncStatus == .scanning else { return }
        guard device.isOnline else {
            syncStatus = .error("设备 \(device.deviceName) 不在线")
            return
        }

        syncStatus = .syncing

        // 异步导出并通过 MCSession 发送
        // F30 修复：补 [weak self]，避免 DeviceSyncManager 在同步进行中被释放后仍被 Task 强引用
        Task { [weak self] in
            guard let self else { return }
            do {
                let data = try await self.getExportData()

                // TODO: 通过 MCSession 发送数据到目标设备
                // session.sendResource(at: tempFileURL, withName: "acc-sync-\(Date().timeIntervalSince1970)", toPeer: targetPeerID)

                // 更新设备同步时间
                if let index = self.discoveredDevices.firstIndex(where: { $0.id == device.id }) {
                    self.discoveredDevices[index].lastSyncTime = Date()
                }
                self.peerDeviceMap[device.id]?.lastSyncTime = Date()

                self.syncStatus = .idle
            } catch {
                self.syncStatus = .error("数据导出失败: \(error.localizedDescription)")
            }
        }
    }

    /// 导出所有本地数据为 JSON。
    ///
    /// 将 Session、Message、AgentConfig 序列化为 JSON 格式，
    /// 结构与 Android 端 Room 导出格式兼容，便于跨平台数据迁移。
    ///
    /// - Returns: JSON 格式的序列化数据
    /// - Throws: 序列化过程中的错误
    func getExportData() async throws -> Data {
        // 构建导出数据结构
        let exportPayload = SyncExportPayload(
            version: "1.0",
            // SW-M4: 使用现代 .iso8601 FormatStyle 替代 ISO8601DateFormatter
            exportedAt: Date().formatted(.iso8601),
            // TODO: 从 DataController 获取实际数据
            // 需要在调用方注入 DataController 实例
            sessions: [],
            messages: [],
            agentConfigs: []
        )

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(exportPayload)
    }

    /// 导入接收到的同步数据。
    ///
    /// 反序列化 JSON 后，逐条 upsert 到 DataController 中。
    /// 采用「新数据覆盖旧数据」策略：相同 ID 的记录用远端数据覆盖。
    ///
    /// - Parameter data: JSON 格式的同步数据
    /// - Parameter dataController: 数据控制器实例（用于持久化）
    func importData(_ data: Data, to dataController: DataController) {
        syncStatus = .syncing

        do {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            let payload = try decoder.decode(SyncExportPayload.self, from: data)

            // 导入会话
            for session in payload.sessions {
                dataController.saveSession(session)
            }

            // 导入消息
            for message in payload.messages {
                dataController.saveMessage(message)
            }

            // 导入 Agent 配置
            for config in payload.agentConfigs {
                dataController.saveAgentConfig(config)
            }

            syncStatus = .idle
        } catch {
            syncStatus = .error("数据导入失败: \(error.localizedDescription)")
        }
    }

    // MARK: - 辅助方法

    /// 处理发现的 MultipeerConnectivity peer（供 MCSessionDelegate 调用）。
    ///
    /// 在 MCSessionDelegate.browser(_:foundPeer:) 中调用此方法，
    /// 将 MCPeerID 转换为 SyncDevice 并添加到发现列表。
    ///
    /// - Parameters:
    ///   - peerName: MCPeerID.displayName
    ///   - peerID: MCPeerID 的完整字符串标识
    func handleDiscoveredPeer(peerName: String, peerID: String) {
        let device = SyncDevice(
            id: peerID,
            deviceName: peerName,
            lastSyncTime: nil,
            isOnline: true
        )
        peerDeviceMap[peerID] = device
        discoveredDevices = Array(peerDeviceMap.values)
    }

    /// 处理丢失的 MultipeerConnectivity peer（供 MCSessionDelegate 调用）。
    ///
    /// 在 MCSessionDelegate.browser(_:lostPeer:) 中调用此方法，
    /// 将对应设备标记为离线。
    ///
    /// - Parameter peerID: MCPeerID 的完整字符串标识
    func handleLostPeer(peerID: String) {
        peerDeviceMap[peerID]?.isOnline = false
        // 从发现列表中移除离线设备
        peerDeviceMap.removeValue(forKey: peerID)
        discoveredDevices = Array(peerDeviceMap.values)
    }
}

// MARK: - 同步导出数据结构

/// P2P 同步导出的数据负载。
///
/// JSON 结构兼容 Android 端的 Room 数据导出格式，
/// 通过 version 字段支持未来格式迁移。
///
/// 注意：此结构仅用于设备间同步，不直接持久化到 SwiftData。
struct SyncExportPayload: Codable {
    /// 数据格式版本（用于向前兼容）
    let version: String
    /// 导出时间（ISO 8601 格式）
    let exportedAt: String
    /// 导出的会话列表
    let sessions: [Session]
    /// 导出的消息列表
    let messages: [Message]
    /// 导出的 Agent 配置列表
    let agentConfigs: [AgentConfig]
}
