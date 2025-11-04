package com.brill.zero.worker

import android.content.Context
import android.os.PowerManager
import android.app.KeyguardManager
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brill.zero.data.db.TodoEntity
import com.brill.zero.data.repo.ZeroRepository
import com.brill.zero.ml.L2ProcessResult
import com.brill.zero.ml.NlpProcessor

class MediumPriorityWorker(
    appContext: Context,
    params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 屏幕状态门控：需要“无交互或锁屏”
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val interactive = runCatching { pm.isInteractive }.getOrDefault(true)
        val locked = runCatching { km.isKeyguardLocked }.getOrDefault(false)
        val ok = (!interactive) || locked
        if (!ok) return Result.retry()

        // 电量门控：满足“电量不低或正在充电”
        val battIntent: Intent? = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val notLow = pct == -1 || pct >= 15
        val powerOk = charging || notLow
        if (!powerOk) return Result.retry()

        val repo = ZeroRepository.get(applicationContext)
        val nlp  = NlpProcessor(applicationContext)
        val slm  = nlp.l3SlmProcessor   // ✅ 新命名（原 l3_slm_processor）

        val batch = repo.nextMediumBatch(limit = 25)
        val processedIds = mutableListOf<Long>()  // ✅ 用 val 即可（列表可变）

        for (n in batch) {
            val full = listOfNotNull(n.title, n.text).joinToString(" · ")

            when (val res = nlp.processNotification(full)) {
                is L2ProcessResult.Handled -> {
                    val t = res.todo
                    repo.saveTodo(
                        TodoEntity(
                            title = t.title,        // ✅ 字段名没变
                            dueAt = t.dueAt,        // ✅ 字段名没变
                            createdAt = System.currentTimeMillis(),
                            status = "OPEN",
                            sourceNotificationKey = n.key
                        )
                    )
                    processedIds.add(n.id)
                }
                is L2ProcessResult.RequiresL3Slm -> { // ✅ 新类名（原 RequiresL3_SLM）
                    val t = slm.process(full, res.intent)  // ✅ res.intent 可用
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
                    processedIds.add(n.id)
                }
                L2ProcessResult.Ignore -> {
                    // 忽略也标记为已处理，防止重复进入队列
                    processedIds.add(n.id)
                }
            }
        }

        if (processedIds.isNotEmpty()) {
            repo.markProcessed(processedIds)
        }
        return Result.success()
    }
}
