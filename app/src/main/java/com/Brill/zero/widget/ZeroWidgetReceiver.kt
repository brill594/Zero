package com.brill.zero.widget


import androidx.glance.appwidget.GlanceAppWidgetReceiver

class ZeroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ZeroWidget()
}