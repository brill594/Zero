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

            // Set the remote adapter for the list to our service
            val svcIntent = Intent(context, TodoWidgetService::class.java)
            views.setRemoteAdapter(R.id.widget_list, svcIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // PendingIntent template for mark-done fill-in intents
            val template = Intent(context, TodoWidgetProvider::class.java).apply { action = ACTION_MARK_DONE }
            val piTemplate = PendingIntent.getBroadcast(context, appWidgetId, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setPendingIntentTemplate(R.id.widget_list, piTemplate)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}