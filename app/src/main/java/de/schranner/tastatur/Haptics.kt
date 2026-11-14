package de.schranner.tastatur

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibration beim Tippen mit einstellbarer Stärke (0 = aus, 100 = kräftig). */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) { null }

    var strength = 40

    fun tap() = pulse(strength)

    /** Etwas schwächer, z. B. für jeden Schritt beim Cursor-Wischen. */
    fun tick() = pulse(strength * 6 / 10)

    private fun pulse(s: Int) {
        if (s <= 0) return
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            val ms = durationMs(s)
            val effect = if (v.hasAmplitudeControl()) VibrationEffect.createOneShot(ms, amplitude(s))
            else VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            v.vibrate(effect)
        } catch (_: Exception) { }
    }

    companion object {
        /** Dauer wächst mit der Stärke: 6 bis 30 ms. */
        fun durationMs(strength: Int): Long = (6 + strength.coerceIn(0, 100) * 0.24f).toLong()

        /** Ausschlag 1..255 (nur auf Handys, die das unterstützen). */
        fun amplitude(strength: Int): Int = (25 + strength.coerceIn(0, 100) * 2.3f).toInt().coerceIn(1, 255)
    }
}
