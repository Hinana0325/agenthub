package com.agenthub.app.data.notification

import com.agenthub.app.data.model.Message
import com.agenthub.app.data.model.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 智能通知 — 基于优先级过滤 Agent 消息
 *
 * 规则：
 * - 高优先级：立即通知（错误、警告、需要用户操作）
 * - 中优先级：静默通知（普通回复）
 * - 低优先级：不通知（状态更新、心跳）
 */
class SmartNotificationManager {

    enum class Priority { HIGH, MEDIUM, LOW }

    data class NotificationRule(
        val name: String,
        val pattern: Regex,
        val priority: Priority,
        val isEnabled: Boolean = true
    )

    data class NotificationConfig(
        val highPriorityEnabled: Boolean = true,
        val mediumPriorityEnabled: Boolean = true,
        val lowPriorityEnabled: Boolean = false,
        val quietHoursEnabled: Boolean = false,
        val quietHoursStart: Int = 23,
        val quietHoursEnd: Int = 8
    )

    private val rules = mutableListOf(
        // High priority rules
        NotificationRule(
            name = "error",
            pattern = Regex("(?i)(error|exception|failed|crash|fatal)", RegexOption.IGNORE_CASE),
            priority = Priority.HIGH
        ),
        NotificationRule(
            name = "warning",
            pattern = Regex("(?i)(warning|warn|caution|attention)", RegexOption.IGNORE_CASE),
            priority = Priority.HIGH
        ),
        NotificationRule(
            name = "action_required",
            pattern = Regex("(?i)(please|need|require|must|urgent|asap)", RegexOption.IGNORE_CASE),
            priority = Priority.HIGH
        ),
        // Low priority rules
        NotificationRule(
            name = "heartbeat",
            pattern = Regex("(?i)(heartbeat|ping|alive|status update)", RegexOption.IGNORE_CASE),
            priority = Priority.LOW
        ),
        NotificationRule(
            name = "routine",
            pattern = Regex("(?i)(ok|done|finished|completed|acknowledged)", RegexOption.IGNORE_CASE),
            priority = Priority.LOW
        )
    )

    private val _config = MutableStateFlow(NotificationConfig())
    val config: StateFlow<NotificationConfig> = _config

    private val _rules = MutableStateFlow(rules.toList())
    val notificationRules: StateFlow<List<NotificationRule>> = _rules

    /**
     * 判断消息应该以什么优先级通知
     */
    fun shouldNotify(message: Message): Priority {
        // System messages are always low priority
        if (message.role == MessageRole.System) return Priority.LOW

        // User messages don't need notification
        if (message.role == MessageRole.User) return Priority.LOW

        // Check rules
        for (rule in rules) {
            if (rule.isEnabled && rule.pattern.containsMatchIn(message.content)) {
                return rule.priority
            }
        }

        // Default: assistant messages are MEDIUM
        return if (message.role == MessageRole.Assistant) Priority.MEDIUM else Priority.LOW
    }

    /**
     * 检查是否应该发送通知（考虑配置）
     */
    fun shouldShowNotification(message: Message): Boolean {
        val priority = shouldNotify(message)
        val currentConfig = _config.value

        return when (priority) {
            Priority.HIGH -> currentConfig.highPriorityEnabled
            Priority.MEDIUM -> currentConfig.mediumPriorityEnabled
            Priority.LOW -> currentConfig.lowPriorityEnabled
        }
    }

    /**
     * 获取通知标题
     */
    fun getNotificationTitle(message: Message): String {
        return when (shouldNotify(message)) {
            Priority.HIGH -> "\u26A0\uFE0F Important"
            Priority.MEDIUM -> "AgentHub"
            Priority.LOW -> "AgentHub"
        }
    }

    /**
     * 添加自定义规则
     */
    fun addRule(rule: NotificationRule) {
        rules.add(rule)
        _rules.value = rules.toList()
    }

    /**
     * 移除规则
     */
    fun removeRule(name: String) {
        rules.removeAll { it.name == name }
        _rules.value = rules.toList()
    }

    /**
     * 更新规则启用状态
     */
    fun toggleRule(name: String) {
        val index = rules.indexOfFirst { it.name == name }
        if (index >= 0) {
            rules[index] = rules[index].copy(isEnabled = !rules[index].isEnabled)
            _rules.value = rules.toList()
        }
    }

    /**
     * 更新配置
     */
    fun updateConfig(config: NotificationConfig) {
        _config.value = config
    }

    /**
     * 切换高优先级通知
     */
    fun toggleHighPriority(enabled: Boolean) {
        _config.value = _config.value.copy(highPriorityEnabled = enabled)
    }

    /**
     * 切换中优先级通知
     */
    fun toggleMediumPriority(enabled: Boolean) {
        _config.value = _config.value.copy(mediumPriorityEnabled = enabled)
    }

    /**
     * 切换低优先级通知
     */
    fun toggleLowPriority(enabled: Boolean) {
        _config.value = _config.value.copy(lowPriorityEnabled = enabled)
    }

    /**
     * 切换免打扰
     */
    fun toggleQuietHours(enabled: Boolean) {
        _config.value = _config.value.copy(quietHoursEnabled = enabled)
    }
}
