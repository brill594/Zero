package com.brill.zero


import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.brill.zero.ml.SlmRuntime
import com.brill.zero.worker.NightlyTrainingWorker
import java.util.concurrent.TimeUnit


class ZeroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化 SLM 运行时（前台预热，后台空闲释放）
        runCatching { SlmRuntime.init(this) }
// Schedule nightly training if not already scheduled
        val work = PeriodicWorkRequestBuilder<NightlyTrainingWorker>(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "nightly_training",
                ExistingPeriodicWorkPolicy.KEEP,
                work
            )

        // Create notification channel for verification codes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "codes",
                "验证码提醒",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "用于显示验证码并提供复制按钮的通知"
            mgr.createNotificationChannel(channel)
        }
    }
}