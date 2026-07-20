package com.agentcontrolcenter.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentcontrolcenter.app.ui.theme.GlassBackdropGradientBottomDark
import com.agentcontrolcenter.app.ui.theme.GlassBackdropGradientBottomLight
import com.agentcontrolcenter.app.ui.theme.GlassBackdropGradientTopDark
import com.agentcontrolcenter.app.ui.theme.GlassBackdropGradientTopLight
import com.agentcontrolcenter.app.ui.theme.LocalIsGlass
import com.agentcontrolcenter.app.navigation.AppNavigation
import com.agentcontrolcenter.app.feature.onboarding.OnboardingScreen
import com.agentcontrolcenter.app.feature.settings.SettingsViewModel
import com.agentcontrolcenter.app.ui.theme.AgentControlCenterTheme
import com.agentcontrolcenter.app.ui.theme.ThemeMode
import com.agentcontrolcenter.app.core.datastore.SettingsDataStore
import com.agentcontrolcenter.app.runtime.notification.StatusNotificationManager
import com.agentcontrolcenter.app.runtime.notification.LocalNotificationManager
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Agent Control Center MainActivity — Compose 原生入口
 *
 * - Jetpack Compose UI (ComponentActivity + setContent)
 * - Edge-to-edge 全面屏
 * - 启动 Foreground Service 后台保活
 * - Android Share Sheet 处理
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var shareHandled = false

    /**
     * Critical 1 修复：通知内联回复的待处理文本。
     *
     * 使用 [mutableStateOf] 以便在 onCreate/onNewIntent 中赋值后，
     * Compose 侧的 LaunchedEffect 能感知到变化并触发消费，
     * 保证回复文本被转发到 ChatViewModel 发送（而非被永久丢弃）。
     */
    private var pendingReplyText by mutableStateOf<String?>(null)

    /**
     * 通知权限请求 launcher。
     *
     * Android 13+ (API 33) 需要 POST_NOTIFICATIONS 运行时权限。
     * 此 launcher 在 onCreate 中注册，首次启动时自动触发请求。
     * 用户拒绝后不重复打扰；通知发送时 LocalNotificationManager 会静默跳过。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 无论授予还是拒绝都不做额外处理。
        // 授予 → LocalNotificationManager.notify() 正常工作
        // 拒绝 → LocalNotificationManager.notify() 静默跳过，不崩溃
    }

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
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val themeMode = when (settingsState.themeMode) {
                "light" -> ThemeMode.Light
                "dark" -> ThemeMode.Dark
                else -> ThemeMode.System
            }

            // Onboarding：首次启动时显示引导页面
            val onboardingCompleted by settingsViewModel.onboardingCompleted.collectAsStateWithLifecycle(initialValue = true)

            AgentControlCenterTheme(
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
                    if (!onboardingCompleted) {
                        OnboardingScreen(
                            onComplete = {
                                settingsViewModel.markOnboardingCompleted()
                            }
                        )
                    } else {
                        AppNavigation()
                    }
                }
            }

            // Handle share intent via ChatViewModel (only when onboarding is done)
            if (onboardingCompleted) {
                val chatViewModel: com.agentcontrolcenter.app.feature.chat.ChatViewModel = hiltViewModel()
                LaunchedEffect(intent) {
                    handleShareIntent(intent, chatViewModel)
                }

                // Critical 1 修复：消费通知内联回复文本，转发给 ChatViewModel 发送。
                // pendingReplyText 由 handleReplyIntent 在 onCreate/onNewIntent 中写入。
                LaunchedEffect(pendingReplyText) {
                    val replyText = pendingReplyText ?: return@LaunchedEffect
                    chatViewModel.handleNotificationReply(replyText)
                    pendingReplyText = null
                }
            }
        }

        // 启动前台服务保持后台连接
        startForegroundService()

        // Android 13+ 主动请求通知权限（POST_NOTIFICATIONS）。
        // 此前仅声明权限但从未请求，导致通知在 Android 13+ 上被静默丢弃。
        requestNotificationPermission()

        // 处理通知回复
        handleReplyIntent(intent)
    }

    /**
     * 请求 POST_NOTIFICATIONS 运行时权限（Android 13+）。
     *
     * 仅在以下条件全部满足时请求：
     * - 设备运行 Android 13 (API 33) 或更高
     * - 当前尚未授予该权限
     * 用户拒绝后不重复打扰。
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareHandled = false
        // Share will be handled by LaunchedEffect in setContent
        handleReplyIntent(intent)
    }

    private fun startForegroundService() {
        AgentConnectionService.start(this, "Agent Control Center")
    }

    /**
     * 更新窗口背景。
     *
     * Glass 模式下，背景视觉由 Compose 端的 [GlassBackdrop] Composable 统一绘制
     * （渐变 + 动态径向光晕）。这里只给 decorView 设一个与 GlassBackdrop 底色一致的
     * 纯色兜底，避免闪屏/转场时露出黑色，同时避免与 GlassBackdrop 的渐变叠加导致浑浊。
     */
    private fun updateWindowBackground(isGlass: Boolean, isDark: Boolean) {
        if (isGlass) {
            // 纯色兜底（取 GlassBackdrop 渐变的底色），不叠加渐变 —— 渐变由 GlassBackdrop 绘制
            val fallbackColor = if (isDark) GlassBackdropGradientBottomDark else GlassBackdropGradientBottomLight
            window.decorView.setBackgroundColor(fallbackColor.toArgb())
        } else {
            window.decorView.background = null
            window.setBackgroundDrawable(null)
        }
    }

    /**
     * 处理通知内联回复。
     *
     * Critical 1 修复：此前本方法仅读取 EXTRA_REPLY_TEXT 后立即 removeExtra，
     * 而 ChatViewModel 从未读取该文本，导致回复被永久丢弃。
     *
     * 现改为：将回复文本暂存到 Compose 可观察的 [pendingReplyText] State，
     * 由 setContent 中的 LaunchedEffect 转发给 [com.agentcontrolcenter.app.feature.chat.ChatViewModel.handleNotificationReply]，
     * 随后清除 extra 以避免重复处理。
     */
    private fun handleReplyIntent(intent: Intent?) {
        val replyText = intent?.getStringExtra(AgentConnectionService.EXTRA_REPLY_TEXT)
        if (!replyText.isNullOrBlank()) {
            pendingReplyText = replyText
            intent?.removeExtra(AgentConnectionService.EXTRA_REPLY_TEXT)
        }
    }

    private fun handleShareIntent(
        intent: Intent?,
        chatViewModel: com.agentcontrolcenter.app.feature.chat.ChatViewModel
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
