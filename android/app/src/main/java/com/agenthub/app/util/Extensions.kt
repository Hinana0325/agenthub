package com.agenthub.app.util

import android.content.Context
import android.content.Intent
import android.os.Build

/** Android 安全启动 Service */
fun Context.startServiceSafe(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
}
