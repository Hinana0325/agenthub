package com.agentcontrolcenter.app.core.hardware

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import java.io.BufferedReader
import java.io.FileReader

/**
 * 骁龙硬件检测器 — 识别 SoC 型号、NPU/GPU 能力、内存带宽等。
 *
 * 骁龙芯片的优化需要知道具体型号，因为不同代际的 Hexagon NPU
 * 和 Adreno GPU 能力差异很大：
 *
 * - Snapdragon 8 Gen 1/2: Hexagon NPU (HTP), Adreno 730/740
 * - Snapdragon 8 Gen 3: Hexagon NPU (HMX), Adreno 750
 * - Snapdragon 8 Elite / Gen 5: Hexagon NPU (HMX FP16), Adreno 830
 *
 * 通过 /proc/cpuinfo 读取 SoC 信息，通过 ActivityManager 获取内存，
 * 通过 GLES 查询 GPU 型号。
 */
object SnapdragonHardwareDetector {

    data class HardwareInfo(
        val isSnapdragon: Boolean,
        val socModel: String,          // e.g. "Snapdragon 8 Gen 3"
        val socCodeName: String,        // e.g. "SM8650-AB"
        val cpuCores: Int,
        val cpuMaxFreqMHz: Long,        // 最高核心频率
        val totalMemoryMB: Long,
        val availableMemoryMB: Long,
        val gpuRenderer: String,        // e.g. "Adreno(TM) 750"
        val gpuVersion: String,
        val supportsNNAPI: Boolean,
        val supports16KPages: Boolean,
        val abi: String,
        val thermalHeadroom: Float,     // 热余量（℃），-1 表示不可用
        val bigCoreCluster: CpuCluster?,
        val primeCoreCluster: CpuCluster?
    )

    data class CpuCluster(
        val name: String,
        val minFreqMHz: Long,
        val maxFreqMHz: Long,
        val coreCount: Int
    )

    /**
     * 检测当前设备硬件信息。
     * 应在 IO 线程调用（读取 /proc 文件）。
     */
    fun detect(context: Context): HardwareInfo {
        val cpuInfo = readCpuInfo()
        val socModel = detectSoCModel(cpuInfo)
        val socCodeName = detectSoCCodeName(cpuInfo)
        val isSnapdragon = socModel.contains("Snapdragon", ignoreCase = true)

        val memoryInfo = getMemoryInfo(context)
        val gpuInfo = getGpuInfo()

        val cpuCores = Runtime.getRuntime().availableProcessors()
        val maxFreq = getMaxCpuFreqMHz()

        val clusters = detectCpuClusters()

        return HardwareInfo(
            isSnapdragon = isSnapdragon,
            socModel = socModel,
            socCodeName = socCodeName,
            cpuCores = cpuCores,
            cpuMaxFreqMHz = maxFreq,
            totalMemoryMB = memoryInfo.first,
            availableMemoryMB = memoryInfo.second,
            gpuRenderer = gpuInfo.first,
            gpuVersion = gpuInfo.second,
            supportsNNAPI = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
            supports16KPages = check16KPageSupport(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            thermalHeadroom = getThermalHeadroom(),
            bigCoreCluster = clusters.firstOrNull { it.name.contains("big", ignoreCase = true) || it.name.contains("gold", ignoreCase = true) },
            primeCoreCluster = clusters.firstOrNull { it.name.contains("prime", ignoreCase = true) || it.name.contains("x", ignoreCase = true) && it.coreCount <= 2 }
        )
    }

    // ── SoC 检测 ──

    private fun readCpuInfo(): String {
        return try {
            BufferedReader(FileReader("/proc/cpuinfo")).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 从 /proc/cpuinfo Hardware 字段检测 SoC 型号。
     * 骁龙芯片通常报告 "Hardware: Qualcomm Technologies, Inc SM8650"
     */
    private fun detectSoCModel(cpuInfo: String): String {
        // 尝试从 Hardware 行匹配
        val hardwareMatch = Regex("Hardware\\s*:\\s*(.+)").find(cpuInfo)
        val hardware = hardwareMatch?.groupValues?.get(1)?.trim()

        // 映射 SoC 代号到型号名
        val socCode = hardware ?: detectSoCCodeName(cpuInfo)
        return mapSoCToModel(socCode)
    }

    private fun detectSoCCodeName(cpuInfo: String): String {
        // 从 Hardware 行提取 SMXXXX 代号
        val match = Regex("SM(\\d{4})", RegexOption.IGNORE_CASE).find(cpuInfo)
            ?: Regex("Qualcomm.*?(SM\\d{4}[-A-Z]*)", RegexOption.IGNORE_CASE).find(cpuInfo)

        return match?.groupValues?.getOrNull(1)?.let { "SM$it" } ?: ""
    }

    /**
     * 将 SoC 代号映射为人类可读型号。
     * 参考 Qualcomm 官方型号命名规范。
     */
    private fun mapSoCToModel(code: String): String {
        if (code.isBlank()) return Build.HARDWARE.ifBlank { "Unknown" }

        val upper = code.uppercase()
        return when {
            // Snapdragon 8 Elite / Gen 5
            upper.contains("SM8850") -> "Snapdragon 8 Elite Gen 5"
            // Snapdragon 8 Elite
            upper.contains("SM8750") -> "Snapdragon 8 Elite"
            // Snapdragon 8 Gen 4 (renamed to 8 Elite)
            upper.contains("SM8690") -> "Snapdragon 8 Gen 4"
            // Snapdragon 8 Gen 3
            upper.contains("SM8650") -> "Snapdragon 8 Gen 3"
            // Snapdragon 8 Gen 2
            upper.contains("SM8550") -> "Snapdragon 8 Gen 2"
            // Snapdragon 8 Gen 1
            upper.contains("SM8475") -> "Snapdragon 8 Gen 1+"
            upper.contains("SM8450") -> "Snapdragon 8 Gen 1"
            // Snapdragon 8+ / 888
            upper.contains("SM8350") -> "Snapdragon 888"
            upper.contains("SM8250") -> "Snapdragon 865"
            upper.contains("SM8150") -> "Snapdragon 855"
            // Snapdragon 7 系列
            upper.contains("SM7675") -> "Snapdragon 7+ Gen 3"
            upper.contains("SM7635") -> "Snapdragon 7s Gen 3"
            upper.contains("SM7475") -> "Snapdragon 7 Gen 1+"
            upper.contains("SM7450") -> "Snapdragon 7 Gen 1"
            upper.contains("SM7350") -> "Snapdragon 780G"
            upper.contains("SM7325") -> "Snapdragon 778G"
            // Snapdragon 6 系列
            upper.contains("SM6475") -> "Snapdragon 6 Gen 3"
            upper.contains("SM6450") -> "Snapdragon 6 Gen 1"
            upper.contains("SM6375") -> "Snapdragon 695"
            // 包含 Snapdragon 或 Qualcomm 的直接返回
            upper.contains("SNAPDRAGON") -> code
            upper.contains("QUALCOMM") -> "Qualcomm $code"
            else -> code
        }
    }

    // ── 内存检测 ──

    private fun getMemoryInfo(context: Context): Pair<Long, Long> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Pair(0L, 0L)

        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val totalMB = info.totalMem / (1024 * 1024)
        val availMB = info.availMem / (1024 * 1024)
        return Pair(totalMB, availMB)
    }

    // ── GPU 检测 ──

    private fun getGpuInfo(): Pair<String, String> {
        var renderer = "Unknown"
        var version = "Unknown"
        try {
            // 在没有 EGL context 的情况下无法直接调用 GLES20
            // 通过系统属性获取
            renderer = readSystemProperty("ro.hardware.egl") + " " + readSystemProperty("ro.hardware.vulkan")
            if (renderer.isBlank()) renderer = "Unknown"
            version = readSystemProperty("ro.opengles.version")
        } catch (_: Exception) { }

        // 尝试从 /sys/devices 读取 GPU 频率
        return Pair(renderer.trim(), version)
    }

    private fun readSystemProperty(name: String): String {
        return try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // ── CPU 频率检测 ──

    /**
     * 读取 CPU 最大频率（MHz）。
     * 骁龙使用动态调频，读取 cpuinfo_max_freq 获取硬件能力上限。
     */
    private fun getMaxCpuFreqMHz(): Long {
        // 尝试读取 cpu0 的最大频率（通常所有核心共享上限或 Prime 核心最高）
        for (i in 0 until 8) {
            val freq = readCpuFreq(i, "cpuinfo_max_freq")
            if (freq > 0) return freq / 1000 // kHz → MHz
        }
        return 0L
    }

    private fun readCpuFreq(core: Int, file: String): Long {
        return try {
            val path = "/sys/devices/system/cpu/cpu$core/cpufreq/$file"
            BufferedReader(FileReader(path)).use { it.readLine().trim().toLong() }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 检测 CPU 集群（Prime/Big/Little 三族架构）。
     * 骁龙 8 系列通常为 1 Prime + 3-4 Big + 3-4 Little 的三族设计。
     */
    private fun detectCpuClusters(): List<CpuCluster> {
        val clusters = mutableListOf<CpuCluster>()

        // 读取所有核心的最大频率
        val coreFreqs = mutableMapOf<Int, Long>()
        for (i in 0 until 8) {
            val freq = readCpuFreq(i, "cpuinfo_max_freq")
            if (freq > 0) coreFreqs[i] = freq
        }

        if (coreFreqs.isEmpty()) return emptyList()

        // 按频率分组
        val freqGroups = coreFreqs.values.groupingBy { it }.eachCount()
        val sortedFreqs = freqGroups.keys.sortedDescending()

        sortedFreqs.forEachIndexed { index, freq ->
            val count = freqGroups[freq] ?: 1
            val name = when (index) {
                0 -> "prime"       // 最高频率 = Prime 核心
                1 -> "big"         // 中间频率 = Big 集群
                else -> "little"   // 最低频率 = Little 集群
            }
            clusters.add(CpuCluster(
                name = name,
                minFreqMHz = readCpuFreq(index, "cpuinfo_min_freq") / 1000,
                maxFreqMHz = freq / 1000,
                coreCount = count
            ))
        }

        return clusters
    }

    // ── 16KB 页大小检测 ──

    private fun check16KPageSupport(): Boolean {
        // Android 15+ 支持 16KB 页大小
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false

        // 检查系统是否实际配置为 16KB 页
        return try {
            val pageSize = osconf("SC_PAGE_SIZE")
            pageSize == 16384L
        } catch (_: Exception) {
            false
        }
    }

    private fun osconf(name: String): Long {
        return try {
            val cls = Class.forName("libcore.io.Os")
            val os = cls.getMethod("getDefault").invoke(null)
            cls.getMethod("sysconf", String::class.java).invoke(os, name) as Long
        } catch (_: Exception) {
            4096L
        }
    }

    // ── 热状态检测 ──

    private fun getThermalHeadroom(): Float {
        return try {
            val powerManager = Class.forName("android.os.PowerManager")
            // Thermal headroom API 在 API 29+ 可用
            -1f // 无法直接获取 PowerManager 实例，返回 -1
        } catch (_: Exception) {
            -1f
        }
    }

    // ── 能力判断 ──

    /**
     * 判断设备是否支持端侧 LLM 推理。
     * 阈值：骁龙 8 Gen 1+ / 8GB+ RAM / arm64-v8a。
     */
    fun supportsOnDeviceInference(info: HardwareInfo): Boolean {
        if (!info.isSnapdragon) return false
        if (info.abi != "arm64-v8a") return false
        if (info.totalMemoryMB < 6 * 1024) return false

        // 骁龙 8 Gen 1 及以上支持有意义的端侧推理
        val gen = extractSnapdragonGeneration(info.socModel)
        return gen >= 1 // Gen 1 = 1, Gen 2 = 2, etc.
    }

    /**
     * 提取骁龙代数（8 Gen N → N）。
     */
    private fun extractSnapdragonGeneration(model: String): Int {
        val match = Regex("Gen\\s*(\\d+)").find(model)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * 推荐的端侧推理配置。
     */
    fun recommendInferenceConfig(info: HardwareInfo): InferenceConfig {
        if (!supportsOnDeviceInference(info)) {
            return InferenceConfig(
                canRunOnDevice = false,
                recommendedModelSize = "1-3B",
                recommendedQuantization = "Q4_0",
                maxContextLength = 2048,
                useNNAPI = false,
                reason = "Device does not meet minimum requirements for on-device inference"
            )
        }

        val gen = extractSnapdragonGeneration(info.socModel)
        val ramGB = info.totalMemoryMB / 1024

        return when {
            gen >= 4 || info.socModel.contains("Elite") -> InferenceConfig(
                canRunOnDevice = true,
                recommendedModelSize = "7-13B",
                recommendedQuantization = "Q4_K_M",
                maxContextLength = 8192,
                useNNAPI = true,
                reason = "${info.socModel}: Hexagon NPU with HMX, suitable for 7-13B models"
            )
            gen >= 3 -> InferenceConfig(
                canRunOnDevice = true,
                recommendedModelSize = "7-8B",
                recommendedQuantization = "Q4_K_M",
                maxContextLength = 4096,
                useNNAPI = true,
                reason = "${info.socModel}: Hexagon NPU, can run 7-8B with Q4 quantization"
            )
            gen >= 2 -> InferenceConfig(
                canRunOnDevice = true,
                recommendedModelSize = "3-7B",
                recommendedQuantization = "Q4_K_M",
                maxContextLength = 4096,
                useNNAPI = true,
                reason = "${info.socModel}: Hexagon NPU, 3-7B models recommended"
            )
            gen >= 1 -> InferenceConfig(
                canRunOnDevice = true,
                recommendedModelSize = "1-3B",
                recommendedQuantization = "Q4_0",
                maxContextLength = 2048,
                useNNAPI = true,
                reason = "${info.socModel}: Basic Hexagon NPU, small models only"
            )
            else -> InferenceConfig(
                canRunOnDevice = ramGB >= 6,
                recommendedModelSize = "1B",
                recommendedQuantization = "Q4_0",
                maxContextLength = 1024,
                useNNAPI = false,
                reason = "Older Snapdragon, CPU-only inference with small models"
            )
        }
    }

    data class InferenceConfig(
        val canRunOnDevice: Boolean,
        val recommendedModelSize: String,
        val recommendedQuantization: String,
        val maxContextLength: Int,
        val useNNAPI: Boolean,
        val reason: String
    )
}
