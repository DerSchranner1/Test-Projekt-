package de.schranner.tastatur

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** Farben für helles und dunkles Design – folgt automatisch dem System. */
class KeyboardTheme(context: Context) {
    val dark: Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    val background = if (dark) Color.parseColor("#1B1C1F") else Color.parseColor("#E8EAED")
    val key = if (dark) Color.parseColor("#3A3B3F") else Color.WHITE
    val keyPressed = if (dark) Color.parseColor("#5A5C61") else Color.parseColor("#C9CDD3")
    val specialKey = if (dark) Color.parseColor("#2A2B2E") else Color.parseColor("#C4C8CF")
    val accent = Color.parseColor("#1E6FD9")
    val accentText = Color.WHITE
    val text = if (dark) Color.parseColor("#F1F3F4") else Color.parseColor("#202124")
    val hint = if (dark) Color.parseColor("#9AA0A6") else Color.parseColor("#80868B")
    val barText = text
    val divider = if (dark) Color.parseColor("#3C4043") else Color.parseColor("#C4C8CF")
    val typo = Color.parseColor("#D93025")
    val recording = Color.parseColor("#D93025")
    val popup = if (dark) Color.parseColor("#4A4C51") else Color.WHITE
    val popupSelected = accent
}
