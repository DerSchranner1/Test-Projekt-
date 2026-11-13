package de.laermwaechter.ui

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

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF80CBC4), secondary = Color(0xFF80CBC4))
        else -> lightColorScheme(primary = Color(0xFF00695C), secondary = Color(0xFF00695C))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Traffic-light colours for the level, readable on light and dark backgrounds. */
data class LevelColors(val quiet: Color, val near: Color, val loud: Color)

@Composable
fun levelColors(): LevelColors =
    if (isSystemInDarkTheme()) {
        LevelColors(quiet = Color(0xFF81C784), near = Color(0xFFFFB74D), loud = Color(0xFFEF9A9A))
    } else {
        LevelColors(quiet = Color(0xFF2E7D32), near = Color(0xFF9A5B00), loud = Color(0xFFC62828))
    }
