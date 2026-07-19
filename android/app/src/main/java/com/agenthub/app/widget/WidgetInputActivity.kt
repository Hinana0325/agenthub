package com.agenthub.app.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.agenthub.app.R

/**
 * WidgetInputActivity — Sprint 8 Widget 快捷输入弹窗
 *
 * 背景：在 v2.1.1 热修复中，由于 RemoteViews 不支持 EditText，
 * Widget 输入区被降级为 TextView。这导致用户无法直接在 Widget 上输入文字，
 * 只能点击跳转到主界面。Sprint 8 引入本 Activity 作为轻量级输入入口，
 * 重新打通"在桌面 Widget 上快速发送一条消息"的体验。
 *
 * 交互流程：
 *   1. 用户点击 Widget 的输入区 TextView（widget_input）
 *   2. 本 Activity 以对话框样式弹出，内含真实 EditText + Send / Cancel
 *   3. 用户输入文本后点击 Send
 *   4. 本 Activity 通过 Broadcast Intent（[ACTION_WIDGET_SEND_MESSAGE]）
 *      把消息推送给 [com.agenthub.app.feature.chat.ChatViewModel]，
 *      由其调用 ChatRepository + AgentTransport 完成真实发送
 *   5. 本 Activity 调用 finish() 关闭，Widget 随后刷新显示最新消息
 *
 * 实现说明：
 *  - 采用纯代码构建 UI（无 Compose / 无 layout XML），以最小化冷启动延迟，
 *    因为该 Activity 直接从桌面 Widget 通过 PendingIntent 拉起。
 *  - 不使用 Hilt @AndroidEntryPoint：本 Activity 不直接持有任何依赖，
 *    发送逻辑通过 Broadcast 解耦到 ChatViewModel，避免在弹窗里注入
 *    长生命周期的 transport / repository。
 *
 * 架构约束（需注意）：
 *  ChatViewModel 的 BroadcastReceiver 仅在 ChatViewModel 存活时生效，
 *  即 App 处于前台或进程未被系统杀死时。若 App 已被冷启动回收，
 *  该 Broadcast 将丢失。后续可通过 WidgetDataProvider SharedPreferences
 *  作为补偿通道，在 ChatViewModel 初始化时消费 pending 消息。
 */
class WidgetInputActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前请求无标题栏
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        // 对话框尺寸：宽 ~300dp，高 wrap_content，居中
        val density = resources.displayMetrics.density
        val widthPx = (WIDGET_INPUT_WIDTH_DP * density).toInt()
        window.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)

        setContentView(buildUi())
    }

    /**
     * 以编程方式构建 UI：
     *   ┌──────────────────────────┐
     *   │ Send to Agent             │  ← 标题
     *   │ ┌──────────────────────┐ │
     *   │ │ Type a message…      │ │  ← EditText (2-4 行)
     *   │ └──────────────────────┘ │
     *   │              [Cancel][Send] │  ← 按钮行
     *   └──────────────────────────┘
     */
    private fun buildUi(): ViewGroup {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        // 标题
        val title = TextView(this).apply {
            text = getString(R.string.widget_input_dialog_title)
            textSize = 15f
            setTextColor(0xFF1A1D2E.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(title)

        // 输入框
        val input = EditText(this).apply {
            hint = getString(R.string.widget_input_edit_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 4
            textSize = 14f
            setTextColor(0xFF1A1D2E.toInt())
            setHintTextColor(0xFFA0A4B8.toInt())
            requestFocus()
        }
        root.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 按钮行（右对齐：Cancel | Send）
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }

        val cancelBtn = Button(this).apply {
            text = getString(R.string.widget_input_cancel)
            minHeight = 0
            minWidth = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        }
        buttonRow.addView(cancelBtn)

        val sendBtn = Button(this).apply {
            text = getString(R.string.widget_input_send)
            minHeight = 0
            minWidth = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    sendWidgetMessage(text)
                }
                finish()
            }
        }
        buttonRow.addView(sendBtn)

        root.addView(buttonRow)
        return root
    }

    /**
     * 发送广播给 ChatViewModel。
     *  - setPackage 限定在应用包内，避免泄露到其他应用
     *  - 接收端用 RECEIVER_NOT_EXPORTED 注册，仅接收同应用发出的广播
     *
     * Critical 3 修复：除发广播外，额外通过 [WidgetDataProvider.setPendingInput]
     * 持久化待发送消息。当 App 进程被冷启动回收、ChatViewModel 的广播接收器
     * 尚未注册时，广播会丢失；持久化的消息会在 ChatViewModel 初始化时通过
     * [WidgetDataProvider.consumePendingInput] 被消费并发送，作为补偿通道。
     */
    private fun sendWidgetMessage(text: String) {
        // 持久化待发送消息，确保冷启动时消息不丢失
        WidgetDataProvider.setPendingInput(this, text)

        // 同时发送广播，App 在前台时 ChatViewModel 可即时接收
        val intent = Intent(ACTION_WIDGET_SEND_MESSAGE).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE, text)
        }
        sendBroadcast(intent)
    }

    companion object {
        /**
         * WidgetInputActivity 发出的广播 action。
         * ChatViewModel 在 init 时注册监听该 action。
         */
        const val ACTION_WIDGET_SEND_MESSAGE = "com.agenthub.app.WIDGET_SEND_MESSAGE"

        /** Intent extra key：携带用户输入的文本。 */
        const val EXTRA_MESSAGE = "message"

        /** 对话框宽度（dp），高度为 wrap_content。 */
        private const val WIDGET_INPUT_WIDTH_DP = 300
    }
}
