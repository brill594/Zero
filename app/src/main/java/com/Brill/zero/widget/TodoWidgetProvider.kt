package com.brill.zero.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.net.Uri
import android.content.SharedPreferences
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.brill.zero.MainActivity
import com.brill.zero.R
import com.brill.zero.data.repo.ZeroRepository
import kotlinx.coroutines.runBlocking

private const val TAG = "TodoWidget"
private const val SCALE = 0.92f

class TodoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.d(TAG, "onUpdate: ids=${appWidgetIds.joinToString()}")
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Log.d(
            TAG,
            "onAppWidgetOptionsChanged: id=$appWidgetId options=${optionsStr(appWidgetManager, appWidgetId)} metrics=${metricsStr(context)}"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_MARK_DONE -> {
                val id = intent.getLongExtra(EXTRA_TODO_ID, 0L)
                Log.d(TAG, "onReceive: ACTION_MARK_DONE id=$id")
                if (id != 0L) {
                    runBlocking { ZeroRepository.get(context).markTodoDone(id) }
                }
                val mgr = AppWidgetManager.getInstance(context)
                val cn = ComponentName(context, TodoWidgetProvider::class.java)
                val ids = mgr.getAppWidgetIds(cn)
                onUpdate(context, mgr, ids)
            }
            ACTION_NEXT_DAY -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                Log.d(TAG, "onReceive: ACTION_NEXT_DAY appWidgetId=$appWidgetId")
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val prefs = prefs(context)
                    val key = "offset_$appWidgetId"
                    val todayKey = "offset_cached_day_$appWidgetId"
                    val currentOffset = prefs.getInt(key, 0)
                    val cal = java.util.Calendar.getInstance()
                    val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
                    val newOffset = if (currentOffset == 0) 1 else 0
                    prefs.edit().putInt(key, newOffset).putInt(todayKey, today).apply()

                    val mgr = AppWidgetManager.getInstance(context)
                    // Partially update header (title + date) without resetting adapter
                    partialHeaderUpdate(context, mgr, appWidgetId)
                    // Slight delay before data refresh to avoid host cache glitches
                    Handler(Looper.getMainLooper()).postDelayed({
                        mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
                    }, 150)
                }
            }
            ACTION_MANUAL_REFRESH -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                Log.d(TAG, "onReceive: ACTION_MANUAL_REFRESH appWidgetId=$appWidgetId")
                val mgr = AppWidgetManager.getInstance(context)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    // Keep header in sync via partial update; no full relayout
                    partialHeaderUpdate(context, mgr, appWidgetId)
                    Handler(Looper.getMainLooper()).postDelayed({
                        mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
                    }, 150)
                } else {
                    val cn = ComponentName(context, TodoWidgetProvider::class.java)
                    val ids = mgr.getAppWidgetIds(cn)
                    ids.forEach { id ->
                        partialHeaderUpdate(context, mgr, id)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        ids.forEach { id -> mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list) }
                    }, 150)
                }
            }
            ACTION_FORCE_RELOAD -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                Log.d(TAG, "onReceive: ACTION_FORCE_RELOAD appWidgetId=$appWidgetId")
                val mgr = AppWidgetManager.getInstance(context)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    forceReload(context, mgr, appWidgetId)
                } else {
                    val cn = ComponentName(context, TodoWidgetProvider::class.java)
                    val ids = mgr.getAppWidgetIds(cn)
                    ids.forEach { id -> forceReload(context, mgr, id) }
                }
            }
        }
    }

    companion object {
        const val ACTION_MARK_DONE = "com.brill.zero.widget.ACTION_MARK_DONE"
        const val ACTION_NEXT_DAY = "com.brill.zero.widget.ACTION_NEXT_DAY"
        const val ACTION_MANUAL_REFRESH = "com.brill.zero.widget.ACTION_MANUAL_REFRESH"
        const val ACTION_FORCE_RELOAD = "com.brill.zero.widget.ACTION_FORCE_RELOAD"
        const val EXTRA_TODO_ID = "todo_id"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val t0 = SystemClock.uptimeMillis()
            val views = RemoteViews(context.packageName, R.layout.widget_todo)

            // Resolve cached offset (only persists for the day it was set)
            val prefs = prefs(context)
            val key = "offset_$appWidgetId"
            val todayKey = "offset_cached_day_$appWidgetId"
            var offset = prefs.getInt(key, 0)
            val calNow = java.util.Calendar.getInstance()
            val today = calNow.get(java.util.Calendar.DAY_OF_YEAR)
            val cachedDay = prefs.getInt(todayKey, -1)
            if (offset == 1 && cachedDay != today) {
                offset = 0
                prefs.edit().putInt(key, 0).apply()
            }
            Log.d(TAG, "updateAppWidget: id=$appWidgetId offset=$offset cachedDay=$cachedDay today=$today")
            Log.d(TAG, "updateAppWidget: metrics=${metricsStr(context)} options=${optionsStr(appWidgetManager, appWidgetId)}")

            // Header content (title and date)
            views.setTextViewText(R.id.widget_header_title, "Zero To-Do")
            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
            val dateStr = java.text.SimpleDateFormat("MMMM d EEE", java.util.Locale.ENGLISH).format(cal.time)
            views.setTextViewText(R.id.widget_header_date, dateStr)
            Log.d(TAG, "updateAppWidget: dateStr=$dateStr")

            // Scale guard: ensure root scale stays at 1 after host interactions
            views.setFloat(R.id.widget_content, "setScaleX", SCALE)
            views.setFloat(R.id.widget_content, "setScaleY", SCALE)

            // Set the remote adapter for the list to our service
            val svcIntent = Intent(context, TodoWidgetService::class.java)
            svcIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // Stable adapter URI to avoid host rebinds that may change scaling
            svcIntent.data = Uri.parse(svcIntent.toUri(Intent.URI_INTENT_SCHEME))
            Log.d(TAG, "updateAppWidget: adapterUri=${svcIntent.data}")
            views.setRemoteAdapter(R.id.widget_list, svcIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // PendingIntent template: launch app directly to To‑Do screen
            val template = Intent(context, MainActivity::class.java).apply {
                putExtra("open_route", "todos")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val piTemplate = PendingIntent.getActivity(context, appWidgetId, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setPendingIntentTemplate(R.id.widget_list, piTemplate)

            // Next-day toggle button
            val nextIntent = Intent(context, TodoWidgetProvider::class.java).apply {
                action = ACTION_NEXT_DAY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val nextPi = PendingIntent.getBroadcast(context, appWidgetId + 1000, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_header_arrow, nextPi)

            // Manual refresh button
            val refreshIntent = Intent(context, TodoWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val refreshPi = PendingIntent.getBroadcast(context, appWidgetId + 2000, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_header_refresh, refreshPi)
            // Reset button: force reload this widget instance
            val resetIntent = Intent(context, TodoWidgetProvider::class.java).apply {
                action = ACTION_FORCE_RELOAD
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val resetPi = PendingIntent.getBroadcast(context, appWidgetId + 3000, resetIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_header_reset, resetPi)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            // Ensure list refresh to avoid stuck loading state
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
            val dt = SystemClock.uptimeMillis() - t0
            Log.d(TAG, "updateAppWidget: notify data changed for list, took=${dt}ms")
        }

        // Header-only partial update: title + date (no adapter reset)
        fun partialHeaderUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val t0 = SystemClock.uptimeMillis()
            val views = RemoteViews(context.packageName, R.layout.widget_todo)
            val prefs = prefs(context)
            val key = "offset_$appWidgetId"
            val todayKey = "offset_cached_day_$appWidgetId"
            var offset = prefs.getInt(key, 0)
            val calNow = java.util.Calendar.getInstance()
            val today = calNow.get(java.util.Calendar.DAY_OF_YEAR)
            val cachedDay = prefs.getInt(todayKey, -1)
            if (offset == 1 && cachedDay != today) {
                offset = 0
                prefs.edit().putInt(key, 0).apply()
            }
            views.setTextViewText(R.id.widget_header_title, "Zero To-Do")
            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
            val dateStr = java.text.SimpleDateFormat("MMMM d EEE.", java.util.Locale.ENGLISH).format(cal.time)
            views.setTextViewText(R.id.widget_header_date, dateStr)
            // Scale guard on partial updates
            views.setFloat(R.id.widget_content, "setScaleX", SCALE)
            views.setFloat(R.id.widget_content, "setScaleY", SCALE)
            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
            val dt = SystemClock.uptimeMillis() - t0
            Log.d(TAG, "partialHeaderUpdate: id=$appWidgetId date=$dateStr metrics=${metricsStr(context)} options=${optionsStr(appWidgetManager, appWidgetId)} took=${dt}ms")
        }

        fun forceReload(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val t0 = SystemClock.uptimeMillis()
            // Normalize min/max width/height to the current min values to avoid host mismatch
            val opts = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)
            val stable = android.os.Bundle(opts).apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minW)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minH)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH)
            }
            appWidgetManager.updateAppWidgetOptions(appWidgetId, stable)
            // Extreme stability: avoid full update; only partial header + list refresh after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                partialHeaderUpdate(context, appWidgetManager, appWidgetId)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
                val dt = SystemClock.uptimeMillis() - t0
                Log.d(TAG, "forceReload: id=$appWidgetId metrics=${metricsStr(context)} options=${optionsStr(appWidgetManager, appWidgetId)} took=${dt}ms")
            }, 3000)
        }

        private fun optionsStr(mgr: AppWidgetManager, id: Int): String {
            return runCatching {
                val o = mgr.getAppWidgetOptions(id)
                val minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1)
                val minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, -1)
                val maxW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, -1)
                val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, -1)
                val cat = o.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, -1)
                "min(${minW}dp,${minH}dp) max(${maxW}dp,${maxH}dp) cat=${cat}"
            }.getOrElse { "<no-opts:${it.javaClass.simpleName}>" }
        }

        private fun metricsStr(ctx: Context): String {
            val dm = ctx.resources.displayMetrics
            val cfg = ctx.resources.configuration
            return "dpi=${dm.densityDpi} dens=${dm.density} sDens=${dm.scaledDensity} font=${cfg.fontScale} sw=${cfg.smallestScreenWidthDp} orien=${cfg.orientation}"
        }
        
        private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("todo_widget_prefs", Context.MODE_PRIVATE)
    }
}