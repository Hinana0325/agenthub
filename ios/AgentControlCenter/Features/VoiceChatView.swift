import SwiftUI

// MARK: - VoiceChatView
//
// 全屏语音录制界面，对应 Android VoiceChatScreen。
//
// 功能概览：
// 1. 大圆形录制按钮（带脉冲动画）
// 2. 实时音量波形可视化
// 3. 录音时长计时
// 4. 取消/发送按钮
// 5. 已录制消息列表（点击播放）
//
// 与 ChatView 中的语音输入按钮互补：
// - ChatView 中的 VoiceInputManager：用于将语音转文字（SFSpeechRecognizer）
// - VoiceChatView：用于录制语音消息并回放（AVAudioRecorder / AVAudioPlayer）

/// 语音聊天视图 — 录制语音消息并播放
struct VoiceChatView: View {
    @Environment(AppState.self) private var appState

    /// 已录制的语音消息列表
    @State private var messages: [VoiceMessage] = []

    /// 录音开始时间（用于计算时长）
    @State private var recordingStartedAt: Date?

    /// 录音时长定时器
    @State private var durationTimer: Timer?

    /// 当前显示的录音时长（秒）
    @State private var currentDuration: TimeInterval = 0

    /// 是否显示取消提示
    @State private var showingCancelHint: Bool = false

    /// 错误提示
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // MARK: 顶部：标题与状态
                headerView

                // MARK: 中部：录制区（波形 + 大按钮）
                recordingArea

                // MARK: 底部：操作按钮（取消 / 发送）
                actionButtons

                Divider()
                    .padding(.top, AppTheme.Spacing.lg)

                // MARK: 已录制消息列表
                messagesList
            }
            .background(AppTheme.backgroundColor)
            .navigationTitle("语音消息")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(role: .destructive) {
                        clearAllMessages()
                    } label: {
                        Image(systemName: "trash")
                            .accessibilityLabel("清空")
                    }
                    .disabled(messages.isEmpty)
                }
            }
            .alert(String(localized: "common.notice"), isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button(String(localized: "common.ok"), role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
            .onDisappear {
                durationTimer?.invalidate()
                appState.voiceChatManager.stopAll()
            }
        }
    }

    // MARK: - 子视图

    /// 顶部状态栏
    private var headerView: some View {
        VStack(spacing: AppTheme.Spacing.xs) {
            Text(appState.voiceChatManager.isRecording ? "正在录音…" : "准备就绪")
                .font(.headline)
                .foregroundStyle(appState.voiceChatManager.isRecording ? AppTheme.errorColor : AppTheme.secondaryTextColor)
                .animation(.easeInOut(duration: 0.2), value: appState.voiceChatManager.isRecording)

            Text(formatDuration(currentDuration))
                .font(.system(size: 36, weight: .light, design: .monospaced))
                .monospacedDigit()
                .foregroundStyle(AppTheme.primaryTextColor)

            if let err = appState.voiceChatManager.errorMessage {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(AppTheme.errorColor)
                    .padding(.horizontal, AppTheme.Spacing.lg)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, AppTheme.Spacing.xl)
    }

    /// 录制区域：波形 + 大圆形按钮
    private var recordingArea: some View {
        VStack(spacing: AppTheme.Spacing.xl) {
            // 波形可视化
            waveformView
                .frame(height: 80)
                .padding(.horizontal, AppTheme.Spacing.lg)

            // 大圆形录制按钮
            recordButton
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, AppTheme.Spacing.xl)
    }

    /// 波形可视化
    private var waveformView: some View {
        GeometryReader { proxy in
            let levels = appState.voiceChatManager.waveformLevels
            let barWidth: CGFloat = 4
            let spacing: CGFloat = 3
            let count = max(levels.count, 1)
            let totalWidth = CGFloat(count) * (barWidth + spacing)
            HStack(spacing: spacing) {
                ForEach(0..<count, id: \.self) { index in
                    let level = levels[index]
                    let height = max(4, CGFloat(level) * proxy.size.height)
                    RoundedRectangle(cornerRadius: barWidth / 2)
                        .fill(barColor(for: level))
                        .frame(width: barWidth, height: height)
                }
            }
            .frame(width: totalWidth, height: proxy.size.height)
            .frame(maxWidth: .infinity)
            .animation(.easeOut(duration: 0.05), value: levels)
        }
    }

    /// 大圆形录制按钮（带脉冲动画 + 液态玻璃）
    private var recordButton: some View {
        Button {
            if appState.voiceChatManager.isRecording {
                stopRecording()
            } else {
                startRecording()
            }
        } label: {
            ZStack {
                // 脉冲光环（录音中显示）
                if appState.voiceChatManager.isRecording {
                    Circle()
                        .fill(AppTheme.errorColor.opacity(0.2))
                        .frame(width: 100, height: 100)
                        .scaleEffect(pulseScale)
                        .opacity(pulseOpacity)
                        .animation(
                            .easeInOut(duration: 1.0).repeatForever(autoreverses: true),
                            value: appState.voiceChatManager.isRecording
                        )
                }

                // 主按钮底色（液态玻璃 + 状态色 tint）
                // HIG (iOS 26 Liquid Glass)：不透明 Circle 会遮挡玻璃采样，
                // 改为对 Button 整体应用 glassEffect(.tint(stateColor))，
                // 玻璃会保留 lensing 效果并叠加状态色
                // R4: 改用 glassTinted 包装，iOS 18 走 ultraThinMaterial + tint 色块回退
                Circle()
                    .fill(Color.clear)
                    .frame(width: 80, height: 80)
                    .glassTinted(
                        appState.voiceChatManager.isRecording ? AppTheme.errorColor : AppTheme.primaryColor,
                        in: GlassTokens.circleShape
                    )

                // 内部图标
                Image(systemName: appState.voiceChatManager.isRecording ? "stop.fill" : "mic.fill")
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .frame(width: 120, height: 120)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(appState.voiceChatManager.isRecording ? "停止录音" : "开始录音")
    }

    /// 操作按钮：取消 / 发送
    @ViewBuilder
    private var actionButtons: some View {
        if appState.voiceChatManager.isRecording {
            HStack(spacing: AppTheme.Spacing.lg) {
                // 取消
                Button {
                    cancelRecording()
                } label: {
                    Label("取消", systemImage: "xmark.circle.fill")
                        .labelStyle(.titleAndIcon)
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppTheme.Spacing.md)
                        .background(AppTheme.tertiaryBackground)
                        .foregroundStyle(AppTheme.secondaryTextColor)
                        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
                }
                .buttonStyle(.plain)

                // 发送（保存为消息）
                Button {
                    sendRecording()
                } label: {
                    Label("发送", systemImage: "arrow.up.circle.fill")
                        .labelStyle(.titleAndIcon)
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppTheme.Spacing.md)
                        .background(AppTheme.primaryColor)
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
                }
                .buttonStyle(.plain)
                .disabled(currentDuration < 1.0)
            }
            .padding(.horizontal, AppTheme.Spacing.lg)
            .transition(.opacity.combined(with: .move(edge: .bottom)))
        } else {
            HStack {
                Text("点击麦克风开始录音，至少录制 1 秒后可发送")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, AppTheme.Spacing.lg)
            .frame(maxWidth: .infinity)
            .transition(.opacity)
        }
    }

    /// 已录制消息列表
    private var messagesList: some View {
        ScrollView {
            LazyVStack(spacing: AppTheme.Spacing.sm) {
                if messages.isEmpty {
                    VStack(spacing: AppTheme.Spacing.sm) {
                        Image(systemName: "waveform.circle")
                            .font(.system(size: 48))
                            .foregroundStyle(.tertiary)
                        Text("暂无语音消息")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, AppTheme.Spacing.xxl)
                } else {
                    ForEach(messages) { message in
                        VoiceMessageRow(message: message)
                    }
                }
            }
            .padding(AppTheme.Spacing.md)
        }
    }

    // MARK: - 状态

    /// 脉冲缩放
    private var pulseScale: CGFloat {
        appState.voiceChatManager.isRecording ? 1.4 : 1.0
    }

    /// 脉冲透明度
    private var pulseOpacity: Double {
        appState.voiceChatManager.isRecording ? 0.0 : 0.5
    }

    // MARK: - 操作

    /// 开始录音
    private func startRecording() {
        appState.voiceChatManager.startRecording()
        currentDuration = 0
        recordingStartedAt = Date()

        // 启动计时器
        durationTimer?.invalidate()
        durationTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { _ in
            if let start = recordingStartedAt {
                currentDuration = Date().timeIntervalSince(start)
            }
        }
    }

    /// 停止录音（保留文件，等待发送）
    private func stopRecording() {
        durationTimer?.invalidate()
        durationTimer = nil
        _ = appState.voiceChatManager.stopRecording()
    }

    /// 取消录音
    private func cancelRecording() {
        durationTimer?.invalidate()
        durationTimer = nil
        appState.voiceChatManager.cancelRecording()
        currentDuration = 0
    }

    /// 发送录音（保存为语音消息）
    private func sendRecording() {
        durationTimer?.invalidate()
        durationTimer = nil
        guard let url = appState.voiceChatManager.stopRecording(),
              FileManager.default.fileExists(atPath: url.path) else {
            errorMessage = String(localized: "error.recording.invalid")
            currentDuration = 0
            return
        }

        let message = VoiceMessage(
            id: UUID().uuidString,
            url: url,
            duration: currentDuration,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            waveform: appState.voiceChatManager.waveformLevels
        )
        messages.insert(message, at: 0)
        currentDuration = 0
    }

    /// 清空所有消息
    private func clearAllMessages() {
        // 删除物理文件
        for message in messages {
            try? FileManager.default.removeItem(at: message.url)
        }
        messages.removeAll()
        appState.voiceChatManager.stopPlayback()
    }

    // MARK: - 格式化

    /// 将秒数格式化为 mm:ss
    /// - Parameter duration: 时长（秒）
    /// - Returns: mm:ss 格式字符串
    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    /// 根据音量等级返回对应颜色
    /// - Parameter level: 0.0 ~ 1.0
    /// - Returns: 波形柱颜色
    private func barColor(for level: Float) -> Color {
        if appState.voiceChatManager.isRecording {
            if level > 0.7 { return AppTheme.errorColor }
            if level > 0.4 { return AppTheme.warningColor }
            return AppTheme.primaryColor
        } else {
            return AppTheme.tertiaryTextColor
        }
    }
}

// MARK: - VoiceMessageRow

/// 单条语音消息行
///
/// 展示：迷你波形 + 时长 + 播放按钮 + 进度
private struct VoiceMessageRow: View {
    let message: VoiceMessage

    @Environment(AppState.self) private var appState

    /// 是否正在播放当前消息
    private var isCurrentPlaying: Bool {
        appState.voiceChatManager.currentPlayingMessageId == message.id &&
        appState.voiceChatManager.isPlaying
    }

    /// 播放进度（0.0 ~ 1.0）
    private var progress: Double {
        guard message.duration > 0,
              appState.voiceChatManager.currentPlayingMessageId == message.id else {
            return 0
        }
        return appState.voiceChatManager.playbackProgress / message.duration
    }

    var body: some View {
        HStack(spacing: AppTheme.Spacing.md) {
            // 播放/暂停按钮
            Button {
                togglePlayback()
            } label: {
                Image(systemName: isCurrentPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(AppTheme.primaryColor)
                    .frame(width: 44, height: 44)  // HIG：触控目标 ≥ 44×44pt
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isCurrentPlaying ? "暂停" : "播放")

            // 迷你波形 + 时长 + 进度
            VStack(alignment: .leading, spacing: AppTheme.Spacing.xs) {
                miniWaveform

                HStack {
                    Text(formatDuration(message.duration))
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(timeAgo)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .padding(AppTheme.Spacing.md)
        .background(AppTheme.secondaryBackground)
        .clipShape(RoundedRectangle(cornerRadius: AppTheme.CornerRadius.md))
    }

    /// 迷你波形
    private var miniWaveform: some View {
        GeometryReader { proxy in
            let waveform = message.waveform.isEmpty ? defaultWaveform : message.waveform
            let barWidth: CGFloat = 2
            let spacing: CGFloat = 1.5
            HStack(spacing: spacing) {
                ForEach(0..<waveform.count, id: \.self) { index in
                    let level = waveform[index]
                    let height = max(2, CGFloat(level) * proxy.size.height)
                    RoundedRectangle(cornerRadius: barWidth / 2)
                        .fill(barColor(for: index, total: waveform.count))
                        .frame(width: barWidth, height: height)
                }
            }
            .frame(maxWidth: .infinity, height: proxy.size.height, alignment: .center)
        }
        .frame(height: 32)
    }

    /// 单个波形柱颜色：已播放部分使用主色，未播放使用次要色
    private func barColor(for index: Int, total: Int) -> Color {
        guard isCurrentPlaying else { return AppTheme.tertiaryTextColor }
        let threshold = Double(total) * progress
        return Double(index) < threshold ? AppTheme.primaryColor : AppTheme.tertiaryTextColor
    }

    /// 默认波形（消息未携带波形时）
    private var defaultWaveform: [Float] {
        Array(repeating: 0.3, count: 30)
    }

    /// 时间戳的相对描述
    private var timeAgo: String {
        AppTheme.timeAgo(message.timestamp)
    }

    /// 切换播放/暂停
    private func togglePlayback() {
        if isCurrentPlaying {
            appState.voiceChatManager.pausePlayback()
        } else {
            appState.voiceChatManager.playAudio(url: message.url, messageId: message.id)
        }
    }

    /// 格式化时长
    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}
