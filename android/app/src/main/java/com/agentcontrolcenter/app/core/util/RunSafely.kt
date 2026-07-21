package com.agentcontrolcenter.app.core.util

import kotlinx.coroutines.CancellationException

/**
 * 安全执行协程块，统一处理异常。
 *
 * - CancellationException 正确再抛
 * - 其他异常传递给 onError 回调
 *
 * 用法：
 * ```
 * viewModelScope.launch {
 *     runSafely(
 *         onError = { e -> _uiState.update { it.copy(error = e.message) } }
 *     ) {
 *         // 业务逻辑
 *     }
 * }
 * ```
 */
suspend fun <T> runSafely(
    onError: suspend (Exception) -> Unit = {},
    block: suspend () -> T
): T? {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
        null
    }
}
