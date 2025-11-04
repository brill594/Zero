package com.brill.zero
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.brill.zero.ui.ZeroTheme
import com.brill.zero.ui.nav.ZeroNavGraph


class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // If launched via widget route, request a hard reload through the provider (uses applicationContext)
        if (intent.getStringExtra("open_route") != null) {
            val reload = Intent(this, com.brill.zero.widget.TodoWidgetProvider::class.java).apply {
                action = com.brill.zero.widget.TodoWidgetProvider.ACTION_FORCE_RELOAD
            }
            android.os.Handler(mainLooper).postDelayed({ sendBroadcast(reload) }, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read desired start route from Intent extras (e.g., from widget)
        val startRouteExtra = intent?.getStringExtra("open_route")
        setContent {
            ZeroTheme {
                val startRoute = startRouteExtra ?: "dashboard"
                Surface { ZeroNavGraph(startDestination = startRoute) }
            }
        }

        // If launched via widget, schedule a hard reload via provider after delay
        if (startRouteExtra != null) {
            val reload = Intent(this, com.brill.zero.widget.TodoWidgetProvider::class.java).apply {
                action = com.brill.zero.widget.TodoWidgetProvider.ACTION_FORCE_RELOAD
            }
            android.os.Handler(mainLooper).postDelayed({ sendBroadcast(reload) }, 3000)
        }
    }

    // Keep widget updates confined to AppWidgetProvider/Repository using applicationContext
}