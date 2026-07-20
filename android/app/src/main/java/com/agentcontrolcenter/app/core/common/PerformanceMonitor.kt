package com.agentcontrolcenter.app.core.common

import android.content.Context
import com.agentcontrolcenter.app.core.hardware.SoCHardwareDetector
import com.agentcontrolcenter.app.core.hardware.SoCOptimizer
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
    val socVendor: String = "Unknown",
    val aiAccelerator: String = "Unknown",
    val aiTops: Float = 0f,
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
    private var hardwareInfo: SoCHardwareDetector.HardwareInfo? = null

    fun initializeHardware(context: Context) {
        SoCOptimizer.initialize(context)
        val info = SoCHardwareDetector.detect(context)
        hardwareInfo = info

        _metrics.update {
            it.copy(
                socModel = info.socModel,
                socVendor = info.vendor.displayName,
                aiAccelerator = info.aiAccelerator.displayName,
                aiTops = info.aiTops,
                cpuCores = info.cpuCores,
                totalMemoryMB = info.totalMemoryMB,
                npuAcceleration = SoCOptimizer.config.value.nnApiAccelerationEnabled
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

        val thermal = if (hardwareInfo?.vendor != SoCHardwareDetector.SoCVendor.OTHER) {
            if (SoCOptimizer.shouldThrottle()) "Throttling" else "Normal"
        } else {
            "N/A"
        }

        _metrics.update {
            it.copy(memoryUsageMB = usedMemoryMB, thermalStatus = thermal)
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

    fun refresh(context: Context) {
        updateMemoryUsage(context)
        updateMetrics()
    }

    fun getHardwareSummary(): String = SoCOptimizer.getHardwareSummary()

    fun getInferenceRecommendation(): SoCHardwareDetector.InferenceConfig? {
        val info = hardwareInfo ?: return null
        return SoCHardwareDetector.recommendInferenceConfig(info)
    }

    fun reset() {
        synchronized(latencySamples) { latencySamples.clear() }
        totalMessageCount = 0
        _metrics.update { PerformanceMetrics() }
    }
}
