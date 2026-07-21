import Foundation
import Observation

/// 性能监控
/// 对应 Android PerformanceMonitor — 记录消息延迟、连接质量、内存使用等
/// 集成 Apple Silicon 硬件检测和优化
@MainActor
@Observable
final class PerformanceMonitor {

    /// 性能指标集合
    struct Metrics {
        /// 平均消息往返延迟（毫秒）
        var avgMessageLatencyMs: Double = 0
        /// 连接质量评估: excellent / good / fair / poor
        var connectionQuality: String = "unknown"
        /// 当前进程内存使用量（MB）
        var memoryUsageMB: Double = 0
        /// 总消息计数
        var totalMessages: Int = 0
        /// 应用运行时间（分钟）
        var uptimeMinutes: Int = 0
        /// 活跃连接数
        var activeConnections: Int = 0
        // 硬件信息
        var chipType: String = "Unknown"
        var neuralEngineTOPS: Float = 0
        var cpuCores: Int = 0
        var totalMemoryGB: Double = 0
        var aiAcceleration: String = "Unknown"
        var thermalStatus: String = "Unknown"
    }

    /// 当前指标
    var metrics: Metrics = Metrics()

    /// 监控启动时间
    private let startTime = Date()

    /// 最近的消息延迟记录（最多保留 100 条）
    private var messageLatencies: [TimeInterval] = []

    /// 定时刷新定时器（每 5 秒更新一次指标）
    private var timer: Timer?

    /// 硬件信息缓存
    private var hardwareInfo: AppleSoCDetector.HardwareInfo?

    /// 初始化并启动监控
    init() {
        initializeHardware()
        startMonitoring()
    }

    /// 析构时清理定时器
    deinit {
        timer?.invalidate()
    }

    // MARK: - 硬件初始化

    /// 初始化硬件检测和优化配置
    private func initializeHardware() {
        let info = AppleSoCDetector.detect()
        hardwareInfo = info

        metrics.chipType = info.chipType.rawValue
        metrics.neuralEngineTOPS = info.neuralEngineTOPS
        metrics.cpuCores = info.cpuCores
        metrics.totalMemoryGB = info.totalMemoryGB

        let optimizerConfig = AppleOptimizer.shared.config
        if optimizerConfig.useNeuralEngine {
            metrics.aiAcceleration = "Neural Engine + Metal"
        } else if optimizerConfig.useMetalAcceleration {
            metrics.aiAcceleration = "Metal GPU"
        } else {
            metrics.aiAcceleration = "CPU only"
        }
    }

    // MARK: - 公开接口

    /// 记录一条消息的往返延迟
    /// - Parameter sendTime: 消息发送时间
    func recordMessageLatency(sendTime: Date) {
        let latency = Date().timeIntervalSince(sendTime)
        messageLatencies.append(latency)
        // 保留最近 100 条，避免内存无限增长
        if messageLatencies.count > 100 {
            messageLatencies.removeFirst()
        }
        updateMetrics()
    }

    /// 记录消息总数递增
    func incrementMessageCount() {
        metrics.totalMessages += 1
    }

    /// 更新活跃连接数
    /// - Parameter count: 当前活跃连接数
    func updateConnectionCount(_ count: Int) {
        metrics.activeConnections = count
    }

    /// 获取硬件摘要（用于设置页面显示）
    func getHardwareSummary() -> String {
        return AppleOptimizer.shared.hardwareSummary()
    }

    /// 获取端侧推理配置建议
    func getInferenceRecommendation() -> AppleSoCDetector.InferenceConfig? {
        guard let info = hardwareInfo else { return nil }
        return AppleSoCDetector.recommendInferenceConfig(info: info)
    }

    // MARK: - 私有方法

    /// 启动定时监控（每 5 秒刷新一次指标）
    ///
    /// Timer 由 MainActor 调度（本类为 @MainActor），回调在 main RunLoop 上触发，
    /// 因此闭包内通过 `MainActor.assumeIsolated` 同步访问 self。
    private func startMonitoring() {
        timer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.updateMetrics()
            }
        }
    }

    /// 计算并更新所有指标
    private func updateMetrics() {
        // 平均消息延迟
        if !messageLatencies.isEmpty {
            metrics.avgMessageLatencyMs = (messageLatencies.reduce(0, +) / Double(messageLatencies.count)) * 1000
        }

        // 运行时间
        metrics.uptimeMinutes = Int(Date().timeIntervalSince(startTime) / 60)

        // 内存使用（通过 Mach task_info 获取当前进程常驻内存大小）
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size) / 4
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        if result == KERN_SUCCESS {
            metrics.memoryUsageMB = Double(info.resident_size) / 1_048_576.0
        }

        // 连接质量评估（基于平均延迟阈值）
        if metrics.avgMessageLatencyMs < 500 {
            metrics.connectionQuality = "excellent"
        } else if metrics.avgMessageLatencyMs < 2000 {
            metrics.connectionQuality = "good"
        } else if metrics.avgMessageLatencyMs < 5000 {
            metrics.connectionQuality = "fair"
        } else {
            metrics.connectionQuality = "poor"
        }

        // 热状态更新
        let thermalState = ProcessInfo.processInfo.thermalState
        switch thermalState {
        case .nominal: metrics.thermalStatus = "Normal"
        case .fair: metrics.thermalStatus = "Fair"
        case .serious: metrics.thermalStatus = "Throttling"
        case .critical: metrics.thermalStatus = "Critical"
        @unknown default: metrics.thermalStatus = "Unknown"
        }
    }
}
