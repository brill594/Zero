package com.brill.zero.worker

import android.content.Context
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
