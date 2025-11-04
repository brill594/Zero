package com.brill.zero.widget

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.brill.zero.R
import com.brill.zero.data.repo.ZeroRepository
import kotlinx.coroutines.runBlocking

class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent?): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: android.content.Context) : RemoteViewsService.RemoteViewsFactory {
        private var items: List<com.brill.zero.data.db.TodoEntity> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            items = runBlocking { ZeroRepository.get(ctx).getOpenTodos(limit = 5) }
        }

        override fun onDestroy() { items = emptyList() }
        override fun getCount(): Int = items.size
        override fun getViewTypeCount(): Int = 1
        override fun hasStableIds(): Boolean = true
        override fun getItemId(position: Int): Long = items.getOrNull(position)?.id ?: position.toLong()

        override fun getViewAt(position: Int): RemoteViews? {
            val t = items.getOrNull(position) ?: return null
            val rv = RemoteViews(ctx.packageName, R.layout.widget_todo_item)
            rv.setTextViewText(R.id.item_summary, t.title)
            val dueLine = t.dueAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "无截止时间"
            rv.setTextViewText(R.id.item_due, dueLine)

            // Fill-in intent for mark done
            val fill = Intent().apply {
                putExtra(TodoWidgetProvider.EXTRA_TODO_ID, t.id)
            }
            rv.setOnClickFillInIntent(R.id.item_done, fill)
            return rv
        }
        override fun getLoadingView(): RemoteViews? = null
    }
}