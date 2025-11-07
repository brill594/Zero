package com.brill.zero.domain.usecase

import android.content.Context
import com.brill.zero.data.db.NotificationEntity
import com.brill.zero.data.db.TodoEntity
import com.brill.zero.data.repo.ZeroRepository
import com.brill.zero.domain.model.Priority
import com.brill.zero.ml.L2ProcessResult
import com.brill.zero.ml.NlpProcessor
import com.brill.zero.ml.PriorityClassifier

class ProcessIncomingNotification(private val context: Context) {

    private val repo by lazy { ZeroRepository.get(context) }
    private val stage1 by lazy { PriorityClassifier(context) }
    private val stage2 by lazy { NlpProcessor(context) }  // 提供 L2/L3

    suspend operator fun invoke(
        key: String,
        pkg: String,
        title: String?,
        text: String?
    ): Pair<Long, Boolean> {
        val full = listOfNotNull(title, text).joinToString(" · ")
        val priority = stage1.predictPriority(full)

        val id = repo.saveNotification(
            NotificationEntity(
                key = key,
                pkg = pkg,
                title = title,
                text = text,
                postedAt = System.currentTimeMillis(),
                priority = priority.name
            )
        )

        var todoCreated = false
        if (priority == Priority.HIGH) {
            when (val res = stage2.processNotification(full)) {
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
                    todoCreated = true
                }
                is L2ProcessResult.RequiresL3Slm -> {                 // ✅ 新分支名
                    val t = stage2.l3SlmProcessor.process(full, res.intent) // ✅ 复用实例
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
                        todoCreated = true
                    }
                }
                L2ProcessResult.Ignore -> { /* 忽略 */ }               // ✅ object 分支不加 is
            }
        }
        return id to todoCreated
    }
}
