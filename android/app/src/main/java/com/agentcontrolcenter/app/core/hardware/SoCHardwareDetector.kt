package com.agentcontrolcenter.app.core.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.FileReader

/**
 * 统一 SoC 硬件检测器 — 支持全主流移动芯片厂商。
 *
 * 覆盖芯片：
 * - Qualcomm Snapdragon（Hexagon NPU + Adreno GPU）
 * - MediaTek Dimensity（APU NPU + Immortalis GPU）
 * - Samsung Exynos（NPU + Xclipse GPU）
 * - Google Tensor（TPU + Mali GPU）
 * - 其他（Intel Core / AMD Ryzen on x86）
 *
 * 检测方式：
 * - /proc/cpuinfo 读取 Hardware 字段和 SoC 代号
 * - ActivityManager 获取内存
 * - sysfs 读取 CPU 集群频率
 * - Build.SUPPORTED_ABIS 获取架构
 */
object SoCHardwareDetector {

    enum class SoCVendor(val displayName: String) {
        SNAPDRAGON("Qualcomm Snapdragon"),
        DIMENSITY("MediaTek Dimensity"),
        EXYNOS("Samsung Exynos"),
        TENSOR("Google Tensor"),
        APPLE("Apple Silicon"),
        OTHER("Unknown"),
        INTEL("Intel"),
        AMD("AMD")
    }

    enum class AIAccelerator(val displayName: String) {
        HEXAGON_NPU("Hexagon NPU (HTP/HMX)"),
        MEDIATEK_APU("MediaTek APU"),
        SAMSUNG_NPU("Samsung NPU (GNPU+SNPU)"),
        GOOGLE_TPU("Google TPU"),
        APPLE_NEURAL_ENGINE("Apple Neural Engine"),
        NONE("No dedicated AI accelerator")
    }

    data class HardwareInfo(
        val vendor: SoCVendor,
        val socModel: String,
        val socCodeName: String,
        val aiAccelerator: AIAccelerator,
        val aiTops: Float,               // AI 算力（TOPS），-1 表示未知
        val cpuCores: Int,
        val cpuMaxFreqMHz: Long,
        val totalMemoryMB: Long,
        val availableMemoryMB: Long,
        val gpuRenderer: String,
        val supportsNNAPI: Boolean,
        val supports16KPages: Boolean,
        val abi: String,
        val thermalHeadroom: Float,
        val cpuClusters: List<CpuCluster>
    ) {
        val isHighEnd: Boolean
            get() = aiTops >= 30f || (vendor == SoCVendor.SNAPDRAGON && socModel.contains("Gen 3") || socModel.contains("Elite"))
                    || (vendor == SoCVendor.DIMENSITY && socModel.contains("9400") || socModel.contains("9300"))
                    || (vendor == SoCVendor.EXYNOS && socModel.contains("2500"))
                    || (vendor == SoCVendor.APPLE && socModel.contains("A19") || socModel.contains("M5"))

        val isSnapdragon: Boolean get() = vendor == SoCVendor.SNAPDRAGON
        val isDimensity: Boolean get() = vendor == SoCVendor.DIMENSITY
        val isExynos: Boolean get() = vendor == SoCVendor.EXYNOS
        val isTensor: Boolean get() = vendor == SoCVendor.TENSOR
    }

    data class CpuCluster(
        val name: String,
        val minFreqMHz: Long,
        val maxFreqMHz: Long,
        val coreCount: Int
    )

    fun detect(context: Context): HardwareInfo {
        val cpuInfo = readCpuInfo()
        val (vendor, socModel, socCodeName) = detectSoC(cpuInfo)
        val aiAccelerator = detectAIAccelerator(vendor, socModel)
        val aiTops = estimateAITops(vendor, socModel)

        val memoryInfo = getMemoryInfo(context)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val maxFreq = getMaxCpuFreqMHz()
        val clusters = detectCpuClusters()

        return HardwareInfo(
            vendor = vendor,
            socModel = socModel,
            socCodeName = socCodeName,
            aiAccelerator = aiAccelerator,
            aiTops = aiTops,
            cpuCores = cpuCores,
            cpuMaxFreqMHz = maxFreq,
            totalMemoryMB = memoryInfo.first,
            availableMemoryMB = memoryInfo.second,
            gpuRenderer = detectGpu(vendor, cpuInfo),
            supportsNNAPI = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
            supports16KPages = check16KPageSupport(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            thermalHeadroom = -1f,
            cpuClusters = clusters
        )
    }

    // ── SoC 识别 ──

    private fun readCpuInfo(): String {
        return try {
            BufferedReader(FileReader("/proc/cpuinfo")).use { it.readText() }
        } catch (_: Exception) { "" }
    }

    private fun detectSoC(cpuInfo: String): Triple<SoCVendor, String, String> {
        val hardwareLine = Regex("Hardware\\s*:\\s*(.+)").find(cpuInfo)?.groupValues?.get(1)?.trim()
        val hardware = hardwareLine ?: Build.HARDWARE ?: ""

        // Qualcomm Snapdragon: SM8650, SM8550, etc.
        val smMatch = Regex("SM(\\d{4}[-A-Z]*)", RegexOption.IGNORE_CASE).find(hardware)
            ?: Regex("SM(\\d{4}[-A-Z]*)", RegexOption.IGNORE_CASE).find(cpuInfo)
        if (smMatch != null) {
            val code = "SM${smMatch.groupValues[1]}"
            val model = mapSnapdragonModel(code)
            return Triple(SoCVendor.SNAPDRAGON, model, code)
        }

        // MediaTek Dimensity: MT6989, MT6985, etc.
        val mtMatch = Regex("MT(\\d{4})", RegexOption.IGNORE_CASE).find(hardware)
            ?: Regex("MT(\\d{4})", RegexOption.IGNORE_CASE).find(cpuInfo)
        if (mtMatch != null) {
            val code = "MT${mtMatch.groupValues[1]}"
            val model = mapDimensityModel(code)
            return Triple(SoCVendor.DIMENSITY, model, code)
        }

        // Samsung Exynos: Samsung, Exynos, S5E...
        if (hardware.contains("samsung", ignoreCase = true) ||
            hardware.contains("exynos", ignoreCase = true) ||
            Regex("S5E\\d{4}", RegexOption.IGNORE_CASE).containsMatchIn(cpuInfo)) {
            val code = Regex("S5E(\\d{4})", RegexOption.IGNORE_CASE).find(cpuInfo)?.value
                ?: Regex("Exynos\\s*(\\d{4})", RegexOption.IGNORE_CASE).find(cpuInfo)?.value
                ?: "Exynos"
            val model = mapExynosModel(code)
            return Triple(SoCVendor.EXYNOS, model, code)
        }

        // Google Tensor: gs201, gs101, zuma, laguna, Zumapro
        if (hardware.contains("tensor", ignoreCase = true) ||
            hardware.contains("gs", ignoreCase = true) && Regex("gs\\d{3}", RegexOption.IGNORE_CASE).containsMatchIn(cpuInfo) ||
            listOf("zuma", "laguna", "zumapro", "cloudripper").any { hardware.contains(it, ignoreCase = true) }) {
            val model = mapTensorModel(hardware)
            return Triple(SoCVendor.TENSOR, model, hardware)
        }

        // Generic fallback
        return Triple(SoCVendor.OTHER, hardware.ifBlank { "Unknown SoC" }, hardware)
    }

    private fun mapSnapdragonModel(code: String): String {
        val u = code.uppercase()
        return when {
            u.contains("SM8850") -> "Snapdragon 8 Elite Gen 5"
            u.contains("SM8750") -> "Snapdragon 8 Elite"
            u.contains("SM8690") -> "Snapdragon 8 Gen 4"
            u.contains("SM8650") -> "Snapdragon 8 Gen 3"
            u.contains("SM8550") -> "Snapdragon 8 Gen 2"
            u.contains("SM8475") -> "Snapdragon 8 Gen 1+"
            u.contains("SM8450") -> "Snapdragon 8 Gen 1"
            u.contains("SM8350") -> "Snapdragon 888"
            u.contains("SM8250") -> "Snapdragon 865"
            u.contains("SM8150") -> "Snapdragon 855"
            u.contains("SM7675") -> "Snapdragon 7+ Gen 3"
            u.contains("SM7635") -> "Snapdragon 7s Gen 3"
            u.contains("SM7450") -> "Snapdragon 7 Gen 1"
            u.contains("SM7325") -> "Snapdragon 778G"
            u.contains("SM6475") -> "Snapdragon 6 Gen 3"
            u.contains("SM6450") -> "Snapdragon 6 Gen 1"
            u.contains("SM6375") -> "Snapdragon 695"
            else -> "Snapdragon ($code)"
        }
    }

    private fun mapDimensityModel(code: String): String {
        val u = code.uppercase()
        return when {
            u.contains("MT6991") -> "Dimensity 9400"
            u.contains("MT6989") || u.contains("MT6985") -> "Dimensity 9300"
            u.contains("MT6983") -> "Dimensity 9200"
            u.contains("MT6985") -> "Dimensity 8300"
            u.contains("MT6879") || u.contains("MT6875") -> "Dimensity 8200"
            u.contains("MT6877") -> "Dimensity 8100"
            u.contains("MT6885") -> "Dimensity 9200+"
            else -> "Dimensity ($code)"
        }
    }

    private fun mapExynosModel(code: String): String {
        val u = code.uppercase()
        return when {
            u.contains("S5E9955") || u.contains("EXYNOS 2500") -> "Exynos 2500"
            u.contains("S5E9945") || u.contains("EXYNOS 2400") -> "Exynos 2400"
            u.contains("S5E9925") || u.contains("EXYNOS 2200") -> "Exynos 2200"
            u.contains("S5E9915") || u.contains("EXYNOS 2100") -> "Exynos 2100"
            else -> if (u.contains("EXYNOS")) "Exynos" else "Samsung ($code)"
        }
    }

    private fun mapTensorModel(hardware: String): String {
        val h = hardware.lowercase()
        return when {
            h.contains("gs510") || h.contains("zumapro") -> "Tensor G5"
            h.contains("gs401") || h.contains("zuma") -> "Tensor G4"
            h.contains("gs201") || h.contains("laguna") -> "Tensor G3"
            h.contains("gs101") || h.contains("cloudripper") -> "Tensor G2"
            h.contains("gs100") -> "Tensor G1"
            else -> "Google Tensor"
        }
    }

    // ── AI 加速器识别 ──

    private fun detectAIAccelerator(vendor: SoCVendor, model: String): AIAccelerator {
        return when (vendor) {
            SoCVendor.SNAPDRAGON -> AIAccelerator.HEXAGON_NPU
            SoCVendor.DIMENSITY -> AIAccelerator.MEDIATEK_APU
            SoCVendor.EXYNOS -> AIAccelerator.SAMSUNG_NPU
            SoCVendor.TENSOR -> AIAccelerator.GOOGLE_TPU
            SoCVendor.APPLE -> AIAccelerator.APPLE_NEURAL_ENGINE
            else -> AIAccelerator.NONE
        }
    }

    /**
     * 估算 AI 算力（TOPS）。基于公开数据。
     */
    private fun estimateAITops(vendor: SoCVendor, model: String): Float {
        return when (vendor) {
            SoCVendor.SNAPDRAGON -> when {
                model.contains("Elite Gen 5") -> 80f
                model.contains("8 Elite") -> 73f
                model.contains("Gen 4") -> 65f
                model.contains("Gen 3") -> 45f
                model.contains("Gen 2") -> 35f
                model.contains("Gen 1") -> 26f
                model.contains("888") -> 26f
                else -> 20f
            }
            SoCVendor.DIMENSITY -> when {
                model.contains("9400") -> 80f
                model.contains("9300") -> 48f
                model.contains("9200") -> 35f
                model.contains("8300") -> 30f
                else -> 20f
            }
            SoCVendor.EXYNOS -> when {
                model.contains("2500") -> 59f
                model.contains("2400") -> 45f
                model.contains("2200") -> 30f
                else -> 20f
            }
            SoCVendor.TENSOR -> when {
                model.contains("G5") -> 49f
                model.contains("G4") -> 36f
                model.contains("G3") -> 30f
                else -> 25f
            }
            else -> 0f
        }
    }

    // ── 内存/GPU/CPU 检测（共用） ──

    private fun getMemoryInfo(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Pair(0L, 0L)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return Pair(info.totalMem / (1024 * 1024), info.availMem / (1024 * 1024))
    }

    private fun detectGpu(vendor: SoCVendor, cpuInfo: String): String {
        return when (vendor) {
            SoCVendor.SNAPDRAGON -> {
                val model = cpuInfo.substringAfter("Snapdragon", "")
                when {
                    model.contains("Elite") -> "Adreno 830"
                    model.contains("Gen 3") -> "Adreno 750"
                    model.contains("Gen 2") -> "Adreno 740"
                    model.contains("Gen 1") -> "Adreno 730"
                    else -> "Adreno"
                }
            }
            SoCVendor.DIMENSITY -> {
                val model = cpuInfo.substringAfter("Dimensity", "")
                when {
                    model.contains("9400") -> "Immortalis-G925"
                    model.contains("9300") -> "Immortalis-G720"
                    model.contains("9200") -> "Immortalis-G715"
                    else -> "Mali-GPU"
                }
            }
            SoCVendor.EXYNOS -> {
                val model = cpuInfo.substringAfter("Exynos", "")
                when {
                    model.contains("2500") -> "Xclipse 950"
                    model.contains("2400") -> "Xclipse 940"
                    else -> "Xclipse GPU"
                }
            }
            SoCVendor.TENSOR -> "Mali-G715"
            else -> "Unknown GPU"
        }
    }

    private fun getMaxCpuFreqMHz(): Long {
        for (i in 0 until 8) {
            val freq = readCpuFreq(i, "cpuinfo_max_freq")
            if (freq > 0) return freq / 1000
        }
        return 0L
    }

    private fun readCpuFreq(core: Int, file: String): Long {
        return try {
            BufferedReader(FileReader("/sys/devices/system/cpu/cpu$core/cpufreq/$file")).use {
                it.readLine().trim().toLong()
            }
        } catch (_: Exception) { 0L }
    }

    private fun detectCpuClusters(): List<CpuCluster> {
        val coreFreqs = mutableMapOf<Int, Long>()
        for (i in 0 until 8) {
            val freq = readCpuFreq(i, "cpuinfo_max_freq")
            if (freq > 0) coreFreqs[i] = freq
        }
        if (coreFreqs.isEmpty()) return emptyList()

        val clusters = mutableListOf<CpuCluster>()
        val freqGroups = coreFreqs.values.groupingBy { it }.eachCount()
        val sortedFreqs = freqGroups.keys.sortedDescending()

        sortedFreqs.forEachIndexed { index, freq ->
            val name = when (index) {
                0 -> "prime"
                1 -> "big"
                else -> "little"
            }
            clusters.add(CpuCluster(
                name = name,
                minFreqMHz = readCpuFreq(index, "cpuinfo_min_freq") / 1000,
                maxFreqMHz = freq / 1000,
                coreCount = freqGroups[freq] ?: 1
            ))
        }
        return clusters
    }

    private fun check16KPageSupport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        return try {
            val cls = Class.forName("libcore.io.Os")
            val os = cls.getMethod("getDefault").invoke(null)
            // F34 修复：原 `as Long` 在反射返回 null 或非 Long 类型时会抛 ClassCastException
            // （虽然被外层 catch 兜底，但语义错误，且某些 OEM 实现可能返回 Int）。
            // 改为 `as? Long`，转型失败返回 false。
            val pageSize = cls.getMethod("sysconf", String::class.java).invoke(os, "SC_PAGE_SIZE") as? Long
            pageSize == 16384L
        } catch (_: Exception) { false }
    }

    // ── 推理配置推荐 ──

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

        val tops = info.aiTops
        val ramGB = info.totalMemoryMB / 1024

        return when {
            tops >= 70f -> InferenceConfig(
                canRunOnDevice = true,
                recommendedModelSize = "7-14B",
                recommendedQuantization = "Q4_K_M",
                maxContextLength = 8192,
                useNNAPI = true,
                reason = "${info.socModel}: ${info.aiAccelerator.displayName} ($tops TOPS), suitable for 7-14B models"
            )
            tops >= 40f -> InferenceConfig(
                canRunOnDevice = true,
                recommendedModelSize = "7-8B",
                recommendedQuantization = "Q4_K_M",
                maxContextLength = 4096,
                useNNAPI = true,
                reason = "${info.socModel}: ${info.aiAccelerator.displayName} ($tops TOPS), 7-8B with Q4 quantization"
            )
            tops >= 25f -> InferenceConfig(
                canRunOnDevice = true,
                recommendedModelSize = "3-7B",
                recommendedQuantization = "Q4_K_M",
                maxContextLength = 4096,
                useNNAPI = true,
                reason = "${info.socModel}: ${info.aiAccelerator.displayName} ($tops TOPS), 3-7B models"
            )
            else -> InferenceConfig(
                canRunOnDevice = ramGB >= 6,
                recommendedModelSize = "1-3B",
                recommendedQuantization = "Q4_0",
                maxContextLength = 2048,
                useNNAPI = true,
                reason = "${info.socModel}: Basic AI acceleration ($tops TOPS), small models only"
            )
        }
    }

    fun supportsOnDeviceInference(info: HardwareInfo): Boolean {
        if (info.abi != "arm64-v8a" && info.vendor != SoCVendor.APPLE) return false
        if (info.totalMemoryMB < 6 * 1024) return false
        return info.aiTops >= 20f || info.vendor != SoCVendor.OTHER
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
