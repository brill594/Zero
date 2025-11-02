package com.Brill.zero.worker


import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.Brill.zero.data.repo.ZeroRepository
import com.Brill.zero.ml.NlpProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class MediumPriorityWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = ZeroRepository.get(applicationContext)
        val nlp = NlpProcessor(applicationContext)
        val batch = repo.nextMediumBatch(limit = 25)
        batch.forEach { n ->
            val full = listOfNotNull(n.title, n.text).joinToString(" · ")
            val todo = nlp.toTodo(full)
            if (todo != null) {
                repo.saveTodo(
                    com.Brill.zero.data.db.TodoEntity(
                        title = todo.title,
                        dueAt = todo.dueAt,
                        createdAt = System.currentTimeMillis(),
                        status = "OPEN",
                        sourceNotificationKey = n.key
                    )
                )
            }
        }
        repo.markProcessed(batch.map { it.id })
        Result.success()
    }
}