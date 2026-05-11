package com.github.itskenny0.r1ha.core.util

import android.util.Log

/**
 * Single point for runtime logging. All R1 HA logs share the same tag so a developer can
 * filter live with `adb logcat | grep R1HA`. Verbose / debug logs are stripped from release
 * builds by the ProGuard rule in `proguard-rules.pro`; `i` / `w` / `e` survive so production
 * crash troubleshooting is possible.
 */
internal object R1Log {
    private const val TAG = "R1HA"

    fun v(where: String, msg: String) { Log.v(TAG, "$where: $msg") }
    fun d(where: String, msg: String) { Log.d(TAG, "$where: $msg") }
    fun i(where: String, msg: String) { Log.i(TAG, "$where: $msg") }
    fun w(where: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, "$where: $msg", t) else Log.w(TAG, "$where: $msg")
    }
    fun e(where: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, "$where: $msg", t) else Log.e(TAG, "$where: $msg")
    }
}
