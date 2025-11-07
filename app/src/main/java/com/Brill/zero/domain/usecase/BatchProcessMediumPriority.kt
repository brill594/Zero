package com.brill.zero.domain.usecase

import android.content.Context
import com.brill.zero.data.db.TodoEntity
import com.brill.zero.data.repo.ZeroRepository
import com.brill.zero.ml.L2ProcessResult
import com.brill.zero.ml.NlpProcessor

/**
 * 拉取一批“中优先级未处理”的通知 -> 逐个生成 ToDo -> 标记已处理
 * 供 WorkManager 的 Worker 调用。
 */
class BatchProcessMediumPriority(private val context: Context) {

    private val repo by lazy { ZeroRepository.get(context) }
    private val nlp by lazy { NlpProcessor(context) }   // L2 + L3-A(RegEx) + L3-SLM 入口

    suspend operator fun invoke(limit: Int = 25): Int {
        val batch = repo.nextMediumBatch(limit)
        var created = 0

        for (n in batch) {
            val full = listOfNotNull(n.title, n.text).joinToString(" · ")

            when (val res = nlp.processNotification(full)) {
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
                    created++
                }
                is L2ProcessResult.RequiresL3Slm -> {       // ✅ 新分支名
                    val t = nlp.l3SlmProcessor.process(full, res.intent)   // ✅ 复用实例
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
                        created++
                    }
                }
                L2ProcessResult.Ignore -> { /* no-op */ }   // ✅ object 分支不加 is
            }
        }

        repo.markProcessed(batch.map { it.id })
        return created
    }
}
