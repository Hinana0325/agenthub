package com.agentcontrolcenter.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * 无障碍语义辅助修饰符。
 *
 * 为组件添加 TalkBack 友好的语义信息：
 * - role: 组件角色（Button/Switch 等）
 * - stateDescription: 当前状态描述
 * - contentDescription: 内容描述
 */

/** 为按钮添加无障碍语义。 */
fun Modifier.accessibleButton(
    label: String,
    state: String? = null
): Modifier = this.semantics {
    role = Role.Button
    contentDescription = label
    if (state != null) {
        stateDescription = state
    }
}

/** 为开关添加无障碍语义。 */
fun Modifier.accessibleSwitch(
    label: String,
    isOn: Boolean
): Modifier = this.semantics {
    role = Role.Switch
    contentDescription = label
    stateDescription = if (isOn) "已开启" else "已关闭"
}

/** 为图片添加无障碍语义。 */
fun Modifier.accessibleImage(
    label: String
): Modifier = this.semantics {
    contentDescription = label
}
