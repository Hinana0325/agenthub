package com.agenthub.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PerformanceMetrics(
    val avgMessageLatency: Long = 0,
    val connectionQuality: String = "Unknown",
    val memoryUsageMB: Long = 0,
    val totalMessages: Long = 0,
    val uptimeMinutes: Long = 0
)

object PerformanceMonitor {
    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    private val latencySamples = mutableListOf<Long>()
    private val startTime = System.currentTimeMillis()
    private var totalMessageCount = 0L

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
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val runtime = Runtime.getRuntime()
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        _metrics.update { it.copy(memoryUsageMB = usedMemoryMB) }
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

    fun reset() {
        synchronized(latencySamples) {
            latencySamples.clear()
        }
        totalMessageCount = 0
        _metrics.update { PerformanceMetrics() }
    }
}
