package com.agentcontrolcenter.app

import android.app.Application
import com.agentcontrolcenter.app.core.common.PerformanceMonitor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class AgentControlCenterApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 骁龙硬件检测和优化初始化（在 IO 线程，避免阻塞启动）
        appScope.launch {
            PerformanceMonitor.initializeHardware(this@AgentControlCenterApplication)
        }
    }
}
