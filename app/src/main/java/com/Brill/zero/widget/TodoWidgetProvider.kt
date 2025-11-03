package com.brill.zero.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.brill.zero.R
import com.brill.zero.data.repo.ZeroRepository
import kotlinx.coroutines.runBlocking

class TodoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_MARK_DONE -> {
                val id = intent.getLongExtra(EXTRA_TODO_ID, 0L)
                if (id != 0L) {
                    runBlocking { ZeroRepository.get(context).markTodoDone(id) }
                }
                val mgr = AppWidgetManager.getInstance(context)
                val cn = ComponentName(context, TodoWidgetProvider::class.java)
                val ids = mgr.getAppWidgetIds(cn)
                onUpdate(context, mgr, ids)
            }
        }
    }

    companion object {
        const val ACTION_MARK_DONE = "com.brill.zero.widget.ACTION_MARK_DONE"
        const val EXTRA_TODO_ID = "todo_id"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_todo)

            val repo = ZeroRepository.get(context)
            val next = runBlocking { repo.getNextOpenTodo() }

            if (next != null) {
                val intentText = next.sourceNotificationKey ?: "(未设置意图)"
                views.setTextViewText(R.id.widget_intent, intentText)
                views.setTextViewText(R.id.widget_summary, next.title)
                val dueLine = next.dueAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "无截止时间"
                views.setTextViewText(R.id.widget_due, dueLine)

                val doneIntent = Intent(context, TodoWidgetProvider::class.java).apply {
                    action = ACTION_MARK_DONE
                    putExtra(EXTRA_TODO_ID, next.id)
                }
                val pi = PendingIntent.getBroadcast(context, appWidgetId, doneIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_done, pi)
            } else {
                views.setTextViewText(R.id.widget_intent, "(暂无待办)")
                views.setTextViewText(R.id.widget_summary, "")
                views.setTextViewText(R.id.widget_due, "")
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}