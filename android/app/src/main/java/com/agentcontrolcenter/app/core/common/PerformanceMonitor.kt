package com.agentcontrolcenter.app.core.common

import android.content.Context
import android.os.Debug
import com.agentcontrolcenter.app.core.hardware.SnapdragonHardwareDetector
import com.agentcontrolcenter.app.core.hardware.SnapdragonOptimizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PerformanceMetrics(
    val avgMessageLatency: Long = 0,
    val connectionQuality: String = "Unknown",
    val memoryUsageMB: Long = 0,
    val totalMessages: Long = 0,
    val uptimeMinutes: Long = 0,
    val socModel: String = "Unknown",
    val isSnapdragon: Boolean = false,
    val npuAcceleration: Boolean = false,
    val thermalStatus: String = "Unknown",
    val cpuCores: Int = 0,
    val totalMemoryMB: Long = 0
)

object PerformanceMonitor {
    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    private val latencySamples = mutableListOf<Long>()
    private val startTime = System.currentTimeMillis()
    private var totalMessageCount = 0L
    private var hardwareInfo: SnapdragonHardwareDetector.HardwareInfo? = null

    /**
     * 初始化硬件检测。应在 Application.onCreate() 中调用。
     */
    fun initializeHardware(context: Context) {
        SnapdragonOptimizer.initialize(context)
        val info = SnapdragonHardwareDetector.detect(context)
        hardwareInfo = info

        _metrics.update {
            it.copy(
                socModel = info.socModel,
                isSnapdragon = info.isSnapdragon,
                cpuCores = info.cpuCores,
                totalMemoryMB = info.totalMemoryMB,
                npuAcceleration = SnapdragonOptimizer.getNNApiConfig()?.enableNNAPI == true
            )
        }
    }

    fun recordMessageLatency(latencyMs: Long) {
        synchronized(latencySamples) {
            latencySamples.add(latencyMs)
            if (latencySamples.size > 100) latencySamples.removeAt(0)
        }
        totalMessageCount++
        updateMetrics()
    }

    fun recordConnectionQuality(rssi: Int) {
        val quality = when {
            rssi >= -50 -> "Excellent"
            rssi >= -60 -> "Good"
            rssi >= -70 -> "Fair"
            rssi >= -80 -> "Weak"
            else -> "Poor"
        }
        _metrics.update { it.copy(connectionQuality = quality) }
    }

    fun recordConnectionLatency(latencyMs: Long) {
        val quality = when {
            latencyMs < 50 -> "Excellent"
            latencyMs < 100 -> "Good"
            latencyMs < 200 -> "Fair"
            latencyMs < 500 -> "Weak"
            else -> "Poor"
        }
        _metrics.update { it.copy(connectionQuality = quality) }
    }

    fun updateMemoryUsage(context: Context) {
        val runtime = Runtime.getRuntime()
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        // 获取热状态
        val thermal = if (hardwareInfo?.isSnapdragon == true) {
            val throttled = SnapdragonOptimizer.shouldThrottle()
            when {
                !throttled -> "Normal"
                else -> "Throttling"
            }
        } else {
            "N/A"
        }

        _metrics.update {
            it.copy(
                memoryUsageMB = usedMemoryMB,
                thermalStatus = thermal
            )
        }
    }

    private fun updateMetrics() {
        val avgLatency = synchronized(latencySamples) {
            if (latencySamples.isEmpty()) 0L
            else latencySamples.average().toLong()
        }
        val uptimeMin = (System.currentTimeMillis() - startTime) / 60_000

        _metrics.update {
            it.copy(
                avgMessageLatency = avgLatency,
                totalMessages = totalMessageCount,
                uptimeMinutes = uptimeMin
            )
        }
    }

    /** Public refresh: updates uptime, avg latency, and memory. Call periodically. */
    fun refresh(context: Context) {
        updateMemoryUsage(context)
        updateMetrics()
    }

    /**
     * 获取硬件摘要（用于设置页面显示）。
     */
    fun getHardwareSummary(): String {
        return SnapdragonOptimizer.getHardwareSummary()
    }

    /**
     * 获取端侧推理配置建议。
     */
    fun getInferenceRecommendation(): SnapdragonHardwareDetector.InferenceConfig? {
        val info = hardwareInfo ?: return null
        return SnapdragonHardwareDetector.recommendInferenceConfig(info)
    }

    fun reset() {
        synchronized(latencySamples) {
            latencySamples.clear()
        }
        totalMessageCount = 0
        _metrics.update { PerformanceMetrics() }
    }
}
