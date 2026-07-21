import Foundation
import Observation
import AVFoundation

// MARK: - VoiceChatManager
//
// 与 `VoiceInputManager`（基于 SFSpeechRecognizer 的语音转文字）不同，
// 本管理器专注于「录音 + 回放 + 波形可视化」，用于 VoiceChatView 录制语音消息：
// - 录音：使用 AVAudioRecorder 写入 m4a 文件
// - 回放：使用 AVAudioPlayer 播放已录制文件
// - 实时音量：通过 audioRecorder.averagePower(forChannel:) 计算 dB，归一化为 0~1 的 audioLevel
// - 波形可视化：定时采样 audioLevel 写入 levels 数组（最多保留 64 个采样点）

/// 语音聊天管理器 — 录音、回放与波形可视化
@MainActor
@Observable
final class VoiceChatManager {

    // MARK: - 状态

    /// 是否正在录音
    private(set) var isRecording: Bool = false

    /// 是否正在播放
    private(set) var isPlaying: Bool = false

    /// 当前录音音量（0.0 ~ 1.0，已归一化的线性值）
    @ObservationIgnored private(set) var audioLevel: Float = 0.0

    /// 当前播放进度（秒）
    private(set) var playbackProgress: TimeInterval = 0.0

    /// 当前录音时长（秒）
    private(set) var recordingDuration: TimeInterval = 0.0

    /// 当前播放文件总时长（秒）
    private(set) var playbackDuration: TimeInterval = 0.0

    /// 波形采样数据（用于绘制波形图，最多保留 64 个采样点）
    private(set) var waveformLevels: [Float] = []

    /// 录音文件 URL（最近一次录音）
    private(set) var lastRecordingURL: URL?

    /// 错误信息
    private(set) var errorMessage: String?

    /// 录音会话状态
    enum SessionState {
        case idle        // 空闲
        case recording   // 录音中
        case playing     // 播放中
        case paused      // 暂停
    }

    /// 当前会话状态
    var state: SessionState {
        if isRecording { return .recording }
        if isPlaying { return .playing }
        return .idle
    }

    /// 波形最大采样数
    private let maxWaveformSamples = 64

    // MARK: - 私有资源

    @ObservationIgnored private var audioRecorder: AVAudioRecorder?
    @ObservationIgnored private var audioPlayer: AVAudioPlayer?
    @ObservationIgnored private var recordingTimer: Timer?
    @ObservationIgnored private var playbackTimer: Timer?

    /// 当前正在播放的消息 ID（用于 UI 高亮）
    @ObservationIgnored private(set) var currentPlayingMessageId: String?

    // MARK: - 初始化
    //
    // HIG (Guideline 2.5.6)：
    // 不在 init() 中激活 AVAudioSession，避免应用启动时中断后台音频。
    // .playAndRecord session 仅在用户实际触发录音/播放时才激活。

    init() {
        // 不调用 configureAudioSession() —— 延迟到 startRecording()/playRecording() 时再激活
    }

    deinit {
        // CI-fix: `deinit` 隐式 nonisolated，无法直接调用 MainActor-isolated 的
        // `stopAll()`。VoiceChatManager 是 @MainActor 类，所有引用通常来自
        // MainActor，deinit 也通常在 MainActor 触发。stopAll 仅调用
        // .stop() / .invalidate() / deactivateAudioSession（线程安全），
        // 使用 `MainActor.assumeIsolated` 同步桥接即可。
        MainActor.assumeIsolated {
            stopAll()
        }
    }

    // MARK: - AudioSession 配置

    /// 配置并激活 AVAudioSession（播放与录音模式）。
    ///
    /// HIG (Guideline 2.5.6)：仅在用户实际触发录音 / 播放时调用，
    /// 避免应用启动时中断用户正在听的音乐 / 播客。
    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
        } catch {
            errorMessage = "音频会话配置失败：\(error.localizedDescription)"
        }
    }

    /// 停用 AVAudioSession（停止录音 / 播放后调用，让其他 App 的音频恢复）
    private func deactivateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    // MARK: - 录音

    /// 开始录音
    ///
    /// HIG (Guideline 5.1.1)：
    /// - 在构造 `AVAudioRecorder` 之前显式请求麦克风权限
    /// - 仅在用户手势触发的上下文中调用（如 VoiceChatView 录音按钮点击）
    func startRecording() {
        // 释放上次录音资源
        audioRecorder?.stop()
        audioRecorder = nil
        waveformLevels = []
        audioLevel = 0.0
        recordingDuration = 0.0
        errorMessage = nil

        // HIG：先请求麦克风权限，再激活 audio session 与构造 recorder
        AVAudioApplication.requestRecordPermission { [weak self] granted in
            Task { @MainActor in
                guard let self else { return }
                guard granted else {
                    self.errorMessage = "麦克风权限被拒绝，请在系统设置中开启"
                    return
                }
                self.startRecordingInternal()
            }
        }
    }

    /// 实际启动录音的内部实现（权限通过后调用）
    private func startRecordingInternal() {
        // HIG：仅在确认录音意图后激活 audio session
        configureAudioSession()

        // 录音文件 URL
        let url = makeRecordingURL()
        lastRecordingURL = url

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue
        ]

        do {
            let recorder = try AVAudioRecorder(url: url, settings: settings)
            recorder.isMeteringEnabled = true
            recorder.prepareToRecord()
            recorder.record()
            audioRecorder = recorder
            isRecording = true

            // 启动定时采样
            startRecordingTimer()
        } catch {
            errorMessage = "录音启动失败：\(error.localizedDescription)"
            isRecording = false
            deactivateAudioSession()
        }
    }

    /// 停止录音并返回文件 URL
    /// - Returns: 录音文件 URL（失败时为 nil）
    @discardableResult
    func stopRecording() -> URL? {
        guard isRecording else { return nil }
        audioRecorder?.stop()
        recordingTimer?.invalidate()
        recordingTimer = nil
        isRecording = false
        audioLevel = 0.0
        let url = audioRecorder?.url
        audioRecorder = nil
        return url
    }

    /// 取消录音（删除文件）
    func cancelRecording() {
        guard isRecording else { return }
        audioRecorder?.stop()
        recordingTimer?.invalidate()
        recordingTimer = nil
        isRecording = false
        audioLevel = 0.0
        recordingDuration = 0.0
        waveformLevels = []
        // 删除文件
        if let url = audioRecorder?.url {
            try? FileManager.default.removeItem(at: url)
        }
        lastRecordingURL = nil
        audioRecorder = nil
    }

    // MARK: - 播放

    /// 播放指定 URL 的音频
    /// - Parameters:
    ///   - url: 音频文件 URL
    ///   - messageId: 关联的消息 ID（可选，用于 UI 高亮）
    func playAudio(url: URL, messageId: String? = nil) {
        // 若正在播放同一文件则切换为暂停
        if isPlaying, audioPlayer?.url == url {
            pausePlayback()
            return
        }

        // 停止当前播放
        stopPlayback()

        // HIG：播放前激活 audio session（与录音一致，不在 init() 激活）
        configureAudioSession()

        do {
            let player = try AVAudioPlayer(contentsOf: url)
            player.prepareToPlay()
            player.play()
            audioPlayer = player
            playbackDuration = player.duration
            currentPlayingMessageId = messageId
            isPlaying = true

            startPlaybackTimer()
        } catch {
            errorMessage = "播放失败：\(error.localizedDescription)"
            deactivateAudioSession()
        }
    }

    /// 暂停播放
    func pausePlayback() {
        audioPlayer?.pause()
        isPlaying = false
        playbackTimer?.invalidate()
        playbackTimer = nil
    }

    /// 恢复播放
    func resumePlayback() {
        audioPlayer?.play()
        isPlaying = true
        startPlaybackTimer()
    }

    /// 停止播放
    func stopPlayback() {
        audioPlayer?.stop()
        audioPlayer = nil
        isPlaying = false
        playbackProgress = 0.0
        playbackDuration = 0.0
        currentPlayingMessageId = nil
        playbackTimer?.invalidate()
        playbackTimer = nil
        // 释放 audio session，让其他应用音频恢复
        if !isRecording { deactivateAudioSession() }
    }

    /// 跳转到指定进度（0.0 ~ 1.0）
    /// - Parameter progress: 进度
    func seek(to progress: Double) {
        guard let player = audioPlayer else { return }
        let target = player.duration * progress
        player.currentTime = target
        playbackProgress = target
    }

    // MARK: - 全部停止

    /// 停止所有播放与录音
    func stopAll() {
        if isRecording { stopRecording() }
        if isPlaying { stopPlayback() }
        recordingTimer?.invalidate()
        playbackTimer?.invalidate()
        recordingTimer = nil
        playbackTimer = nil
        deactivateAudioSession()
    }

    // MARK: - 计时器

    /// 启动录音计时与采样
    ///
    /// Timer 由 MainActor 调度（本类为 @MainActor），回调在 main RunLoop 上触发，
    /// 因此闭包内通过 `MainActor.assumeIsolated` 同步访问 self。
    private func startRecordingTimer() {
        recordingTimer?.invalidate()
        recordingTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let recorder = self.audioRecorder, recorder.isRecording else { return }
                recorder.updateMeters()

                // averagePower 返回 -160 ~ 0 dB，归一化为 0 ~ 1
                let db = recorder.averagePower(forChannel: 0)
                let level = self.normalizeDBToLevel(db)
                self.audioLevel = level
                self.recordingDuration += 0.05

                // 写入波形采样
                self.waveformLevels.append(level)
                if self.waveformLevels.count > self.maxWaveformSamples {
                    self.waveformLevels.removeFirst(self.waveformLevels.count - self.maxWaveformSamples)
                }
            }
        }
    }

    /// 启动播放进度计时
    private func startPlaybackTimer() {
        playbackTimer?.invalidate()
        playbackTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let player = self.audioPlayer else { return }
                self.playbackProgress = player.currentTime
                if !player.isPlaying {
                    // 播放结束
                    self.stopPlayback()
                }
            }
        }
    }

    /// 将 dB 值（-160 ~ 0）归一化为 0 ~ 1 的线性值
    /// - Parameter db: dB 值
    /// - Returns: 归一化后的音量
    private func normalizeDBToLevel(_ db: Float) -> Float {
        // -160 dB → 0，-40 dB → 约 0.5，0 dB → 1
        let clamped = max(-160, min(0, db))
        // 简单线性映射 + 平方根曲线增强低音量可视化
        let normalized = (clamped + 160) / 160
        return sqrt(normalized)
    }

    // MARK: - 录音文件 URL

    /// 生成录音文件 URL。
    ///
    /// 录音文件存放在 `caches/VoiceRecordings/` 而非 `NSTemporaryDirectory()`：
    /// - 系统在低存储压力下才会清理 caches 目录，临时目录可能更早被回收，
    ///   导致播放时文件已丢失；
    /// - caches 目录不会被 iCloud/iTunes 备份，符合语音消息的临时性语义；
    /// - 与崩溃日志、备份等其它 caches 子目录平级，便于统一清理。
    /// - Returns: 文件 URL
    private func makeRecordingURL() -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = caches.appendingPathComponent("VoiceRecordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let fileName = "voice_\(Int(Date().timeIntervalSince1970 * 1000)).m4a"
        return dir.appendingPathComponent(fileName)
    }
}

// MARK: - VoiceMessage 模型

/// 已录制的语音消息
///
/// 由 VoiceChatView 录制完成后构造，加入消息列表用于回放展示。
/// 字段设计简洁，仅包含 UI 展示必需信息；持久化由调用方决定。
struct VoiceMessage: Identifiable, Hashable {
    /// 唯一 ID
    let id: String
    /// 音频文件 URL
    let url: URL
    /// 时长（秒）
    let duration: TimeInterval
    /// 录制时间戳（毫秒级）
    let timestamp: Int64
    /// 波形采样快照
    let waveform: [Float]
}
