import Foundation
import Metal
import CoreML

// MARK: - Apple 芯片优化器

/// Apple Silicon 优化器 — 根据检测到的芯片型号应用 CoreML / Metal / Neural Engine 优化。
///
/// 优化策略：
/// - **CoreML 推理委托**：Neural Engine 优先 > GPU (Metal) > CPU
/// - **Metal Performance Shaders**：GPU 加速矩阵运算
/// - **QoS 线程优先级**：推理任务使用 userInteractive 优先级
/// - **内存预算**：根据 RAM 大小动态分配推理内存
/// - **热降频**：高温时降低并发和精度
/// - **MLModel 编译**：使用 .optimizationLevel 参数
struct AppleOptimizer {

    struct OptimizationConfig {
        let chipType: AppleSoCDetector.AppleChipType
        let neuralEngineTOPS: Float
        let totalMemoryGB: Double
        let preferredThreadPoolSize: Int
        let inferenceThreadPoolSize: Int
        let useNeuralEngine: Bool
        let useMetalAcceleration: Bool
        let metalDeviceName: String
        let coreMLComputeUnits: MLComputeUnits
        let maxConcurrentStreams: Int
        let memoryBudgetMB: Double
        let thermalThrottlingEnabled: Bool
        let aggressiveCaching: Bool
        let modelOptimizationLevel: ModelOptimizationLevel
    }

    enum ModelOptimizationLevel: Int {
        case fast = 0        // 快速编译，运行时优化少
        case balanced = 1   // 平衡（默认）
        case aggressive = 2  // 激进优化，编译慢但运行快
    }

    static let shared = AppleOptimizer()

    private(set) var config: OptimizationConfig = {
        let info = AppleSoCDetector.detect()
        return generateConfig(info: info)
    }()

    private var hardwareInfo: AppleSoCDetector.HardwareInfo?

    // MARK: 初始化

    mutating func initialize() {
        let info = AppleSoCDetector.detect()
        hardwareInfo = info
        config = AppleOptimizer.generateConfig(info: info)
    }

    private static func generateConfig(info: AppleSoCDetector.HardwareInfo) -> OptimizationConfig {
        let chip = info.chipType
        let tops = chip.neuralEngineTOPS
        let ramGB = info.totalMemoryGB

        // CoreML 计算单元选择优先级：Neural Engine > GPU > CPU
        let computeUnits: MLComputeUnits
        if tops >= 35 {
            computeUnits = .all               // 优先 Neural Engine，回退 GPU
        } else if tops >= 15 {
            computeUnits = .all               // 同上，但能力较低
        } else {
            computeUnits = .cpuAndGPU         // 跳过 Neural Engine
        }

        let useNeuralEngine = tops >= 15
        let useMetal = info.supportsMetal3

        // Metal 设备
        let metalDevice = MTLCreateSystemDefaultDevice()
        let metalName = metalDevice?.name ?? "Unknown"

        // 线程池大小
        let preferredPool = max(2, info.cpuCores - 1)
        let inferencePool = max(1, info.performanceCores)

        // 并发流数
        let maxStreams: Int
        if tops >= 50 { maxStreams = 6 }
        else if tops >= 35 { maxStreams = 5 }
        else if tops >= 15 { maxStreams = 4 }
        else { maxStreams = 3 }

        // 内存预算
        let memoryBudget: Double
        if ramGB >= 16 { memoryBudget = 2048 }
        else if ramGB >= 8 { memoryBudget = 1024 }
        else if ramGB >= 6 { memoryBudget = 512 }
        else { memoryBudget = 256 }

        // 优化级别
        let optLevel: ModelOptimizationLevel
        if tops >= 38 {
            optLevel = .aggressive   // M4+/A19 Pro：激进优化
        } else if tops >= 15 {
            optLevel = .balanced
        } else {
            optLevel = .fast
        }

        return OptimizationConfig(
            chipType: chip,
            neuralEngineTOPS: tops,
            totalMemoryGB: ramGB,
            preferredThreadPoolSize: preferredPool,
            inferenceThreadPoolSize: inferencePool,
            useNeuralEngine: useNeuralEngine,
            useMetalAcceleration: useMetal,
            metalDeviceName: metalName,
            coreMLComputeUnits: computeUnits,
            maxConcurrentStreams: maxStreams,
            memoryBudgetMB: memoryBudget,
            thermalThrottlingEnabled: true,
            aggressiveCaching: ramGB >= 8,
            modelOptimizationLevel: optLevel
        )
    }

    // MARK: CoreML 配置

    /// 生成 CoreML 模型加载配置。
    func makeCoreMLConfig() -> MLModelConfiguration {
        let mlConfig = MLModelConfiguration()
        // CI-fix: 原代码 `config.coreMLComputeUnits` 引用了 MLModelConfiguration 上
        // 不存在的成员。意图是读取 `self.config`（OptimizationConfig）的 coreMLComputeUnits。
        mlConfig.computeUnits = self.config.coreMLComputeUnits
        return mlConfig
    }

    // MARK: 热降频

    func shouldThrottle() -> Bool {
        // ProcessInfo.thermalState 在 iOS 11+ 可用
        let state = ProcessInfo.processInfo.thermalState
        return state == .serious || state == .critical
    }

    struct ThrottledConfig {
        let shouldReduceConcurrency: Bool
        let shouldLowerPrecision: Bool
        let recommendedConcurrency: Int
        let recommendedPrecision: String
    }

    func getThrottledConfig() -> ThrottledConfig {
        if !shouldThrottle() {
            return ThrottledConfig(
                shouldReduceConcurrency: false,
                shouldLowerPrecision: false,
                recommendedConcurrency: config.inferenceThreadPoolSize,
                recommendedPrecision: "Q4_K_M"
            )
        }
        return ThrottledConfig(
            shouldReduceConcurrency: true,
            shouldLowerPrecision: true,
            recommendedConcurrency: 1,
            recommendedPrecision: "Q4_0"
        )
    }

    // MARK: Ollama 参数

    /// 生成 Ollama API 请求参数，附带 Apple 芯片优化。
    func getOllamaOptions() -> [String: Any] {
        let info = AppleSoCDetector.detect()
        let inferenceConfig = AppleSoCDetector.recommendInferenceConfig(info: info)
        let throttled = getThrottledConfig()

        var options: [String: Any] = [:]
        options["num_thread"] = throttled.recommendedConcurrency
        options["num_ctx"] = inferenceConfig.maxContextLength
        options["num_batch"] = throttled.shouldReduceConcurrency ? 128 : 512

        // Metal GPU 加速
        if config.useMetalAcceleration {
            options["num_gpu"] = 99
            options["flash_attn"] = true
        }

        return options
    }

    // MARK: 硬件摘要

    func hardwareSummary() -> String {
        return AppleSoCDetector.hardwareSummary()
    }

    // MARK: 推理配置

    func inferenceRecommendation() -> AppleSoCDetector.InferenceConfig? {
        guard let info = hardwareInfo else { return nil }
        return AppleSoCDetector.recommendInferenceConfig(info: info)
    }

    // MARK: Metal 设备信息

    /// 获取 Metal GPU 设备详细信息。
    func getMetalDeviceInfo() -> MetalDeviceInfo? {
        guard let device = MTLCreateSystemDefaultDevice() else { return nil }
        // CI-fix: `supportsMeshShaders` 是 iOS 26+ / Metal 4 新增 API，
        // iOS 18 SDK 上不存在该成员。用 #if compiler(>=6.2) 编译时门控，
        // 低版本编译器走 false 兜底（与原本"近似判断"的注释语义一致）。
        #if compiler(>=6.2)
        let supportsMeshShaders = device.supportsMeshShaders
        #else
        let supportsMeshShaders = false
        #endif
        return MetalDeviceInfo(
            name: device.name,
            supportsRayTracing: device.supportsRaytracing,
            supportsMeshShaders: supportsMeshShaders,
            unifiedMemory: device.hasUnifiedMemory,
            recommendedMaxWorkingSetSize: device.recommendedMaxWorkingSetSize,
            maxThreadgroupMemoryLength: device.maxThreadgroupMemoryLength,
            supportsMetal4: supportsMeshShaders // 近似判断
        )
    }

    struct MetalDeviceInfo {
        let name: String
        let supportsRayTracing: Bool
        let supportsMeshShaders: Bool
        let unifiedMemory: Bool
        let recommendedMaxWorkingSetSize: UInt64
        let maxThreadgroupMemoryLength: Int
        let supportsMetal4: Bool
    }
}
