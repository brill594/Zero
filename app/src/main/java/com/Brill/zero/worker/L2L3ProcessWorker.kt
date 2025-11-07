package com.brill.zero.worker

import android.content.Context
import android.util.Log
import android.os.BatteryManager
import android.content.IntentFilter
import android.content.Intent
import android.os.PowerManager
import android.app.KeyguardManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brill.zero.data.db.TodoEntity
import com.brill.zero.data.repo.ZeroRepository
import com.brill.zero.ml.L2ProcessResult
import com.brill.zero.ml.NlpProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.brill.zero.domain.model.Priority
import com.brill.zero.settings.AppSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.PendingIntent
import com.brill.zero.R

class L2L3ProcessWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val repo: ZeroRepository = ZeroRepository.get(appContext)
    private val stage2Processor: NlpProcessor = NlpProcessor(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 屏幕状态门控：仅对 MEDIUM 执行“屏幕无活动（或锁屏/熄屏）”限制
        val priorityStr = inputData.getString(WorkDefs.KEY_PRIORITY)
        val isMedium = priorityStr == Priority.MEDIUM.name
        if (isMedium) {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val interactive = runCatching { pm.isInteractive }.getOrDefault(true)
            val locked = runCatching { km.isKeyguardLocked }.getOrDefault(false)
            val ok = (!interactive) || locked
            if (!ok) {
                Log.i("ZeroWorker", "屏幕活跃，延后处理 MEDIUM 任务（将重试）")
                return@withContext Result.retry()
            }

            // 电量门控：满足“电量不低或正在充电”
            val battIntent: Intent? = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val threshold = AppSettings.getBatteryThreshold(applicationContext)
            val notLow = pct == -1 || pct >= threshold
            val powerOk = charging || notLow
            if (!powerOk) {
                Log.i("ZeroWorker", "电量偏低且未充电，延后处理 MEDIUM 任务（将重试）")
                return@withContext Result.retry()
            }
        }

        val notificationId = inputData.getLong(WorkDefs.KEY_NOTIFICATION_ID, -1L)
        if (notificationId == -1L) {
            Log.e("ZeroWorker", "Worker 收到无效的 ID")
            return@withContext Result.failure()
        }

        val n = repo.getNotificationById(notificationId)
        if (n == null || n.processed) {
            Log.w("ZeroWorker", "找不到通知 $notificationId 或它已被处理。")
            return@withContext Result.success()
        }

        val full = listOfNotNull(n.title, n.text).joinToString(" · ")

        when (val res = stage2Processor.processNotification(full)) {
            is L2ProcessResult.Handled -> {
                val t = res.todo
                repo.saveTodo(
                    TodoEntity(
                        title = t.title,
                        dueAt = t.dueAt,
                        createdAt = System.currentTimeMillis(),
                        status = "OPEN",
                        sourceNotificationKey = res.intent
                    )
                )
                // 若是验证码意图，尝试提取验证码并发送通知，提供复制按钮
                if (t.title.startsWith("验证码")) {
                    val codeRegex = Regex("""(?!.*\b尾号\b.*)(\b[A-Z0-9]{4,8}\b)""")
                    val code = codeRegex.find(listOfNotNull(n.title, n.text).joinToString(" · "))?.groupValues?.getOrNull(1)
                        ?: codeRegex.find(t.title)?.groupValues?.getOrNull(1)
                    if (!code.isNullOrBlank()) {
                        val copyIntent = Intent(applicationContext, com.brill.zero.receiver.CopyCodeReceiver::class.java).apply {
                            action = "com.brill.zero.ACTION_COPY_CODE"
                            putExtra("code", code)
                        }
                        val piCopy = PendingIntent.getBroadcast(
                            applicationContext,
                            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                            copyIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val builder = NotificationCompat.Builder(applicationContext, "codes")
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("验证码")
                            .setContentText(code)
                            .setStyle(NotificationCompat.BigTextStyle().bigText("验证码：$code"))
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .addAction(NotificationCompat.Action.Builder(0, "复制", piCopy).build())
                            .setAutoCancel(true)
                        NotificationManagerCompat.from(applicationContext)
                            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
                    }
                }
                repo.markProcessed(listOf(n.id))
            }
            is L2ProcessResult.RequiresL3Slm -> {          // ✅ 新名字
                // 使用设置页面中的线程数
                runCatching {
                    val threads = AppSettings.getL3Threads(applicationContext)
                    stage2Processor.l3SlmProcessor.setThreadsOverride(threads)
                }
                val t = stage2Processor.l3SlmProcessor     // ✅ 新名字
                    .process(full, res.intent)
                if (t != null) {
                    repo.saveTodo(
                        TodoEntity(
                            title = t.title,
                            dueAt = t.dueAt,
                            createdAt = System.currentTimeMillis(),
                            status = "OPEN",
                            sourceNotificationKey = res.intent
                        )
                    )
                }
                repo.markProcessed(listOf(n.id))
            }
            L2ProcessResult.Ignore -> {                    // ✅ object 分支不要写 `is`
                repo.markProcessed(listOf(n.id))
            }
        }

        // 可选：如果保存了 todo，可在这里触发小组件刷新
        // com.brill.zero.widget.ZeroWidget.updateAll(applicationContext)

        Result.success()
    }
}
