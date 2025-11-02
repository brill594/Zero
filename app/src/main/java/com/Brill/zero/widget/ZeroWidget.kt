package com.Brill.zero.widget


import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.Brill.zero.data.repo.ZeroRepository
import kotlinx.coroutines.flow.first


class ZeroWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = ZeroRepository.get(context)
        val highs = repo.streamByPriority("HIGH").first()
        provideContent {
            Column(modifier = GlanceModifier.padding(12.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text("ZERO", style = TextStyle(color = ColorProvider(0xFFFFFFFF.toInt())))
                Spacer(Modifier.height(8.dp))
                if (highs.isEmpty()) {
                    Text("No high‑priority push")
                } else {
                    highs.take(3).forEach { n -> Text("• ${'$'}{n.title ?: n.text}") }
                }
            }
        }
    }


    companion object {
        suspend fun updateAll(context: Context) {
            GlanceAppWidgetManager(context).getGlanceIds(ZeroWidget::class.java).forEach { id ->
                ZeroWidget().update(context, id)
            }
        }
    }
}