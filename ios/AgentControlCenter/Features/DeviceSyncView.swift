import SwiftUI
import UIKit

// MARK: - SyncHistoryEntry

/// 同步历史记录条目
struct SyncHistoryEntry: Identifiable, Codable {
    let id: UUID
    let deviceName: String
    let timestamp: Date
    let result: SyncResult

    enum SyncResult: String, Codable {
        case success
        case failed
        case partial

        var displayName: String {
            switch self {
            case .success: return "成功"
            case .failed:  return "失败"
            case .partial: return "部分成功"
            }
        }

        var systemImage: String {
            switch self {
            case .success: return "checkmark.circle.fill"
            case .failed:  return "xmark.circle.fill"
            case .partial: return "exclamationmark.circle.fill"
            }
        }
    }

    init(deviceName: String, result: SyncResult, timestamp: Date = Date()) {
        self.id = UUID()
        self.deviceName = deviceName
        self.timestamp = timestamp
        self.result = result
    }
}

// MARK: - DeviceSyncView

/// 设备同步页面 — 对应 Android DeviceSyncScreen。
///
/// 作为 `DeviceSyncManager` 的 UI 层，展示：
/// - 当前设备信息（名称、型号、系统版本）
/// - 已连接设备列表与在线状态
/// - 同步状态指示器（空闲 / 扫描中 / 同步中 / 错误）
/// - 手动同步按钮与自动同步开关
/// - 最后同步时间
/// - 同步历史记录
/// - 设备配对二维码（占位 UI）
///
/// 重要约束：
/// 本视图不重复 DeviceSyncManager 的数据层逻辑，仅负责呈现和交互。
/// 自动同步开关、同步历史等 UI 专属状态通过 @AppStorage / UserDefaults 管理。
struct DeviceSyncView: View {
    @Environment(AppState.self) private var appState

    // MARK: - UI 状态

    /// 自动同步开关（持久化到 UserDefaults）
    @AppStorage("deviceSyncAutoSync") private var autoSyncEnabled: Bool = false

    /// 同步历史记录（持久化到 UserDefaults）
    @State private var syncHistory: [SyncHistoryEntry] = []

    /// 最后同步时间（持久化到 UserDefaults）
    @State private var lastSyncTime: Date?

    /// 是否显示设备发现中的进度提示
    @State private var isDiscovering: Bool = false

    /// 当前显示的二维码占位
    @State private var showQRCode: Bool = false

    /// 错误提示
    @State private var errorMessage: String?

    // MARK: - Body

    var body: some View {
        ScrollView {
            VStack(spacing: AppTheme.Spacing.lg) {
                // 0. Development banner
                devFeatureBanner

                // 1. 当前设备信息
                currentDeviceSection

                // 2. 同步状态与控制
                syncControlSection

                // 3. 已连接设备列表
                connectedDevicesSection

                // 4. 同步历史
                syncHistorySection

                // 5. 设备配对二维码
                qrCodeSection
            }
            .padding(AppTheme.Spacing.lg)
        }
        .navigationTitle("设备同步")
        // SW-M2: 使用 .task 替代 .onAppear，由 SwiftUI 管理任务生命周期
        .task {
            loadSyncHistory()
            loadLastSyncTime()
        }
        .alert("同步错误",
               isPresented: Binding(
                   get: { errorMessage != nil },
                   set: { if !$0 { errorMessage = nil } }
               ),
               presenting: errorMessage
        ) { _ in
            Button("好的", role: .cancel) { errorMessage = nil }
        } message: { msg in
            Text(msg)
        }
    }

    // MARK: - 开发中横幅

    /// 设备同步功能开发中提示横幅
    private var devFeatureBanner: some View {
        HStack(spacing: AppTheme.Spacing.md) {
            Image(systemName: "hammer.circle.fill")
                .font(.title2)
                .foregroundStyle(.orange)

            VStack(alignment: .leading, spacing: AppTheme.Spacing.xs) {
                Text("功能开发中")
                    .font(.subheadline)
                    .fontWeight(.semibold)

                Text("设备同步功能正在开发中，设备发现和同步可能尚不可用。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(AppTheme.Spacing.md)
        .background(Color.orange.opacity(0.1), in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    // MARK: - 当前设备信息

    /// 当前设备信息卡片：设备名、型号、系统版本
    private var currentDeviceSection: some View {
        VStack(alignment: .leading, spacing: AppTheme.Spacing.md) {
            sectionHeader("当前设备", icon: "iphone")

            VStack(spacing: AppTheme.Spacing.sm) {
                infoRow(title: "设备名称", value: UIDevice.current.name)
                infoRow(title: "设备型号", value: deviceModelName)
                infoRow(title: "系统版本", value: "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)")
            }
        }
        .appCard()
    }

    /// 设备型号显示名称（将原始标识符转为可读名称）
    private var deviceModelName: String {
        let model = UIDevice.current.model
        // UIDevice.current.model 返回 "iPhone" / "iPad" 等
        // 使用 localizedModel 获取更友好的名称
        return UIDevice.current.localizedModel.isEmpty ? model : UIDevice.current.localizedModel
    }

    // MARK: - 同步控制

    /// 同步状态与控制区：状态指示器 + 自动同步开关 + 手动同步按钮 + 最后同步时间
    private var syncControlSection: some View {
        VStack(alignment: .leading, spacing: AppTheme.Spacing.md) {
            sectionHeader("同步状态", icon: "arrow.triangle.2.circlepath")

            // 同步状态指示器
            syncStatusIndicator

            Divider()

            // 自动同步开关
            Toggle(isOn: $autoSyncEnabled) {
                Label("自动同步", systemImage: "clock.arrow.circlepath")
                    .font(.subheadline)
            }
            .tint(AppTheme.primaryColor)

            // 最后同步时间
            HStack {
                Image(systemName: "clock")
                    .foregroundStyle(.secondary)
                Text("最后同步")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(lastSyncTimeFormatted)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }

            // 手动同步按钮
            Button {
                startManualSync()
            } label: {
                HStack {
                    if isSyncing {
                        ProgressView()
                            .scaleEffect(0.8)
                            .tint(.white)
                    } else {
                        Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                    }
                    Text(isSyncing ? "同步中…" : "立即同步")
                        .fontWeight(.medium)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, AppTheme.Spacing.sm)
                .background(
                    isSyncing ? Color.gray.opacity(0.5) : AppTheme.primaryColor,
                    in: RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md)
                )
                .foregroundStyle(.white)
            }
            .disabled(isSyncing)
        }
        .appCard()
    }

    /// 同步状态指示器：根据 DeviceSyncManager.syncStatus 显示对应颜色与文字
    private var syncStatusIndicator: some View {
        HStack(spacing: AppTheme.Spacing.sm) {
            // 状态圆点
            Circle()
                .fill(syncStatusColor)
                .frame(width: 10, height: 10)
                .overlay(
                    Circle()
                        .stroke(syncStatusColor.opacity(0.3), lineWidth: 3)
                        .scaleEffect(isSyncing ? 1.4 : 1.0)
                        .opacity(isSyncing ? 0 : 1)
                        .animation(
                            .easeInOut(duration: 1).repeatForever(autoreverses: false),
                            value: isSyncing
                        )
                )

            Text(syncStatusText)
                .font(.subheadline)
                .foregroundStyle(syncStatusColor)

            Spacer()

            // 状态图标
            Image(systemName: syncStatusSystemImage)
                .foregroundStyle(syncStatusColor)
                .font(.title3)
        }
    }

    /// 是否正在同步中
    private var isSyncing: Bool {
        appState.deviceSyncManager.syncStatus == .syncing
    }

    /// 同步状态文本
    private var syncStatusText: String {
        switch appState.deviceSyncManager.syncStatus {
        case .idle:            return "空闲"
        case .scanning:        return "正在扫描设备…"
        case .syncing:         return "正在同步数据…"
        case .error(let msg):  return "错误: \(msg)"
        }
    }

    /// 同步状态颜色
    private var syncStatusColor: Color {
        switch appState.deviceSyncManager.syncStatus {
        case .idle:            return AppTheme.secondaryTextColor
        case .scanning:        return AppTheme.warningColor
        case .syncing:         return AppTheme.infoColor
        case .error:           return AppTheme.errorColor
        }
    }

    /// 同步状态图标
    private var syncStatusSystemImage: String {
        switch appState.deviceSyncManager.syncStatus {
        case .idle:            return "checkmark.circle"
        case .scanning:        return "antenna.radiowaves.left.and.right"
        case .syncing:         return "arrow.triangle.2.circlepath"
        case .error:           return "exclamationmark.triangle"
        }
    }

    /// 最后同步时间的格式化字符串
    private var lastSyncTimeFormatted: String {
        guard let time = lastSyncTime else { return "从未同步" }
        // SW-M4: 使用现代 FormatStyle 替代 DateFormatter（locale 自动从系统获取）
        return time.formatted(Date.FormatStyle()
            .year(.defaultDigits)
            .month(.twoDigits)
            .day(.twoDigits)
            .hour(.twoDigitsNoAMPM)
            .minute(.twoDigits)
            .locale(Locale(identifier: "zh_CN")))
    }

    // MARK: - 已连接设备列表

    /// 已连接设备列表区
    private var connectedDevicesSection: some View {
        VStack(alignment: .leading, spacing: AppTheme.Spacing.md) {
            HStack {
                sectionHeader("已连接设备", icon: "ipad.and.iphone")
                Spacer()
                Button {
                    startScanning()
                } label: {
                    Label("扫描", systemImage: "magnifyingglass")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .disabled(appState.deviceSyncManager.syncStatus == .scanning
                          || appState.deviceSyncManager.syncStatus == .syncing)
            }

            let devices = appState.deviceSyncManager.discoveredDevices

            if devices.isEmpty {
                // 空状态
                VStack(spacing: AppTheme.Spacing.sm) {
                    Image(systemName: "ipad.and.iphone.badge.exclamationmark")
                        .font(.system(size: 36))
                        .foregroundStyle(.secondary)
                    Text("暂无已连接设备")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("点击「扫描」搜索附近的可同步设备")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, AppTheme.Spacing.xl)
            } else {
                ForEach(devices) { device in
                    deviceRow(device)
                }
            }
        }
        .appCard()
    }

    /// 单个设备行
    private func deviceRow(_ device: DeviceSyncManager.SyncDevice) -> some View {
        HStack(spacing: AppTheme.Spacing.md) {
            // 设备图标 + 在线状态指示
            ZStack(alignment: .bottomTrailing) {
                Image(systemName: "iphone")
                    .font(.title2)
                    .foregroundStyle(AppTheme.primaryColor)
                    .frame(width: 40, height: 40)
                    .background(AppTheme.tertiaryBackground, in: Circle())

                Circle()
                    .fill(device.isOnline ? AppTheme.successColor : AppTheme.secondaryTextColor)
                    .frame(width: 12, height: 12)
                    .overlay(Circle().stroke(Color.white, lineWidth: 2))
            }

            VStack(alignment: .leading, spacing: AppTheme.Spacing.xs) {
                Text(device.deviceName)
                    .font(.subheadline)
                    .fontWeight(.medium)
                HStack(spacing: AppTheme.Spacing.xs) {
                    Text(device.isOnline ? "在线" : "离线")
                        .font(.caption)
                        .foregroundStyle(device.isOnline ? AppTheme.successColor : AppTheme.secondaryTextColor)
                    if let lastSync = device.lastSyncTime {
                        Text("·")
                            .foregroundStyle(.secondary)
                        Text("上次同步: \(formatDate(lastSync))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Spacer()

            // 同步按钮
            Button {
                syncWithDevice(device)
            } label: {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.body)
                    .foregroundStyle(AppTheme.primaryColor)
            }
            .buttonStyle(.borderless)
            .disabled(!device.isOnline || isSyncing)
            .accessibilityLabel("与该设备同步")
        }
        .padding(.vertical, AppTheme.Spacing.xs)
    }

    // MARK: - 同步历史

    /// 同步历史记录列表
    private var syncHistorySection: some View {
        VStack(alignment: .leading, spacing: AppTheme.Spacing.md) {
            HStack {
                sectionHeader("同步历史", icon: "clock.arrow.circlepath")
                Spacer()
                if !syncHistory.isEmpty {
                    Button {
                        clearSyncHistory()
                    } label: {
                        Text("清空")
                            .font(.caption)
                            .foregroundStyle(AppTheme.errorColor)
                    }
                    .buttonStyle(.borderless)
                }
            }

            if syncHistory.isEmpty {
                Text("暂无同步记录")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, AppTheme.Spacing.md)
            } else {
                ForEach(syncHistory) { entry in
                    syncHistoryRow(entry)
                }
            }
        }
        .appCard()
    }

    /// 单条同步历史记录
    private func syncHistoryRow(_ entry: SyncHistoryEntry) -> some View {
        HStack(spacing: AppTheme.Spacing.sm) {
            Image(systemName: entry.result.systemImage)
                .foregroundStyle(syncResultColor(entry.result))
                .font(.body)

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.deviceName)
                    .font(.caption)
                    .fontWeight(.medium)
                Text(formatDate(entry.timestamp))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Text(entry.result.displayName)
                .font(.caption2)
                .foregroundStyle(syncResultColor(entry.result))
                .appPill(
                    foreground: syncResultColor(entry.result),
                    background: syncResultColor(entry.result).opacity(0.1)
                )
        }
        .padding(.vertical, AppTheme.Spacing.xs)
    }

    /// 同步结果对应的颜色
    private func syncResultColor(_ result: SyncHistoryEntry.SyncResult) -> Color {
        switch result {
        case .success: return AppTheme.successColor
        case .failed:  return AppTheme.errorColor
        case .partial: return AppTheme.warningColor
        }
    }

    // MARK: - 二维码配对

    /// 设备配对二维码占位区
    private var qrCodeSection: some View {
        VStack(alignment: .leading, spacing: AppTheme.Spacing.md) {
            sectionHeader("设备配对", icon: "qrcode")

            VStack(spacing: AppTheme.Spacing.md) {
                // 二维码占位
                ZStack {
                    RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md)
                        .fill(AppTheme.tertiaryBackground)
                        .frame(width: 180, height: 180)

                    if showQRCode {
                        // 占位二维码图案（用 SF Symbol 模拟）
                        Image(systemName: "qrcode")
                            .font(.system(size: 80))
                            .foregroundStyle(AppTheme.primaryColor)
                    } else {
                        VStack(spacing: AppTheme.Spacing.sm) {
                            Image(systemName: "qrcode.viewfinder")
                                .font(.system(size: 48))
                                .foregroundStyle(.secondary)
                            Text("点击生成配对码")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.3)) {
                        showQRCode.toggle()
                    }
                }

                Text("扫描二维码即可与其他设备配对同步")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Text("配对后，两台设备可在局域网内互相同步会话、消息和配置数据")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
        }
        .appCard()
    }

    // MARK: - 通用组件

    /// 区块标题
    private func sectionHeader(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .font(.headline)
            .foregroundStyle(.primary)
    }

    /// 信息行（标题 + 值）
    private func infoRow(title: String, value: String) -> some View {
        HStack {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.subheadline)
                .fontWeight(.medium)
                .foregroundStyle(.primary)
        }
    }

    // MARK: - 操作

    /// 开始扫描附近设备
    private func startScanning() {
        appState.deviceSyncManager.startScanning()
        isDiscovering = true

        // 扫描结束后重置发现状态
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
            isDiscovering = false
        }
    }

    /// 手动同步全部已连接设备
    private func startManualSync() {
        let devices = appState.deviceSyncManager.discoveredDevices.filter { $0.isOnline }

        if devices.isEmpty {
            errorMessage = "没有可同步的在线设备，请先扫描并连接设备。"
            return
        }

        // 依次同步所有在线设备
        for device in devices {
            syncWithDevice(device)
        }
    }

    /// 与指定设备同步
    private func syncWithDevice(_ device: DeviceSyncManager.SyncDevice) {
        appState.deviceSyncManager.syncWithDevice(device)

        // 延迟检查同步结果并记录历史
        let deviceName = device.deviceName
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            let result: SyncHistoryEntry.SyncResult
            switch appState.deviceSyncManager.syncStatus {
            case .error:
                result = .failed
            case .idle:
                result = .success
            default:
                result = .success
            }

            let entry = SyncHistoryEntry(deviceName: deviceName, result: result)
            syncHistory.insert(entry, at: 0)
            if syncHistory.count > 50 {
                syncHistory.removeLast()
            }
            saveSyncHistory()

            if result == .success {
                lastSyncTime = Date()
                saveLastSyncTime()
            }
        }
    }

    /// 清空同步历史
    private func clearSyncHistory() {
        syncHistory.removeAll()
        saveSyncHistory()
    }

    // MARK: - 持久化（UserDefaults）

    /// 加载同步历史
    private func loadSyncHistory() {
        guard let data = UserDefaults.standard.data(forKey: "deviceSyncHistory") else { return }
        let decoder = JSONDecoder()
        syncHistory = (try? decoder.decode([SyncHistoryEntry].self, from: data)) ?? []
    }

    /// 保存同步历史
    private func saveSyncHistory() {
        let encoder = JSONEncoder()
        guard let data = try? encoder.encode(syncHistory) else { return }
        UserDefaults.standard.set(data, forKey: "deviceSyncHistory")
    }

    /// 加载最后同步时间
    private func loadLastSyncTime() {
        if let timestamp = UserDefaults.standard.object(forKey: "deviceSyncLastSyncTime") as? Double {
            lastSyncTime = Date(timeIntervalSince1970: timestamp)
        }
    }

    /// 保存最后同步时间
    private func saveLastSyncTime() {
        if let time = lastSyncTime {
            UserDefaults.standard.set(time.timeIntervalSince1970, forKey: "deviceSyncLastSyncTime")
        }
    }

    // MARK: - 日期格式化

    /// 格式化日期为本地化字符串
    private func formatDate(_ date: Date) -> String {
        // SW-M4: 使用现代 FormatStyle 替代 DateFormatter
        return date.formatted(Date.FormatStyle()
            .month(.twoDigits)
            .day(.twoDigits)
            .hour(.twoDigitsNoAMPM)
            .minute(.twoDigits)
            .locale(Locale(identifier: "zh_CN")))
    }
}
