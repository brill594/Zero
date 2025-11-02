package com.Brill.zero.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow

private val Context.dataStore by preferencesDataStore(name = "settings")

object PrefKeys {
    val AUTO_TAKEOVER = booleanPreferencesKey("auto_takeover")            // 自动接管通知
    val TRAIN_WHEN_CHARGING = booleanPreferencesKey("train_when_charging")// 仅充电时训练
    val BATCH_INTERVAL_MIN = intPreferencesKey("batch_interval_min")      // 中优先级批处理间隔
}

class SettingsDataStore(private val context: Context) {
    val autoTakeover: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.AUTO_TAKEOVER] ?: true }

    val trainWhenCharging: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.TRAIN_WHEN_CHARGING] ?: true }

    val batchIntervalMinutes: Flow<Int> =
        context.dataStore.data.map { it[PrefKeys.BATCH_INTERVAL_MIN] ?: 30 }

    suspend fun setAutoTakeover(v: Boolean) = context.dataStore.edit {
        it[PrefKeys.AUTO_TAKEOVER] = v
    }

    suspend fun setTrainWhenCharging(v: Boolean) = context.dataStore.edit {
        it[PrefKeys.TRAIN_WHEN_CHARGING] = v
    }

    suspend fun setBatchIntervalMinutes(min: Int) = context.dataStore.edit {
        it[PrefKeys.BATCH_INTERVAL_MIN] = min.coerceIn(5, 120)
    }
}
