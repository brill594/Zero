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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read desired start route from Intent extras (e.g., from widget)
        val startRouteExtra = intent?.getStringExtra("open_route")
        setContent {
            ZeroTheme {
                val startRoute = startRouteExtra ?: "todos"
                Surface { ZeroNavGraph(startDestination = startRoute) }
            }
        }

    }

    // Keep widget updates confined to AppWidgetProvider/Repository using applicationContext
}