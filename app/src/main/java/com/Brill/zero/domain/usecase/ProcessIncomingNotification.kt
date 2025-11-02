package com.Brill.zero.domain.usecase

import android.content.Context
import com.Brill.zero.data.db.NotificationEntity
import com.Brill.zero.data.db.TodoEntity
import com.Brill.zero.data.repo.ZeroRepository
import com.Brill.zero.domain.model.Priority
import com.Brill.zero.ml.NlpProcessor
import com.Brill.zero.ml.PriorityClassifier

/**
 * 单条通知的同步处理：分级 -> 入库 -> (必要时) 生成 ToDo
 * 在 NotificationListenerService 或测试代码里直接调用即可。
 */
class ProcessIncomingNotification(private val context: Context) {

    private val repo by lazy { ZeroRepository.get(context) }
    private val stage1 by lazy { PriorityClassifier(context) }
    private val stage2 by lazy { NlpProcessor(context) }

    /**
     * @return 实际写入的 NotificationEntity.id 以及是否生成了 ToDo
     */
    suspend operator fun invoke(
        key: String,
        pkg: String,
        title: String?,
        text: String?
    ): Pair<Long, Boolean> {
        val full = listOfNotNull(title, text).joinToString(" · ")
        val priority = stage1.predictPriority(full)

        val entity = NotificationEntity(
            key = key,
            pkg = pkg,
            title = title,
            text = text,
            postedAt = System.currentTimeMillis(),
            priority = priority.name
        )
        val id = repo.saveNotification(entity)

        var todoCreated = false
        if (priority == Priority.HIGH) {
            stage2.toTodo(full)?.let { todo ->
                repo.saveTodo(
                    TodoEntity(
                        title = todo.title,
                        dueAt = todo.dueAt,
                        createdAt = System.currentTimeMillis(),
                        status = "OPEN",
                        sourceNotificationKey = key
                    )
                )
                todoCreated = true
            }
        }
        return id to todoCreated
    }
}
