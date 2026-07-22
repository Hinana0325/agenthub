package com.agentcontrolcenter.app.core.config

/**
 * 配置校验结果。
 *
 * 与 iOS `ConfigValidationResult` 对齐：单条 error = field + message。
 * 用 data class + List 而非 sealed class，便于表单 UI 一次性渲染所有错误。
 */
data class ConfigValidationError(
    /** 出错字段名（与领域模型的属性名对齐，便于 UI 高亮对应输入框） */
    val field: String,
    /** 用户可读的错误描述 */
    val message: String
)

/**
 * 校验结果。空 errors 表示通过。
 *
 * 与 iOS `ConfigValidationResult` 对齐。
 */
data class ConfigValidationResult(
    val errors: List<ConfigValidationError> = emptyList()
) {
    val isValid: Boolean get() = errors.isEmpty()

    /** 取某个字段的第一条错误（UI 渲染 helperText 时用） */
    fun errorFor(field: String): String? = errors.firstOrNull { it.field == field }?.message

    companion object {
        val Valid = ConfigValidationResult(emptyList())
        fun of(vararg errors: ConfigValidationError) = ConfigValidationResult(errors.toList())
    }
}
