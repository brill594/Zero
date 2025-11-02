package com.Brill.zero.domain.usecase

import android.content.Context
import com.Brill.zero.data.db.TodoEntity
import com.Brill.zero.data.repo.ZeroRepository
import com.Brill.zero.ml.NlpProcessor

/**
 * 拉取一批“中优先级未处理”的通知 -> 逐个生成 ToDo -> 标记已处理
 * 可在 WorkManager 的 Worker 中直接 new 后调用。
 */
class BatchProcessMediumPriority(private val context: Context) {
    private val repo by lazy { ZeroRepository.get(context) }
    private val stage2 by lazy { NlpProcessor(context) }

    /**
     * @return 这次批处理生成的 ToDo 数量
     */
    suspend operator fun invoke(limit: Int = 25): Int {
        val batch = repo.nextMediumBatch(limit)
        var created = 0
        batch.forEach { n ->
            val full = listOfNotNull(n.title, n.text).joinToString(" · ")
            val todo = stage2.toTodo(full)
            if (todo != null) {
                repo.saveTodo(
                    TodoEntity(
                        title = todo.title,
                        dueAt = todo.dueAt,
                        createdAt = System.currentTimeMillis(),
                        status = "OPEN",
                        sourceNotificationKey = n.key
                    )
                )
                created++
            }
        }
        repo.markProcessed(batch.map { it.id })
        return created
    }
}
