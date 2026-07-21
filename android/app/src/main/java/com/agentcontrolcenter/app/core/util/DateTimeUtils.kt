package com.agentcontrolcenter.app.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日期时间格式化工具。
 *
 * 统一管理 SimpleDateFormat 实例，避免重复创建。
 * SimpleDateFormat 非线程安全，使用 ThreadLocal 或每次创建。
 */

private val timeFormat = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}

private val dateFormat = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
}

private val dateTimeFormat = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
}

fun formatTime(timestamp: Long): String = timeFormat.get()!!.format(Date(timestamp))

fun formatDate(timestamp: Long): String = dateFormat.get()!!.format(Date(timestamp))

fun formatDateTime(timestamp: Long): String = dateTimeFormat.get()!!.format(Date(timestamp))
