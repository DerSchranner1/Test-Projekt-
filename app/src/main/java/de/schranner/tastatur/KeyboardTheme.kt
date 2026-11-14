package de.schranner.tastatur

import android.content.Context
import android.graphics.Color

/** Dunkles Design (nur Dark Mode). Farben aus einer Gboard im dunklen Design gemessen. */
@Suppress("UNUSED_PARAMETER")
class KeyboardTheme(context: Context) {
    val dark = true

    val background = Color.parseColor("#1E1F21")
    val key = Color.parseColor("#38393B")
    val keyPressed = Color.parseColor("#5A5C5F")
    val specialKey = Color.parseColor("#454746")
    val accent = Color.parseColor("#A8C8FB")
    val accentText = Color.parseColor("#062E6F")
    val text = Color.parseColor("#E2E4E3")
    val hint = Color.parseColor("#C4C7C5")
    val barText = text
    val divider = Color.parseColor("#2E2F33")
    val typo = Color.parseColor("#F28B82")
    val recording = Color.parseColor("#F28B82")
    val popup = Color.parseColor("#4A4C4F")
    val popupSelected = accent
}
