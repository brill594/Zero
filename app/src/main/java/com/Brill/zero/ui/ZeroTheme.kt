package com.Brill.zero.ui


import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable


private val ZeroScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
)


@Composable
fun ZeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZeroScheme,
        typography = Typography(),
        content = content
    )
}