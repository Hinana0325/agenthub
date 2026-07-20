package com.agentcontrolcenter.app.core.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 骁龙芯片优化器 — 根据检测到的硬件能力动态调整应用行为。
 *
 * 核心优化策略：
 *
 * 1. **CPU 亲和性调度**：将推理任务绑定到 Prime/Big 集群，UI 任务保留在 Little 集群
 * 2. **内存管理**：大内存设备启用更激进的缓存策略
 * 3. **NNAPI 委托**：Hexagon NPU 支持时启用硬件加速推理
 * 4. **线程池调优**：根据 CPU 核心数调整并发度
 * 5. **热降频策略**：高温时自动降低推理精度/频率
 */
object SnapdragonOptimizer {

    data class OptimizationConfig(
        val isSnapdragon: Boolean,
        val socModel: String,
        val thermalThrottlingEnabled: Boolean,
        val nnApiAccelerationEnabled: Boolean,
        val cpuAffinityEnabled: Boolean,
        val preferredThreadPoolSize: Int,
        val inferenceThreadPoolSize: Int,
        val largeHeapEnabled: Boolean,
        val aggressiveCaching: Boolean,
        val maxConcurrentStreams: Int,
        val hardwareDecoderEnabled: Boolean,
        val renderThreadPriority: ThreadPriority,
        val inferenceThreadPriority: ThreadPriority,
        val memoryBudgetMB: Long
    )

    enum class ThreadPriority(val androidPriority: Int) {
        LOWEST(19),    // 后台低优先级
        BACKGROUND(10),
        DEFAULT(0),
        FOREGROUND(-2),
        URGENT(-8),   // 推理任务
        DISPLAY(-10)  // UI 渲染
    }

    private val _config = MutableStateFlow(
        OptimizationConfig(
            isSnapdragon = false,
            socModel = "Unknown",
            thermalThrottlingEnabled = true,
            nnApiAccelerationEnabled = false,
            cpuAffinityEnabled = false,
            preferredThreadPoolSize = 4,
            inferenceThreadPoolSize = 2,
            largeHeapEnabled = false,
            aggressiveCaching = false,
            maxConcurrentStreams = 3,
            hardwareDecoderEnabled = true,
            renderThreadPriority = ThreadPriority.DISPLAY,
            inferenceThreadPriority = ThreadPriority.URGENT,
            memoryBudgetMB = 256L
        )
    )
    val config: StateFlow<OptimizationConfig> = _config.asStateFlow()

    private var hardwareInfo: SnapdragonHardwareDetector.HardwareInfo? = null

    /**
     * 初始化：检测硬件并生成优化配置。
     * 应在 Application.onCreate() 中调用。
     */
    fun initialize(context: Context) {
        val info = SnapdragonHardwareDetector.detect(context)
        hardwareInfo = info

        val config = generateOptimizationConfig(context, info)
        _config.value = config

        // 应用线程优先级
        applyThreadPriorities(config)
    }

    /**
     * 根据硬件信息生成优化配置。
     */
    private fun generateOptimizationConfig(
        context: Context,
        info: SnapdragonHardwareDetector.HardwareInfo
    ): OptimizationConfig {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = activityManager?.isLowRamDevice == true
        val ramGB = info.totalMemoryMB / 1024

        // CPU 核心数决定线程池大小
        val cpuCores = info.cpuCores.coerceAtLeast(2)
        val preferredPoolSize = (cpuCores - 1).coerceIn(2, 6)  // 留一核给 UI
        val inferencePoolSize = when {
            info.primeCoreCluster != null -> info.primeCoreCluster.coreCount.coerceAtLeast(1)
            info.bigCoreCluster != null -> info.bigCoreCluster.coreCount.coerceAtLeast(1)
            else -> 1
        }

        // NNAPI 加速：骁龙 8 系列支持 Hexagon NPU
        val gen = extractGeneration(info.socModel)
        val nnApiEnabled = info.isSnapdragon && info.supportsNNAPI && gen >= 1

        // 大堆内存：8GB+ 设备受益
        val largeHeap = ramGB >= 6 && info.isSnapdragon

        // 激进缓存：12GB+ 设备可以多缓存
        val aggressiveCache = ramGB >= 8

        // 最大并发流：取决于网络和 CPU
        val maxStreams = when {
            gen >= 3 -> 5   // 8 Gen 3+ 可以处理更多并发
            gen >= 1 -> 4
            else -> 3
        }

        // 内存预算：留给推理的内存
        val memoryBudget = when {
            ramGB >= 12 -> 1024L   // 1GB 推理预算
            ramGB >= 8 -> 512L
            ramGB >= 6 -> 256L
            else -> 128L
        }

        // 热降频：骁龙 8 系列需要主动热管理
        val thermalThrottling = info.isSnapdragon && gen >= 1

        // CPU 亲和性：只有 root 或特定 API 才能设置
        // 这里只是标记，实际通过 sched_setaffinity 需要权限
        val cpuAffinity = info.isSnapdragon && gen >= 2

        return OptimizationConfig(
            isSnapdragon = info.isSnapdragon,
            socModel = info.socModel,
            thermalThrottlingEnabled = thermalThrottling,
            nnApiAccelerationEnabled = nnApiEnabled,
            cpuAffinityEnabled = cpuAffinity,
            preferredThreadPoolSize = preferredPoolSize,
            inferenceThreadPoolSize = inferencePoolSize,
            largeHeapEnabled = largeHeap,
            aggressiveCaching = aggressiveCache,
            maxConcurrentStreams = maxStreams,
            hardwareDecoderEnabled = info.gpuRenderer.contains("Adreno", ignoreCase = true),
            renderThreadPriority = ThreadPriority.DISPLAY,
            inferenceThreadPriority = if (gen >= 3) ThreadPriority.URGENT else ThreadPriority.FOREGROUND,
            memoryBudgetMB = memoryBudget
        )
    }

    /**
     * 应用线程优先级。
     * 使用 Process.setThreadPriority，不需要 root 权限。
     */
    private fun applyThreadPriorities(config: OptimizationConfig) {
        try {
            // 主线程（UI）设为显示优先级
            android.os.Process.setThreadPriority(
                android.os.Process.myTid(),
                config.renderThreadPriority.androidPriority
            )
        } catch (_: Exception) {
            // 设置失败不影响功能
        }
    }

    /**
     * 为推理工作线程设置优先级。
     * 在创建推理线程时调用。
     */
    fun applyInferenceThreadPriority() {
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.myTid(),
                _config.value.inferenceThreadPriority.androidPriority
            )
        } catch (_: Exception) { }
    }

    // ── 热降频策略 ──

    /**
     * 检查是否需要热降频。
     * 在骁龙设备上，长时间高负载会导致 SoC 降频。
     */
    fun shouldThrottle(): Boolean {
        val info = hardwareInfo ?: return false
        if (!info.isSnapdragon) return false

        // 通过 /sys/class/thermal 读取温度
        val temp = readCpuTemperature()
        if (temp < 0) return false

        // 骁龙 8 系列热阈值约 45°C（皮肤温度），SoC 内部约 75-85°C
        return temp > 75
    }

    /**
     * 获取热降频后的推理配置。
     * 高温时降低并发度和精度。
     */
    fun getThrottledConfig(): ThrottledInferenceConfig {
        val baseConfig = _config.value
        val throttling = shouldThrottle()

        return if (!throttling) {
            ThrottledInferenceConfig(
                shouldReduceConcurrency = false,
                shouldLowerPrecision = false,
                shouldSkipNonEssentialWork = false,
                recommendedConcurrency = baseConfig.inferenceThreadPoolSize,
                recommendedPrecision = "Q4_K_M"
            )
        } else {
            // 降频：减少并发 + 降低精度
            ThrottledInferenceConfig(
                shouldReduceConcurrency = true,
                shouldLowerPrecision = true,
                shouldSkipNonEssentialWork = true,
                recommendedConcurrency = 1,
                recommendedPrecision = "Q4_0"  // 更激进的量化
            )
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
        // 骁龙热区路径
        val thermalZones = listOf(
            "/sys/class/thermal/thermal_zone0/temp",  // CPU
            "/sys/class/thermal/thermal_zone1/temp",  // GPU
            "/sys/class/thermal/thermal_zone7/temp",  // SoC
            "/sys/class/thermal/thermal_zone15/temp"  // Hexagon NPU
        )

        for (zone in thermalZones) {
            try {
                val temp = java.io.BufferedReader(java.io.FileReader(zone)).use {
                    it.readLine().trim().toFloat()
                }
                // 某些热区返回千分温度（如 45000 = 45°C），某些返回整数
                return if (temp > 1000) temp / 1000 else temp
            } catch (_: Exception) {
                continue
            }
        }
        return -1f
    }

    // ── NNAPI 配置生成 ──

    /**
     * 生成 NNAPI 委托配置（用于 Ollama/llama.cpp 请求参数）。
     * 返回 null 表示不支持或未启用。
     */
    fun getNNApiConfig(): NNApiConfig? {
        val config = _config.value
        if (!config.nnApiAccelerationEnabled) return null

        val info = hardwareInfo ?: return null
        val gen = extractGeneration(info.socModel)

        return NNApiConfig(
            enableNNAPI = true,
            // 骁龙 8 Gen 3+ 使用 HMX（扩展矩阵乘法），支持 INT8/INT16
            preferredPrecision = if (gen >= 3) "INT8" else "FP16",
            // Hexagon NPU 优先
            acceleratorName = "qti-npu",
            // 允许编译时优化
            allowFp16Fallback = gen < 3,
            // 使用扩展执行
            useDynamicShapes = gen >= 3
        )
    }

    data class NNApiConfig(
        val enableNNAPI: Boolean,
        val preferredPrecision: String,
        val acceleratorName: String,
        val allowFp16Fallback: Boolean,
        val useDynamicShapes: Boolean
    )

    /**
     * 生成 Ollama API 请求参数，附带硬件优化提示。
     */
    fun getOllamaOptions(): Map<String, Any> {
        val config = _config.value
        val info = hardwareInfo
        val throttled = getThrottledConfig()

        val options = mutableMapOf<String, Any>()

        // 并发线程数
        options["num_thread"] = throttled.recommendedConcurrency

        // 上下文长度（根据内存预算）
        info?.let {
            val inferenceConfig = SnapdragonHardwareDetector.recommendInferenceConfig(it)
            options["num_ctx"] = inferenceConfig.maxContextLength
        }

        // 批处理大小（影响推理速度）
        options["num_batch"] = if (throttled.shouldReduceConcurrency) 128 else 512

        // GPU layers（如果使用 GPU 加速）
        if (config.nnApiAccelerationEnabled) {
            options["num_gpu"] = 99  // 尽可能多地放到 GPU/NPU
        }

        return options
    }

    /**
     * 获取硬件摘要，用于显示在设置页面。
     */
    fun getHardwareSummary(): String {
        val info = hardwareInfo ?: return "Hardware not detected"
        val config = _config.value

        return buildString {
            appendLine("SoC: ${info.socModel}")
            if (info.socCodeName.isNotBlank()) appendLine("Code: ${info.socCodeName}")
            appendLine("CPU: ${info.cpuCores} cores @ ${info.cpuMaxFreqMHz}MHz")
            appendLine("RAM: ${info.totalMemoryMB}MB (${info.availableMemoryMB}MB available)")
            if (info.gpuRenderer != "Unknown") appendLine("GPU: ${info.gpuRenderer}")
            appendLine("ABI: ${info.abi}")
            if (info.supportsNNAPI) appendLine("NNAPI: Supported (Hexagon NPU)")
            if (info.supports16KPages) appendLine("16KB Pages: Enabled")
            if (config.nnApiAccelerationEnabled) appendLine("NPU Acceleration: Enabled")
            if (config.largeHeapEnabled) appendLine("Large Heap: Enabled")
        }.trim()
    }

    private fun extractGeneration(model: String): Int {
        val match = Regex("Gen\\s*(\\d+)").find(model)
        if (match != null) return match.groupValues[1].toIntOrNull() ?: 0
        // Elite 系列视作 Gen 5
        if (model.contains("Elite", ignoreCase = true)) return 5
        return 0
    }
}
