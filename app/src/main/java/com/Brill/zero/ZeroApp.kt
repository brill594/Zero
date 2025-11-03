package com.brill.zero


import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.brill.zero.worker.NightlyTrainingWorker
import java.util.concurrent.TimeUnit


class ZeroApp : Application() {
    override fun onCreate() {
        super.onCreate()
// Schedule nightly training if not already scheduled
        val work = PeriodicWorkRequestBuilder<NightlyTrainingWorker>(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "nightly_training",
                ExistingPeriodicWorkPolicy.KEEP,
                work
            )
    }
}