package com.agentcontrolcenter.app

import android.app.Application
import com.agentcontrolcenter.app.core.common.PerformanceMonitor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class AgentControlCenterApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Install local crash logger (chains with Sentry's handler).
        // Sentry auto-initializes via manifest meta-data before Application.onCreate,
        // so getDefaultUncaughtExceptionHandler() here returns Sentry's handler.
        // Our logger writes to a local file first, then delegates to Sentry.
        installLocalCrashLogger()

        // 骁龙硬件检测和优化初始化（在 IO 线程，避免阻塞启动）
        appScope.launch {
            PerformanceMonitor.initializeHardware(this@AgentControlCenterApplication)
        }
    }

    /**
     * 安装本地崩溃日志器 — 作为 Sentry 的二级安全保障。
     *
     * Sentry 通过 manifest auto-init 已安装其 UncaughtExceptionHandler。
     * 此方法在 Sentry 的 handler 之前插入本地文件日志，形成链式调用：
     *   1. 本地写入 crash_*.log（即使 Sentry DSN 未配置也能保留崩溃记录）
     *   2. 委托给 Sentry handler 上报到云端
     *   3. 委托给默认 handler 终止进程
     */
    private fun installLocalCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashDir = File(filesDir, "crashes").apply { mkdirs() }
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val crashFile = File(crashDir, "crash_$timestamp.log")
                crashFile.writeText(buildString {
                    append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}\n")
                    append("Thread: ${thread.name}\n")
                    append("Exception: ${throwable.javaClass.name}\n")
                    append("Message: ${throwable.message}\n")
                    append("Stack:\n")
                    append(throwable.stackTraceToString())
                })
            } catch (_: Exception) {
                // Best-effort logging; never swallow the original exception
            }
            // Delegate to previous handler (Sentry's, then default)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
