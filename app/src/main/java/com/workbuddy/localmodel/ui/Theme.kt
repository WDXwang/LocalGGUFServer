package com.workbuddy.localmodel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E6FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF52606D),
    surfaceVariant = Color(0xFFEEF1F5)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC3FF),
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF174A8B),
    onPrimaryContainer = Color(0xFFD7E6FF),
    secondary = Color(0xFFB8C5D3),
    surfaceVariant = Color(0xFF2A3038)
)

@Composable
fun LocalModelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
