package com.brill.zero.settings

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREFS_NAME = "zero_settings"
    private const val KEY_BATTERY_THRESHOLD = "battery_threshold_pct"
    private const val KEY_L3_THREADS = "l3_threads"
    private const val KEY_L1_USE_LEARNED = "l1_use_learned_model"
    private const val KEY_L1_LAST_DATASET_SIG = "l1_last_dataset_sig"
    private const val KEY_L1_TRAIN_PROGRESS_EPOCH = "l1_train_progress_epoch"
    private const val KEY_L1_NIGHTLY_SCHEDULED = "l1_nightly_scheduled"
    private const val KEY_L1_SELECTED_MODEL_PATH = "l1_selected_model_path"
    private const val KEY_L1_TRAIN_START_TS = "l1_train_start_ts"
    private const val KEY_L1_TRAIN_LAST_UPDATE_TS = "l1_train_last_update_ts"

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

    // ---- L1 训练与模型选择相关 ----
    fun getUseLearnedL1(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_L1_USE_LEARNED, false)

    fun setUseLearnedL1(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_L1_USE_LEARNED, value).apply()
    }

    fun getL1LastDatasetSig(ctx: Context): String? =
        prefs(ctx).getString(KEY_L1_LAST_DATASET_SIG, null)

    fun setL1LastDatasetSig(ctx: Context, sig: String) {
        prefs(ctx).edit().putString(KEY_L1_LAST_DATASET_SIG, sig).apply()
    }

    fun getL1TrainProgressEpoch(ctx: Context): Int =
        prefs(ctx).getInt(KEY_L1_TRAIN_PROGRESS_EPOCH, 0)

    fun setL1TrainProgressEpoch(ctx: Context, epoch: Int) {
        val v = epoch.coerceIn(0, 80)
        prefs(ctx).edit().putInt(KEY_L1_TRAIN_PROGRESS_EPOCH, v).apply()
    }

    fun getL1NightlyScheduled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_L1_NIGHTLY_SCHEDULED, false)

    fun setL1NightlyScheduled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_L1_NIGHTLY_SCHEDULED, value).apply()
    }

    fun getL1SelectedModelPath(ctx: Context): String? =
        prefs(ctx).getString(KEY_L1_SELECTED_MODEL_PATH, null)

    fun setL1SelectedModelPath(ctx: Context, path: String?) {
        val edit = prefs(ctx).edit()
        if (path == null) edit.remove(KEY_L1_SELECTED_MODEL_PATH) else edit.putString(KEY_L1_SELECTED_MODEL_PATH, path)
        edit.apply()
    }

    fun getL1TrainStartTs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_L1_TRAIN_START_TS, 0L)

    fun setL1TrainStartTs(ctx: Context, ts: Long) {
        prefs(ctx).edit().putLong(KEY_L1_TRAIN_START_TS, ts).apply()
    }

    fun getL1TrainLastUpdateTs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_L1_TRAIN_LAST_UPDATE_TS, 0L)

    fun setL1TrainLastUpdateTs(ctx: Context, ts: Long) {
        prefs(ctx).edit().putLong(KEY_L1_TRAIN_LAST_UPDATE_TS, ts).apply()
    }
}