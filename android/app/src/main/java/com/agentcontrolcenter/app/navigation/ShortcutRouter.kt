package com.agentcontrolcenter.app.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P3-5: Shortcut 路由桥接器。
 *
 * 作用：在 Android 侧，[com.agentcontrolcenter.app.MainActivity]（命令式 Activity 层）
 * 与 Compose 导航图（[AppNavigation]）之间架起一座状态桥梁，使 Launcher 快捷方式
 * 携带的 `shortcut_action` extra 能被 NavController 感知并消费。
 *
 * 背景：Compose 的 [androidx.navigation.NavHostController] 只能从 Composable 内部访问，
 * 而 MainActivity 在 `onCreate` / `onNewIntent` 中收到 Intent 时 NavController 尚未
 * （或不再）处于可直接调用的作用域。因此通过一个进程内单例 [StateFlow] 暂存待处理的
 * 快捷方式动作，由 [AppNavigation] 中的 `LaunchedEffect` 观察并执行实际导航。
 *
 * 使用流程：
 * 1. MainActivity 读取 `intent.getStringExtra("shortcut_action")`
 * 2. 调用 [route] 写入待处理动作
 * 3. AppNavigation 观察待处理动作并导航
 * 4. 导航完成后调用 [consume] 清空，避免重复处理
 *
 * 线程安全：[MutableStateFlow] 本身线程安全，可在任意线程写入；Compose 端通过
 * `collectAsStateWithLifecycle` 在主线程安全消费。
 */
object ShortcutRouter {

    /**
     * 快捷方式动作标识 — 与 `res/xml/shortcuts.xml` 中 `<extra android:value>` 一一对应。
     *
     * @property action 原始字符串值，用于在 [StateFlow] 中传递
     */
    enum class Action(val action: String) {
        /** 新建聊天 — 导航到 Chat 页面 */
        NEW_CHAT("new_chat"),

        /** 新建 Agent — 导航到 Agents 页面 */
        NEW_AGENT("new_agent"),

        /** 设置 — 导航到 Settings 页面 */
        SETTINGS("settings");

        companion object {
            /**
             * 将原始字符串解析为 [Action]；无法识别时返回 null。
             *
             * @param raw Intent extra 中的原始字符串
             * @return 对应的 [Action]，若未匹配则 null
             */
            fun fromRaw(raw: String?): Action? = raw?.let { value ->
                entries.firstOrNull { it.action == value }
            }
        }
    }

    private val _pendingAction = MutableStateFlow<Action?>(null)

    /**
     * 待处理的快捷方式动作。
     *
     * 非 null 时表示存在尚未被 Compose 导航消费的快捷方式请求；
     * 消费后应通过 [consume] 置回 null。
     */
    val pendingAction: StateFlow<Action?> = _pendingAction.asStateFlow()

    /**
     * 提交一个待处理的快捷方式动作。
     *
     * @param action 由 MainActivity 从 Intent extra 中解析出的动作
     */
    fun route(action: Action) {
        _pendingAction.value = action
    }

    /**
     * 清空待处理动作。
     *
     * 由 [AppNavigation] 在完成导航后调用，确保同一动作不会触发多次跳转。
     */
    fun consume() {
        _pendingAction.value = null
    }
}
