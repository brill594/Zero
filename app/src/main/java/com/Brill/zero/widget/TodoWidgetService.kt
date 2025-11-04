package com.brill.zero.widget

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.appwidget.AppWidgetManager
import com.brill.zero.R
import com.brill.zero.data.repo.ZeroRepository
import kotlinx.coroutines.runBlocking
import android.util.Log
import android.os.SystemClock

class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent?): RemoteViewsFactory =
        Factory(applicationContext, intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID)

    private class Factory(private val ctx: android.content.Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
        private var items: List<com.brill.zero.data.db.TodoEntity> = emptyList()
        private val tag = "TodoWidget"

        override fun onCreate() { Log.d(tag, "Factory.onCreate: widgetId=$widgetId") }

        override fun onDataSetChanged() {
            val t0 = SystemClock.uptimeMillis()
            Log.d(tag, "onDataSetChanged: start widgetId=$widgetId metrics=${metricsStr(ctx)}")
            // Clear Binder identity to ensure app can access its own storage/db under RemoteViews context
            val token = android.os.Binder.clearCallingIdentity()
            try {
                runCatching {
                    // Read today/tomorrow offset from prefs
                    val prefs = ctx.getSharedPreferences("todo_widget_prefs", android.content.Context.MODE_PRIVATE)
                    val offset = prefs.getInt("offset_$widgetId", 0)
                    Log.d(tag, "onDataSetChanged: offset=$offset")
                    val repo = ZeroRepository.get(ctx)
                    val tFetch0 = SystemClock.uptimeMillis()
                    val all = runBlocking { repo.getOpenTodos(limit = 50) }
                    val tFetchDt = SystemClock.uptimeMillis() - tFetch0
                    val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
                    val startCal = (cal.clone() as java.util.Calendar).apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val endCal = (startCal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
                    val start = startCal.timeInMillis
                    val end = endCal.timeInMillis
                    val tFilter0 = SystemClock.uptimeMillis()
                    items = all.filter { it.dueAt?.let { d -> d in start until end } ?: false }
                        .take(5)
                    val tFilterDt = SystemClock.uptimeMillis() - tFilter0
                    Log.d(tag, "onDataSetChanged: repoCount=${all.size} filtered=${items.size} fetch=${tFetchDt}ms filter=${tFilterDt}ms")
                    if (items.isNotEmpty()) {
                        val first = items.first()
                        Log.d(tag, "onDataSetChanged: first id=${first.id} dueAt=${first.dueAt} titleLen=${first.title.length}")
                    }
                }.onFailure {
                    items = emptyList()
                    Log.e(tag, "onDataSetChanged: error", it)
                }
            } finally {
                android.os.Binder.restoreCallingIdentity(token)
            }
            val dt = SystemClock.uptimeMillis() - t0
            Log.d(tag, "onDataSetChanged: end count=${items.size} took=${dt}ms")
        }

        override fun onDestroy() { items = emptyList() }
        override fun getCount(): Int { val c = items.size; Log.d(tag, "getCount=$c widgetId=$widgetId"); return c }
        override fun getViewTypeCount(): Int = 1
        override fun hasStableIds(): Boolean = true
        override fun getItemId(position: Int): Long = items.getOrNull(position)?.id ?: position.toLong()

        override fun getViewAt(position: Int): RemoteViews? {
            val tBuild0 = SystemClock.uptimeMillis()
            val t = items.getOrNull(position) ?: return null
            Log.d(tag, "getViewAt: pos=$position id=${t.id} titleLen=${t.title.length}")
            val rv = RemoteViews(ctx.packageName, R.layout.widget_todo_item)
            // Title & sub
            rv.setTextViewText(R.id.item_title, t.title)
            val subLine = t.sourceNotificationKey ?: "(未设置意图)"
            rv.setTextViewText(R.id.item_sub, subLine)

            // Time column
            val dueDate = t.dueAt?.let { java.util.Date(it) }
            val timeTop = dueDate?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(it) } ?: ""
            rv.setTextViewText(R.id.item_time_top, timeTop)
            // bottom line optional (use same or blank)
            rv.setTextViewText(R.id.item_time_bottom, "")

            // Left bar color alternates using resource to avoid host restrictions
            val barRes = if (position % 2 == 0) R.drawable.bar_purple else R.drawable.bar_teal
            rv.setImageViewResource(R.id.item_bar, barRes)
            // Scale guard: ensure row scale stays at 1
            rv.setFloat(R.id.item_container, "setScaleX", 0.92f)
            rv.setFloat(R.id.item_container, "setScaleY", 0.92f)

            // Keep RemoteViews minimal; avoid toggling view states that hosts may cache

            // Fill-in intent to open app To‑Do page and optionally focus this item
            val fill = Intent().apply {
                putExtra("open_route", "todos")
                putExtra("open_todo_id", t.id)
            }
            rv.setOnClickFillInIntent(R.id.item_container, fill)
            val tBuildDt = SystemClock.uptimeMillis() - tBuild0
            Log.d(tag, "getViewAt: pos=$position builtIn=${tBuildDt}ms")
            return rv
        }

        private fun metricsStr(ctx: android.content.Context): String {
            val dm = ctx.resources.displayMetrics
            val cfg = ctx.resources.configuration
            return "dpi=${dm.densityDpi} dens=${dm.density} sDens=${dm.scaledDensity} font=${cfg.fontScale} sw=${cfg.smallestScreenWidthDp} orien=${cfg.orientation}"
        }
        override fun getLoadingView(): RemoteViews? {
            Log.d(tag, "getLoadingView: returning null to use host default")
            return null
        }
    }
}