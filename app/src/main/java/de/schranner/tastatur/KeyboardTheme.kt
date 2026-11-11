package de.schranner.tastatur

import android.content.Context
import android.graphics.Color

/** Dunkles Design (nur Dark Mode), angelehnt an aktuelle Android-Tastaturen. */
@Suppress("UNUSED_PARAMETER")
class KeyboardTheme(context: Context) {
    val dark = true

    val background = Color.parseColor("#17181A")
    val key = Color.parseColor("#2D2E31")
    val keyPressed = Color.parseColor("#4A4C50")
    val specialKey = Color.parseColor("#232427")
    val accent = Color.parseColor("#A8C7FA")
    val accentText = Color.parseColor("#062E6F")
    val text = Color.parseColor("#E8EAED")
    val hint = Color.parseColor("#9AA0A6")
    val barText = text
    val divider = Color.parseColor("#2E2F33")
    val typo = Color.parseColor("#F28B82")
    val recording = Color.parseColor("#F28B82")
    val popup = Color.parseColor("#3A3B3F")
    val popupSelected = accent
}
