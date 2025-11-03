package com.brill.zero.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.brill.zero.data.repo.ZeroRepository
import kotlinx.coroutines.flow.first

class ZeroWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = ZeroRepository.get(context)
        val highs = repo.streamByPriority("HIGH").first()

        provideContent {
            Column(
                modifier = GlanceModifier.padding(12.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = "ZERO",
                    // 先用默认颜色，避免受限 API
                    style = TextStyle()
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
                if (highs.isEmpty()) {
                    Text("No high-priority push")
                } else {
                    highs.take(3).forEach { n ->
                        Text("• ${n.title ?: n.text}")
                    }
                }
            }
        }
    }

    companion object {
        suspend fun updateAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(ZeroWidget::class.java).forEach { id ->
                ZeroWidget().update(context, id)
            }
        }
    }
}
