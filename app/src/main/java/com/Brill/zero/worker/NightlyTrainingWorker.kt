package com.brill.zero.worker


import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class NightlyTrainingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
// Only when charging + device idle (configured via Constraints where you enqueue)
// TODO: Plug in your Mediapipe Model Maker on-device retraining or export labeled data.
// For now, we no-op and succeed.
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }


    companion object {
        fun defaultConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()
    }
}