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
            val notLow = pct == -1 || pct >= 15 // 与 WorkManager 的“电量不低”阈值保持接近
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
                        sourceNotificationKey = n.key
                    )
                )
                repo.markProcessed(listOf(n.id))
            }
            is L2ProcessResult.RequiresL3Slm -> {          // ✅ 新名字
                // 自动处理时强制使用单线程
                stage2Processor.l3SlmProcessor.setThreadsOverride(1)
                val t = stage2Processor.l3SlmProcessor     // ✅ 新名字
                    .process(full, res.intent)
                if (t != null) {
                    repo.saveTodo(
                        TodoEntity(
                            title = t.title,
                            dueAt = t.dueAt,
                            createdAt = System.currentTimeMillis(),
                            status = "OPEN",
                            sourceNotificationKey = n.key
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
