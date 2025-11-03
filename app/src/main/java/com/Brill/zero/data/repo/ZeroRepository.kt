package com.brill.zero.data.repo

import android.content.Context
import com.brill.zero.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class ZeroRepository private constructor(private val db: ZeroDatabase) {
    val openTodos = db.todoDao().streamOpen()


    fun streamByPriority(p: String) = db.notificationDao().streamByPriority(p)


    suspend fun saveNotification(n: NotificationEntity) = withContext(Dispatchers.IO) {
        db.notificationDao().upsert(n)
    }


    suspend fun saveTodo(todo: TodoEntity) = withContext(Dispatchers.IO) {
        db.todoDao().upsert(todo)
    }

    suspend fun markTodoDone(id: Long) = withContext(Dispatchers.IO) {
        db.todoDao().markDone(id)
    }

    suspend fun getNextOpenTodo(): TodoEntity? = withContext(Dispatchers.IO) {
        db.todoDao().getNextOpen()
    }


    suspend fun nextMediumBatch(limit: Int = 25) = db.notificationDao().nextUnprocessedMedium(limit)


    suspend fun markProcessed(ids: List<Long>) = db.notificationDao().markProcessed(ids)

    suspend fun getNotificationById(id: Long) = db.notificationDao().getById(id)
    companion object {
        @Volatile private var INSTANCE: ZeroRepository? = null
        fun get(context: Context): ZeroRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ZeroRepository(ZeroDatabase.build(context)).also { INSTANCE = it }
        }
    }
}