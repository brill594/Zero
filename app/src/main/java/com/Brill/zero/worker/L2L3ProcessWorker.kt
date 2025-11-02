package com.Brill.zero.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.Brill.zero.data.db.TodoEntity
import com.Brill.zero.data.repo.ZeroRepository
import com.Brill.zero.ml.L2ProcessResult
import com.Brill.zero.ml.NlpProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [!!!] V26 新 Worker: L2/L3 处理器
 * 这是我们架构的核心。它被 NLS 触发。
 * 它可以 "立即" 运行 (对于 HIGH) 或 "受约束" 运行 (对于 MEDIUM)。
 * 它的工作是运行 L2 (MobileBERT) 和 L3 (RegEx / SLM)。
 */
class L2L3ProcessWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    // [!] 关键: Worker 持有 Repository 和 L2/L3 处理器
    private val repo: ZeroRepository = ZeroRepository.get(appContext)
    private val stage2Processor: NlpProcessor = NlpProcessor(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 1. 获取 NLS 传来的通知 ID
        val notificationId = inputData.getLong("NOTIFICATION_ID", -1L)
        if (notificationId == -1L) {
            Log.e("ZeroWorker", "Worker 收到无效的 ID")
            return@withContext Result.failure()
        }

        // 2. 从数据库获取通知
        val notification = repo.getNotificationById(notificationId)
        if (notification == null || notification.processed) {
            Log.w("ZeroWorker", "找不到通知 $notificationId 或它已被处理。")
            return@withContext Result.success() // 任务已完成
        }

        val fullText = listOfNotNull(notification.title, notification.text).joinToString(" · ")

        // 3. [!!!] 运行 L2/L3-A (分流器) [!!!]
        val result = stage2Processor.processNotification(fullText)

        var todoCreated = false

        when (result) {
            // 4. L3-A (RegEx) 成功: 立即保存 Todo
            is L2ProcessResult.Handled -> {
                Log.i("ZeroWorker", "L3-RegEx 成功 (ID: $notificationId)")
                repo.saveTodo(
                    TodoEntity(
                        title = result.todo.title,
                        dueAt = result.todo.dueAt,
                        createdAt = System.currentTimeMillis(),
                        status = "OPEN",
                        sourceNotificationKey = notification.key
                    )
                )
                todoCreated = true
            }

            // 5. [!!!] L3-B (SLM) 触发 [!!!]
            is L2ProcessResult.RequiresL3_SLM -> {
                Log.i("ZeroWorker", "L3-SLM (Qwen) 正在处理 (ID: $notificationId)...")

                // [!] 唤醒 "重型" L3-SLM 引擎
                // [!] 这是最耗电的一步
                val todo = stage2Processor.l3_slm_processor.process(fullText, result.intent)

                if (todo != null) {
                    repo.saveTodo(
                        TodoEntity(
                            title = todo.title,
                            dueAt = todo.dueAt,
                            createdAt = System.currentTimeMillis(),
                            status = "OPEN",
                            sourceNotificationKey = notification.key
                        )
                    )
                    todoCreated = true
                } else {
                    Log.e("ZeroWorker", "L3-SLM 引擎返回了 null (ID: $notificationId)")
                }
            }

            // 6. L2 决定忽略 (e.g., "系统通知")
            is L2ProcessResult.Ignore -> {
                Log.i("ZeroWorker", "L2 决定忽略 (ID: $notificationId)")
            }
        }

        // 7. 标记为 "已处理"
        repo.markProcessed(listOf(notificationId))

        // 8. (可选) 如果创建了 Todo，再次更新小组件
        if (todoCreated) {
            com.Brill.zero.widget.ZeroWidget.updateAll(applicationContext)
        }

        return@withContext Result.success()
    }
}