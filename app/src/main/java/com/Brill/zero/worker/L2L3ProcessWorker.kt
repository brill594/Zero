package com.brill.zero.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brill.zero.data.db.TodoEntity
import com.brill.zero.data.repo.ZeroRepository
import com.brill.zero.ml.L2ProcessResult
import com.brill.zero.ml.NlpProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class L2L3ProcessWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val repo: ZeroRepository = ZeroRepository.get(appContext)
    private val stage2Processor: NlpProcessor = NlpProcessor(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val notificationId = inputData.getLong("NOTIFICATION_ID", -1L)
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
