package com.brill.zero.data.repo

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.brill.zero.R
import com.brill.zero.data.db.*
import com.brill.zero.widget.TodoWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class ZeroRepository private constructor(private val db: ZeroDatabase, private val appCtx: Context) {
    val openTodos = db.todoDao().streamOpen()


    fun streamByPriority(p: String) = db.notificationDao().streamByPriority(p)


    suspend fun saveNotification(n: NotificationEntity) = withContext(Dispatchers.IO) {
        db.notificationDao().upsert(n)
    }


    suspend fun saveTodo(todo: TodoEntity): Long = withContext(Dispatchers.IO) {
        val id = db.todoDao().upsert(todo)
        notifyWidgets()
        id
    }

    suspend fun markTodoDone(id: Long) = withContext(Dispatchers.IO) {
        db.todoDao().markDone(id)
        notifyWidgets()
    }

    suspend fun updateTodoDueAt(id: Long, dueAt: Long?) = withContext(Dispatchers.IO) {
        db.todoDao().setDueAt(id, dueAt)
        notifyWidgets()
    }

    suspend fun getNextOpenTodo(): TodoEntity? = withContext(Dispatchers.IO) {
        db.todoDao().getNextOpen()
    }

    suspend fun getOpenTodos(limit: Int = 5): List<TodoEntity> = withContext(Dispatchers.IO) {
        db.todoDao().getOpen(limit)
    }


    suspend fun nextMediumBatch(limit: Int = 25) = db.notificationDao().nextUnprocessedMedium(limit)


    suspend fun markProcessed(ids: List<Long>) = db.notificationDao().markProcessed(ids)

    suspend fun getNotificationById(id: Long) = db.notificationDao().getById(id)

    suspend fun setNotificationUserPriority(id: Long, userPriority: String?) = withContext(Dispatchers.IO) {
        db.notificationDao().setUserPriority(id, userPriority)
    }

    suspend fun deleteNotification(id: Long) = withContext(Dispatchers.IO) {
        db.notificationDao().deleteById(id)
    }

    suspend fun clearAllNotifications() = withContext(Dispatchers.IO) {
        db.notificationDao().clearAll()
    }
    companion object {
        @Volatile private var INSTANCE: ZeroRepository? = null
        fun get(context: Context): ZeroRepository = INSTANCE ?: synchronized(this) {
            val app = context.applicationContext
            INSTANCE ?: ZeroRepository(ZeroDatabase.build(app), app).also { INSTANCE = it }
        }
    }

    private fun notifyWidgets() {
        runCatching {
            val mgr = AppWidgetManager.getInstance(appCtx)
            val cn = ComponentName(appCtx, TodoWidgetProvider::class.java)
            val ids = mgr.getAppWidgetIds(cn)
            if (ids.isNotEmpty()) {
                // Extreme stability: only refresh the collection data; avoid full relayout
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
        }
    }
}