package com.agenthub.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.agenthub.app.ui.theme.GlassBackdropGradientBottomDark
import com.agenthub.app.ui.theme.GlassBackdropGradientBottomLight
import com.agenthub.app.ui.theme.GlassBackdropGradientTopDark
import com.agenthub.app.ui.theme.GlassBackdropGradientTopLight
import com.agenthub.app.ui.theme.LocalIsGlass
import com.agenthub.app.navigation.AppNavigation
import com.agenthub.app.ui.settings.SettingsViewModel
import com.agenthub.app.ui.theme.AgentHubTheme
import com.agenthub.app.ui.theme.ThemeMode
import com.agenthub.app.runtime.notification.StatusNotificationManager
import com.agenthub.app.runtime.notification.LocalNotificationManager
import dagger.hilt.android.AndroidEntryPoint

/**
 * AgentHub MainActivity — Compose 原生入口
 *
 * - Jetpack Compose UI (ComponentActivity + setContent)
 * - Edge-to-edge 全面屏
 * - 启动 Foreground Service 后台保活
 * - Android Share Sheet 处理
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var shareHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 安装 Splash Screen（必须在 super.onCreate 之前）
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

            // 保持闪屏至少 800ms，避免闪烁
        val startTime = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            System.currentTimeMillis() - startTime < 800
        }

        enableEdgeToEdge()

        // 强制全屏：清除窗口背景色，防止黑边
        window.setBackgroundDrawable(null)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)

        // 允许内容延伸到刘海/挖孔区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // 确保内容绘制到系统栏后面
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settingsState by settingsViewModel.uiState.collectAsState()
            val themeMode = when (settingsState.themeMode) {
                "light" -> ThemeMode.Light
                "dark" -> ThemeMode.Dark
                else -> ThemeMode.System
            }
            AgentHubTheme(
                themeMode = themeMode,
                fontSize = settingsState.fontSize
            ) {
                val isGlass = LocalIsGlass.current
                val isDark = isSystemInDarkTheme()
                SideEffect {
                    updateWindowBackground(isGlass, isDark)
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }

            // Handle share intent via ChatViewModel
            val chatViewModel: com.agenthub.app.ui.chat.ChatViewModel = hiltViewModel()
            LaunchedEffect(intent) {
                handleShareIntent(intent, chatViewModel)
            }
        }

        // 启动前台服务保持后台连接
        startForegroundService()

        // 处理通知回复
        handleReplyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareHandled = false
        // Share will be handled by LaunchedEffect in setContent
        handleReplyIntent(intent)
    }

    private fun startForegroundService() {
        AgentConnectionService.start(this, "AgentHub")
    }

    private fun updateWindowBackground(isGlass: Boolean, isDark: Boolean) {
        if (isGlass) {
            val topColor = if (isDark) GlassBackdropGradientTopDark.toArgb() else GlassBackdropGradientTopLight.toArgb()
            val bottomColor = if (isDark) GlassBackdropGradientBottomDark.toArgb() else GlassBackdropGradientBottomLight.toArgb()
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(topColor, bottomColor)
            )
            window.decorView.background = gradient
        } else {
            window.decorView.background = null
            window.setBackgroundDrawable(null)
        }
    }

    /** 处理通知内联回复 */
    private fun handleReplyIntent(intent: Intent?) {
        val replyText = intent?.getStringExtra(AgentConnectionService.EXTRA_REPLY_TEXT)
        if (!replyText.isNullOrBlank()) {
            // Reply text will be consumed by ChatViewModel via intent extra
            // Clear the extra to avoid re-processing
            intent?.removeExtra(AgentConnectionService.EXTRA_REPLY_TEXT)
        }
    }

    private fun handleShareIntent(
        intent: Intent?,
        chatViewModel: com.agenthub.app.ui.chat.ChatViewModel
    ) {
        if (shareHandled) return
        if (intent == null ||
            (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)
        ) return

        shareHandled = true
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        if (!sharedText.isNullOrEmpty()) {
            chatViewModel.handleSharedText(sharedText)
        }
    }
}
