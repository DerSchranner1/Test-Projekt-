package de.schranner.tastatur

import android.content.Context
import android.graphics.Color

/**
 * Eigenes dunkles Design der KlarTastatur: tiefes Petrol-Schwarz mit türkisem Akzent
 * (bewusst anders als Gboard, gleich kompakte Maße). Nur Dark Mode.
 */
@Suppress("UNUSED_PARAMETER")
class KeyboardTheme(context: Context) {
    val dark = true

    val background = Color.parseColor("#11171A")
    val key = Color.parseColor("#262E33")
    val keyPressed = Color.parseColor("#3D4950")
    val specialKey = Color.parseColor("#323C42")
    val accent = Color.parseColor("#5FD3C4")
    val accentText = Color.parseColor("#00312C")
    val text = Color.parseColor("#E6ECEE")
    val hint = Color.parseColor("#A9B6BC")
    val barText = text
    val divider = Color.parseColor("#1E272B")
    val typo = Color.parseColor("#FF8A80")
    val recording = Color.parseColor("#FF8A80")
    val popup = Color.parseColor("#3A464D")
    val popupSelected = accent
}
