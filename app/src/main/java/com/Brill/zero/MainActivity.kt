package com.Brill.zero
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.Brill.zero.ui.ZeroTheme
import com.Brill.zero.ui.nav.ZeroNavGraph


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZeroTheme {
                Surface { ZeroNavGraph() }
            }
        }
    }
}