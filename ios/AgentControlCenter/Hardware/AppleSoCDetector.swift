import Foundation
import UIKit
import Darwin

// MARK: - Apple 芯片检测器

/// Apple Silicon 硬件检测器 — 识别 A 系列 / M 系列芯片和 Neural Engine 能力。
///
/// 覆盖芯片：
/// - A18 Pro / A19 Pro（iPhone 16 Pro / iPhone 17 Pro）
/// - M1 / M2 / M3 / M4 / M5（iPad Pro / Mac）
///
/// 检测方式：
/// - machdep.cpu.brand_string（CPU 型号字符串）
/// - ProcessInfo.processInfo.physicalMemory（物理内存）
/// - UIDevice.identifier（设备型号代号如 iPhone17,1）
/// - sysctlbyname（CPU 核心数、频率）
struct AppleSoCDetector {

    // MARK: 数据模型

    enum AppleChipType: String, CaseIterable {
        case a17Pro       = "A17 Pro"
        case a18          = "A18"
        case a18Pro       = "A18 Pro"
        case a19          = "A19"
        case a19Pro       = "A19 Pro"
        case m1           = "M1"
        case m1Pro        = "M1 Pro"
        case m1Max        = "M1 Max"
        case m2           = "M2"
        case m2Pro        = "M2 Pro"
        case m2Max        = "M2 Max"
        case m3           = "M3"
        case m3Pro        = "M3 Pro"
        case m3Max        = "M3 Max"
        case m4           = "M4"
        case m4Pro        = "M4 Pro"
        case m4Max        = "M4 Max"
        case m5           = "M5"
        case m5Pro        = "M5 Pro"
        case m5Max        = "M5 Max"
        case unknown      = "Unknown"

        var neuralEngineTOPS: Float {
            switch self {
            case .a17Pro: return 35
            case .a18: return 35
            case .a18Pro: return 35
            case .a19: return 50
            case .a19Pro: return 160
            case .m1: return 11
            case .m1Pro: return 11
            case .m1Max: return 11
            case .m2: return 15.8
            case .m2Pro: return 15.8
            case .m2Max: return 15.8
            case .m3: return 18
            case .m3Pro: return 18
            case .m3Max: return 18
            case .m4: return 38
            case .m4Pro: return 38
            case .m4Max: return 38
            case .m5: return 55
            case .m5Pro: return 55
            case .m5Max: return 55
            case .unknown: return 0
            }
        }

        var gpuCoreCount: Int {
            switch self {
            case .a17Pro: return 6
            case .a18: return 5
            case .a18Pro: return 6
            case .a19: return 5
            case .a19Pro: return 6
            case .m1: return 8
            case .m1Pro: return 16
            case .m1Max: return 32
            case .m2: return 10
            case .m2Pro: return 19
            case .m2Max: return 38
            case .m3: return 10
            case .m3Pro: return 18
            case .m3Max: return 40
            case .m4: return 10
            case .m4Pro: return 20
            case .m4Max: return 40
            case .m5: return 10
            case .m5Pro: return 20
            case .m5Max: return 40
            case .unknown: return 4
            }
        }

        var isHighEnd: Bool {
            neuralEngineTOPS >= 38
        }

        var isMobileChip: Bool {
            switch self {
            case .a17Pro, .a18, .a18Pro, .a19, .a19Pro: return true
            default: return false
            }
        }
    }

    struct HardwareInfo {
        let chipType: AppleChipType
        let cpuBrandString: String
        let cpuCores: Int
        let performanceCores: Int
        let efficiencyCores: Int
        let totalMemoryGB: Double
        let availableMemoryGB: Double
        let gpuCoreCount: Int
        let neuralEngineTOPS: Float
        let deviceModel: String
        let deviceIdentifier: String
        let supportsMetal3: Bool
        let supportsMetal4: Bool
        let osVersion: String
    }

    // MARK: 检测

    static func detect() -> HardwareInfo {
        let cpuBrand = readSysctlString("machdep.cpu.brand_string")
        let cpuCores = readSysctlInt("hw.logicalcpu")
        let physCores = readSysctlInt("hw.physicalcpu")
        let memSize = readSysctlUInt64("hw.memsize")
        let deviceModel = readSysctlString("hw.model")
        let deviceIdentifier = machineIdentifier()

        let chipType = identifyChip(cpuBrand: cpuBrand, deviceIdentifier: deviceIdentifier)

        let totalMemoryGB = Double(memSize) / (1024 * 1024 * 1024)
        let availableMemoryGB = availableMemory()

        let perfCores: Int
        let effCores: Int
        switch chipType {
        case .a17Pro, .a18Pro, .a19Pro:
            perfCores = 2; effCores = 4
        case .a18, .a19:
            perfCores = 2; effCores = 4
        case .m1, .m2, .m3, .m4, .m5:
            perfCores = 4; effCores = 4
        case .m1Pro, .m2Pro, .m3Pro, .m4Pro, .m5Pro:
            perfCores = 6 + (8 - physCores / 2); effCores = 2
        case .m1Max, .m2Max, .m3Max, .m4Max, .m5Max:
            perfCores = 8; effCores = 4
        default:
            perfCores = cpuCores / 2; effCores = cpuCores / 2
        }

        let osVersion = ProcessInfo.processInfo.operatingSystemVersionString
        let supportsMetal3 = MTLCreateSystemDefaultDevice() != nil
        let supportsMetal4 = supportsMetal3 && isMetal4Available()

        return HardwareInfo(
            chipType: chipType,
            cpuBrandString: cpuBrand,
            cpuCores: cpuCores,
            performanceCores: perfCores,
            efficiencyCores: effCores,
            totalMemoryGB: totalMemoryGB,
            availableMemoryGB: availableMemoryGB,
            gpuCoreCount: chipType.gpuCoreCount,
            neuralEngineTOPS: chipType.neuralEngineTOPS,
            deviceModel: deviceModel,
            deviceIdentifier: deviceIdentifier,
            supportsMetal3: supportsMetal3,
            supportsMetal4: supportsMetal4,
            osVersion: osVersion
        )
    }

    // MARK: 芯片识别

    private static func identifyChip(cpuBrand: String, deviceIdentifier: String) -> AppleChipType {
        let brand = cpuBrand.lowercased()

        // M 系列优先匹配（iPad Pro / Mac）
        if brand.contains("m5 max") { return .m5Max }
        if brand.contains("m5 pro") { return .m5Pro }
        if brand.contains("m5") { return .m5 }
        if brand.contains("m4 max") { return .m4Max }
        if brand.contains("m4 pro") { return .m4Pro }
        if brand.contains("m4") { return .m4 }
        if brand.contains("m3 max") { return .m3Max }
        if brand.contains("m3 pro") { return .m3Pro }
        if brand.contains("m3") { return .m3 }
        if brand.contains("m2 max") { return .m2Max }
        if brand.contains("m2 pro") { return .m2Pro }
        if brand.contains("m2") { return .m2 }
        if brand.contains("m1 max") { return .m1Max }
        if brand.contains("m1 pro") { return .m1Pro }
        if brand.contains("m1") { return .m1 }

        // A 系列通过设备标识符匹配
        return identifyAChip(deviceIdentifier: deviceIdentifier)
    }

    /// 通过设备型号代号识别 A 系列芯片。
    private static func identifyAChip(deviceIdentifier: String) -> AppleChipType {
        // iPhone 17 Pro = A19 Pro
        if deviceIdentifier.hasPrefix("iPhone18,") { return .a19Pro }
        // iPhone 17 = A19
        if deviceIdentifier.hasPrefix("iPhone18,3") || deviceIdentifier.hasPrefix("iPhone18,4") { return .a19 }
        // iPhone 16 Pro = A18 Pro
        if deviceIdentifier.hasPrefix("iPhone17,1") || deviceIdentifier.hasPrefix("iPhone17,2") { return .a18Pro }
        // iPhone 16 = A18
        if deviceIdentifier.hasPrefix("iPhone17,3") || deviceIdentifier.hasPrefix("iPhone17,4") { return .a18 }
        // iPhone 15 Pro = A17 Pro
        if deviceIdentifier.hasPrefix("iPhone16,1") || deviceIdentifier.hasPrefix("iPhone16,2") { return .a17Pro }
        // iPad Pro M4
        if deviceIdentifier.hasPrefix("iPad16,") { return .m4 }
        // iPad Pro M2
        if deviceIdentifier.hasPrefix("iPad14,") { return .m2 }

        return .unknown
    }

    // MARK: 推理配置推荐

    struct InferenceConfig {
        let canRunOnDevice: Bool
        let recommendedModelSize: String
        let recommendedQuantization: String
        let maxContextLength: Int
        let useNeuralEngine: Bool
        let useMetal: Bool
        let reason: String
    }

    static func recommendInferenceConfig(info: HardwareInfo) -> InferenceConfig {
        let tops = info.neuralEngineTOPS
        let ramGB = info.totalMemoryGB

        guard ramGB >= 4 else {
            return InferenceConfig(
                canRunOnDevice: false, recommendedModelSize: "1B",
                recommendedQuantization: "Q4_0", maxContextLength: 1024,
                useNeuralEngine: false, useMetal: false,
                reason: "Insufficient memory for on-device inference"
            )
        }

        if tops >= 50 {
            return InferenceConfig(
                canRunOnDevice: true, recommendedModelSize: "7-14B",
                recommendedQuantization: "Q4_K_M", maxContextLength: 8192,
                useNeuralEngine: true, useMetal: true,
                reason: "\(info.chipType.rawValue): Neural Engine \(tops) TOPS, suitable for 7-14B models"
            )
        } else if tops >= 35 {
            return InferenceConfig(
                canRunOnDevice: true, recommendedModelSize: "4-8B",
                recommendedQuantization: "Q4_K_M", maxContextLength: 4096,
                useNeuralEngine: true, useMetal: true,
                reason: "\(info.chipType.rawValue): Neural Engine \(tops) TOPS, 4-8B models recommended"
            )
        } else if tops >= 15 {
            return InferenceConfig(
                canRunOnDevice: true, recommendedModelSize: "3-7B",
                recommendedQuantization: "Q4_K_M", maxContextLength: 4096,
                useNeuralEngine: true, useMetal: true,
                reason: "\(info.chipType.rawValue): Neural Engine \(tops) TOPS, 3-7B models"
            )
        } else {
            return InferenceConfig(
                canRunOnDevice: ramGB >= 6, recommendedModelSize: "1-3B",
                recommendedQuantization: "Q4_0", maxContextLength: 2048,
                useNeuralEngine: false, useMetal: true,
                reason: "\(info.chipType.rawValue): Basic Neural Engine (\(tops) TOPS), small models only"
            )
        }
    }

    // MARK: 硬件摘要

    static func hardwareSummary() -> String {
        let info = detect()
        return """
        Chip: \(info.chipType.rawValue)
        CPU: \(info.cpuBrandString) (\(info.cpuCores) cores)
        RAM: \(String(format: "%.1f", info.totalMemoryGB))GB
        GPU: \(info.gpuCoreCount) cores
        Neural Engine: \(info.neuralEngineTOPS) TOPS
        Device: \(info.deviceIdentifier)
        OS: \(info.osVersion)
        Metal 3: \(info.supportsMetal3 ? "Supported" : "Not supported")
        Metal 4: \(info.supportsMetal4 ? "Supported" : "Not supported")
        """
    }

    // MARK: 系统工具

    private static func readSysctlString(_ name: String) -> String {
        var size: Int = 0
        sysctlbyname(name, nil, &size, nil, 0)
        var value = [CChar](repeating: 0, count: size)
        sysctlbyname(name, &value, &size, nil, 0)
        return String(cString: value)
    }

    private static func readSysctlInt(_ name: String) -> Int {
        var value: Int32 = 0
        var size = MemoryLayout<Int32>.size
        sysctlbyname(name, &value, &size, nil, 0)
        return Int(value)
    }

    private static func readSysctlUInt64(_ name: String) -> UInt64 {
        var value: UInt64 = 0
        var size = MemoryLayout<UInt64>.size
        sysctlbyname(name, &value, &size, nil, 0)
        return value
    }

    private static func machineIdentifier() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machineMirror = Mirror(reflecting: systemInfo.machine)
        return machineMirror.children.compactMap { $0.value as? Int8 }
            .map { Character(UnicodeScalar(UInt8(bitPattern: $0))) }
            .map { String($0) }
            .joined()
    }

    private static func availableMemory() -> Double {
        var vmStats = vm_statistics64()
        var count = mach_vm.size_t(MemoryLayout<vm_statistics64>.size / MemoryLayout<integer_t>.size)
        let hostPort = mach_host_self()
        let result = withUnsafeMutablePointer(to: &vmStats) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                host_statistics64(hostPort, HOST_VM_INFO64, $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return 0 }
        let pageSize = UInt64(vm_kernel_page_size)
        let free = UInt64(vmStats.free_count) * pageSize
        return Double(free) / (1024 * 1024 * 1024)
    }

    private static func isMetal4Available() -> Bool {
        // Metal 4 在 iOS 19+ / macOS 26+ 可用
        if #available(iOS 19, macOS 26, *) { return true }
        return false
    }
}
