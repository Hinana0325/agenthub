import SwiftUI
import Speech
import AVFoundation

/// 语音输入管理器 — 封装 SFSpeechRecognizer
/// 对应 Android VoiceInputManager (SpeechRecognizer)
//
// CI-fix: 标记 @MainActor。VoiceInputManager 由 ChatView (@MainActor View) 持有，
// `await voiceManager.startListening()` 跨 actor 边界要求 voiceManager Sendable。
// @MainActor 类自动 Sendable，且与 View 共享隔离，调用无需跨边界。
@Observable
@MainActor
final class VoiceInputManager {

    // CI-fix: 添加 `Equatable` 协议。`state == .listening` 等比较需要 Equatable，
    // 但 `case error(String)` 含关联值，Swift 不会自动合成 Equatable。
    // String 是 Equatable，故显式声明后编译器可合成。
    enum VoiceState: Equatable {
        case idle        // 未开始
        case listening   // 正在监听
        case processing  // 识别中
        case error(String)
    }

    var state: VoiceState = .idle
    var recognizedText: String = ""

    private var audioEngine: AVAudioEngine?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?

    /// 请求麦克风权限
    func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }

    /// 开始语音识别
    func startListening() async {
        // 1. 检查权限
        // CI-fix: Xcode 16.4 / Swift 6 下 `SFSpeechRecognizer.requestAuthorization()`
        // 的隐式 async 桥接不可用（"missing argument for parameter #1 in call"），
        // 改用显式 `withCheckedContinuation` 包装 completion handler。
        let speechAuth: SFSpeechRecognizerAuthorizationStatus = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status)
            }
        }
        let micAuth = await AVCaptureDevice.requestAccess(for: .audio)
        guard speechAuth == .authorized, micAuth else {
            state = .error("麦克风或语音识别权限未授权")
            return
        }

        // 2. 配置 AudioEngine
        let engine = AVAudioEngine()
        let node = engine.inputNode
        let format = node.outputFormat(forBus: 0)

        // 3. 配置 RecognitionRequest（先创建以便 installTap 闭包直接捕获，
        //    避免 MainActor 隔离的 self.recognitionRequest 跨线程访问）
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        recognitionRequest = request

        // CI-fix: AVAudioNode installTap 回调在音频线程触发（非 MainActor），
        // 不能访问 MainActor 隔离的 self.recognitionRequest。改为捕获局部
        // `request`（class 引用，闭包按值捕获），通过 nonisolated 方法 append(buffer)。
        node.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            request.append(buffer)
        }

        // 4. 创建 RecognitionTask
        let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "zh-Hans"))
        guard let recognizer, recognizer.isAvailable else {
            state = .error("语音识别器不可用")
            return
        }

        recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
            // CI-fix: SFSpeechRecognitionTask 回调可能在非 MainActor 线程触发，
            // 用 Task { @MainActor in } 桥接到 MainActor 访问 self 的属性。
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let result {
                    self.recognizedText = result.bestTranscription.formattedString
                }
                if error != nil || (result?.isFinal ?? false) {
                    self.stopListening()
                }
            }
        }

        // 5. 启动
        do {
            try engine.start()
            audioEngine = engine
            state = .listening
        } catch {
            state = .error("音频引擎启动失败: \(error.localizedDescription)")
        }
    }

    /// 停止语音识别
    func stopListening() {
        audioEngine?.stop()
        audioEngine?.inputNode.removeTap(onBus: 0)
        recognitionRequest?.endAudio()
        recognitionTask?.cancel()
        recognitionTask = nil
        recognitionRequest = nil
        audioEngine = nil
        if state == .listening {
            state = .idle
        }
    }
}
