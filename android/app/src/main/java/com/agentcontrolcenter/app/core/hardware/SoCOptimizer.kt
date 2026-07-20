package com.agentcontrolcenter.app.core.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.FileReader

/**
 * 统一 SoC 优化器 — 根据检测到的芯片厂商和型号动态调整应用行为。
 *
 * 各厂商优化策略：
 *
 * - **Snapdragon**: Hexagon NPU 委托（NNAPI → qti-npu）、CPU 亲和性调度
 * - **Dimensity**: APU NPU 委托（NNAPI → mtk-apu）、NeuroPilot 参数
 * - **Exynos**: NPU 委托（NNAPI → samsung-npu）、Xclipse GPU 加速
 * - **Tensor**: TPU 委托（NNAPI → google-tpu）、Edge TPU 配置
 *
 * 共通优化：
 * - 线程池大小根据 CPU 核心数调整
 * - 热降频策略（读取 thermal zone）
 * - 内存预算根据总 RAM 动态分配
 * - Ollama 请求参数自动注入
 */
object SoCOptimizer {

    data class OptimizationConfig(
        val vendor: SoCHardwareDetector.SoCVendor,
        val socModel: String,
        val aiAccelerator: SoCHardwareDetector.AIAccelerator,
        val thermalThrottlingEnabled: Boolean,
        val nnApiAccelerationEnabled: Boolean,
        val nnApiAcceleratorName: String,
        val cpuAffinityEnabled: Boolean,
        val preferredThreadPoolSize: Int,
        val inferenceThreadPoolSize: Int,
        val largeHeapEnabled: Boolean,
        val aggressiveCaching: Boolean,
        val maxConcurrentStreams: Int,
        val hardwareDecoderEnabled: Boolean,
        val renderThreadPriority: ThreadPriority,
        val inferenceThreadPriority: ThreadPriority,
        val memoryBudgetMB: Long,
        val vendorSpecificParams: Map<String, String>
    )

    enum class ThreadPriority(val androidPriority: Int) {
        LOWEST(19), BACKGROUND(10), DEFAULT(0),
        FOREGROUND(-2), URGENT(-8), DISPLAY(-10)
    }

    private val _config = MutableStateFlow(
        OptimizationConfig(
            vendor = SoCHardwareDetector.SoCVendor.OTHER,
            socModel = "Unknown",
            aiAccelerator = SoCHardwareDetector.AIAccelerator.NONE,
            thermalThrottlingEnabled = true,
            nnApiAccelerationEnabled = false,
            nnApiAcceleratorName = "",
            cpuAffinityEnabled = false,
            preferredThreadPoolSize = 4,
            inferenceThreadPoolSize = 2,
            largeHeapEnabled = false,
            aggressiveCaching = false,
            maxConcurrentStreams = 3,
            hardwareDecoderEnabled = true,
            renderThreadPriority = ThreadPriority.DISPLAY,
            inferenceThreadPriority = ThreadPriority.URGENT,
            memoryBudgetMB = 256L,
            vendorSpecificParams = emptyMap()
        )
    )
    val config: StateFlow<OptimizationConfig> = _config.asStateFlow()

    private var hardwareInfo: SoCHardwareDetector.HardwareInfo? = null

    fun initialize(context: Context) {
        val info = SoCHardwareDetector.detect(context)
        hardwareInfo = info
        _config.value = generateConfig(context, info)
        applyThreadPriorities(_config.value)
    }

    private fun generateConfig(
        context: Context,
        info: SoCHardwareDetector.HardwareInfo
    ): OptimizationConfig {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true
        val ramGB = info.totalMemoryMB / 1024

        val cpuCores = info.cpuCores.coerceAtLeast(2)
        val preferredPool = (cpuCores - 1).coerceIn(2, 6)
        val inferencePool = info.cpuClusters.firstOrNull { it.name == "prime" }?.coreCount
            ?: info.cpuClusters.firstOrNull { it.name == "big" }?.coreCount
            ?: 1

        // 厂商特定配置
        val (nnApiEnabled, nnApiName, vendorParams) = getVendorAIConfig(info)

        val largeHeap = ramGB >= 6
        val aggressiveCache = ramGB >= 8

        val maxStreams = when (info.vendor) {
            SoCHardwareDetector.SoCVendor.SNAPDRAGON, SoCHardwareDetector.SoCVendor.DIMENSITY -> 5
            SoCHardwareDetector.SoCVendor.EXYNOS, SoCHardwareDetector.SoCVendor.TENSOR -> 4
            else -> 3
        }

        val memoryBudget = when {
            ramGB >= 12 -> 1024L
            ramGB >= 8 -> 512L
            ramGB >= 6 -> 256L
            else -> 128L
        }

        val thermalThrottling = info.vendor != SoCHardwareDetector.SoCVendor.OTHER
        val cpuAffinity = info.aiTops >= 35f

        return OptimizationConfig(
            vendor = info.vendor,
            socModel = info.socModel,
            aiAccelerator = info.aiAccelerator,
            thermalThrottlingEnabled = thermalThrottling,
            nnApiAccelerationEnabled = nnApiEnabled,
            nnApiAcceleratorName = nnApiName,
            cpuAffinityEnabled = cpuAffinity,
            preferredThreadPoolSize = preferredPool,
            inferenceThreadPoolSize = inferencePool,
            largeHeapEnabled = largeHeap,
            aggressiveCaching = aggressiveCache,
            maxConcurrentStreams = maxStreams,
            hardwareDecoderEnabled = info.gpuRenderer.isNotBlank() && info.gpuRenderer != "Unknown GPU",
            renderThreadPriority = ThreadPriority.DISPLAY,
            inferenceThreadPriority = if (info.aiTops >= 40f) ThreadPriority.URGENT else ThreadPriority.FOREGROUND,
            memoryBudgetMB = memoryBudget,
            vendorSpecificParams = vendorParams
        )
    }

    /**
     * 各厂商的 AI 加速器配置。
     */
    private fun getVendorAIConfig(
        info: SoCHardwareDetector.HardwareInfo
    ): Triple<Boolean, String, Map<String, String>> {
        return when (info.vendor) {
            SoCHardwareDetector.SoCVendor.SNAPDRAGON -> {
                val gen = extractSnapdragonGen(info.socModel)
                Triple(
                    info.supportsNNAPI && gen >= 1,
                    "qti-npu",
                    mapOf(
                        "precision" to if (gen >= 3) "INT8" else "FP16",
                        "hexagon_version" to if (gen >= 3) "HMX" else "HTP",
                        "allow_fp16_fallback" to (gen < 3).toString()
                    )
                )
            }
            SoCHardwareDetector.SoCVendor.DIMENSITY -> {
                val gen = extractDimensityGen(info.socModel)
                Triple(
                    info.supportsNNAPI && gen >= 7,
                    "mtk-apu",
                    mapOf(
                        "precision" to if (gen >= 8) "INT8" else "FP16",
                        "apu_version" to if (gen >= 8) "NPU 890" else "NPU 790",
                        "neuropilot" to "enabled"
                    )
                )
            }
            SoCHardwareDetector.SoCVendor.EXYNOS -> {
                val is2500 = info.socModel.contains("2500")
                Triple(
                    info.supportsNNAPI,
                    "samsung-npu",
                    mapOf(
                        "precision" to if (is2500) "INT8" else "FP16",
                        "npu_config" to if (is2500) "24K-MAC" else "17K-MAC",
                        "xclipse_gpu" to "enabled"
                    )
                )
            }
            SoCHardwareDetector.SoCVendor.TENSOR -> {
                val isG4Plus = info.socModel.contains("G4") || info.socModel.contains("G5")
                Triple(
                    info.supportsNNAPI,
                    "google-tpu",
                    mapOf(
                        "precision" to if (isG4Plus) "INT8" else "FP16",
                        "tpu_arch" to if (isG4Plus) "Matformer" else "Standard",
                        "edge_tpu" to "enabled"
                    )
                )
            }
            else -> Triple(false, "", emptyMap())
        }
    }

    // ── 线程优先级 ──

    private fun applyThreadPriorities(config: OptimizationConfig) {
        try {
            Process.setThreadPriority(Process.myTid(), config.renderThreadPriority.androidPriority)
        } catch (_: Exception) { }
    }

    fun applyInferenceThreadPriority() {
        try {
            Process.setThreadPriority(Process.myTid(), _config.value.inferenceThreadPriority.androidPriority)
        } catch (_: Exception) { }
    }

    // ── 热降频 ──

    fun shouldThrottle(): Boolean {
        val info = hardwareInfo ?: return false
        if (info.vendor == SoCHardwareDetector.SoCVendor.OTHER) return false
        val temp = readCpuTemperature()
        return temp > 75
    }

    fun getThrottledConfig(): ThrottledInferenceConfig {
        val base = _config.value
        val throttling = shouldThrottle()
        return if (!throttling) {
            ThrottledInferenceConfig(false, false, false, base.inferenceThreadPoolSize, "Q4_K_M")
        } else {
            ThrottledInferenceConfig(true, true, true, 1, "Q4_0")
        }
    }

    data class ThrottledInferenceConfig(
        val shouldReduceConcurrency: Boolean,
        val shouldLowerPrecision: Boolean,
        val shouldSkipNonEssentialWork: Boolean,
        val recommendedConcurrency: Int,
        val recommendedPrecision: String
    )

    private fun readCpuTemperature(): Float {
        val zones = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone7/temp",
            "/sys/class/thermal/thermal_zone15/temp"
        )
        for (zone in zones) {
            try {
                val temp = BufferedReader(FileReader(zone)).use { it.readLine().trim().toFloat() }
                return if (temp > 1000) temp / 1000 else temp
            } catch (_: Exception) { continue }
        }
        return -1f
    }

    // ── Ollama 参数 ──

    fun getOllamaOptions(): Map<String, Any> {
        val config = _config.value
        val info = hardwareInfo
        val throttled = getThrottledConfig()

        val options = mutableMapOf<String, Any>()
        options["num_thread"] = throttled.recommendedConcurrency

        info?.let {
            val inferenceConfig = SoCHardwareDetector.recommendInferenceConfig(it)
            options["num_ctx"] = inferenceConfig.maxContextLength
        }

        options["num_batch"] = if (throttled.shouldReduceConcurrency) 128 else 512

        if (config.nnApiAccelerationEnabled) {
            options["num_gpu"] = 99
        }

        // 厂商特定参数
        when (config.vendor) {
            SoCHardwareDetector.SoCVendor.DIMENSITY -> {
                // MediaTek NeuroPilot 参数
                options["flash_attn"] = config.vendorSpecificParams["neuropilot"] == "enabled"
            }
            SoCHardwareDetector.SoCVendor.TENSOR -> {
                // Google TPU 优化
                options["flash_attn"] = true
            }
            else -> { }
        }

        return options
    }

    // ── 硬件摘要 ──

    fun getHardwareSummary(): String {
        val info = hardwareInfo ?: return "Hardware not detected"
        val config = _config.value

        return buildString {
            appendLine("SoC: ${info.socModel}")
            if (info.socCodeName.isNotBlank()) appendLine("Code: ${info.socCodeName}")
            appendLine("Vendor: ${info.vendor.displayName}")
            appendLine("AI: ${info.aiAccelerator.displayName} (${info.aiTops} TOPS)")
            appendLine("CPU: ${info.cpuCores} cores @ ${info.cpuMaxFreqMHz}MHz")
            appendLine("RAM: ${info.totalMemoryMB}MB (${info.availableMemoryMB}MB available)")
            if (info.gpuRenderer != "Unknown GPU") appendLine("GPU: ${info.gpuRenderer}")
            appendLine("ABI: ${info.abi}")
            if (info.supportsNNAPI) appendLine("NNAPI: Supported (${config.nnApiAcceleratorName})")
            if (info.supports16KPages) appendLine("16KB Pages: Enabled")
            if (config.nnApiAccelerationEnabled) appendLine("AI Acceleration: Enabled")
            if (config.largeHeapEnabled) appendLine("Large Heap: Enabled")
        }.trim()
    }

    // ── 厂商代数提取 ──

    private fun extractSnapdragonGen(model: String): Int {
        Regex("Gen\\s*(\\d+)").find(model)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        if (model.contains("Elite", ignoreCase = true)) return 5
        return 0
    }

    private fun extractDimensityGen(model: String): Int {
        // NPU 7代 = 9200/9300, NPU 8代 = 9400
        return when {
            model.contains("9400") -> 8
            model.contains("9300") || model.contains("9200") -> 7
            model.contains("8300") -> 7
            else -> 6
        }
    }
}
