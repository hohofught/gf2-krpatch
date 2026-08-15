package com.hoho.snqxkr

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF3B608F),
    secondary = Color(0xFF54617A),
    tertiary = Color(0xFF6D5677),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA4C9FE),
    secondary = Color(0xFFBBC7E4),
    tertiary = Color(0xFFDABDE3),
)

/** Material You: 안드 12+ 에서는 기기 배경화면 색을 그대로 따라간다 */
@Composable
fun SnqxKRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
