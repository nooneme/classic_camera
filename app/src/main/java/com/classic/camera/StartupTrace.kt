package com.classic.camera

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * 启动耗时打点工具。以进程启动时刻为基准，输出相对毫秒数。
 * 仅用于诊断启动到首帧预览的延迟。
 */
object StartupTrace {
    const val TAG = "ClassicCamera"

    // 与 uptimeMillis 同一时基，进程创建时刻
    private val base = runCatching { Process.getStartUptimeMillis() }.getOrDefault(0L)

    fun mark(label: String) {
        if (base <= 0L) {
            Log.d(TAG, "[STARTUP] ----  $label")
            return
        }
        val elapsed = SystemClock.uptimeMillis() - base
        Log.d(TAG, String.format("[STARTUP] +%5dms  %s", elapsed, label))
    }
}
