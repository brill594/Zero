package com.brill.zero.settings

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREFS_NAME = "zero_settings"
    private const val KEY_BATTERY_THRESHOLD = "battery_threshold_pct"
    private const val KEY_L3_THREADS = "l3_threads"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBatteryThreshold(ctx: Context): Int {
        val p = prefs(ctx)
        return p.getInt(KEY_BATTERY_THRESHOLD, 15) // default 15%
    }

    fun setBatteryThreshold(ctx: Context, value: Int) {
        val v = value.coerceIn(5, 50)
        prefs(ctx).edit().putInt(KEY_BATTERY_THRESHOLD, v).apply()
    }

    fun getL3Threads(ctx: Context): Int {
        val p = prefs(ctx)
        return p.getInt(KEY_L3_THREADS, 4) // default threads 4
    }

    fun setL3Threads(ctx: Context, value: Int) {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
        val v = value.coerceIn(1, cores)
        prefs(ctx).edit().putInt(KEY_L3_THREADS, v).apply()
    }
}