package de.schranner.tastatur

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Fühlbare Rückmeldung beim Tippen – je nach Aktion unterschiedlich, ähnlich wie beim iPhone:
 * ein feines Ticken für Buchstaben, ein etwas kräftigerer Klick für Leertaste und Löschen,
 * ein deutlicher „Plopp“, wenn sich bei langem Druck etwas öffnet, ein sehr leises Ticken
 * für jeden Schritt beim Cursor-Verschieben.
 *
 * Nutzt die präzisen Effekte moderner Vibrationsmotoren, wenn das Handy sie hat; sonst kurze Impulse.
 * Die Stärke (0 = aus bis 100) kommt aus den Einstellungen.
 */
class Haptics(context: Context) {

    enum class Kind { KEY, SPECIAL, POPUP, TICK, CONFIRM, REJECT }

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) { null }

    /** Kann das Handy die feinen, vordefinierten Effekte abspielen? */
    private val primitives: Boolean = try {
        Build.VERSION.SDK_INT >= 31 && vibrator?.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        ) == true
    } catch (_: Exception) { false }

    var strength = 40

    fun tap() = play(Kind.KEY)
    fun tick() = play(Kind.TICK)

    fun play(kind: Kind) {
        val s = strength
        if (s <= 0) return
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            v.vibrate(effect(kind, s, v))
        } catch (_: Exception) { }
    }

    private fun effect(kind: Kind, s: Int, v: Vibrator): VibrationEffect {
        if (primitives && Build.VERSION.SDK_INT >= 31) {
            val c = VibrationEffect.startComposition()
            val scale = scale(kind, s)
            when (kind) {
                Kind.KEY -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                Kind.SPECIAL -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                Kind.POPUP -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                Kind.TICK -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
                Kind.CONFIRM -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale, 60)
                Kind.REJECT -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 90)
            }
            return c.compose()
        }
        val ms = durationMs(kind, s)
        if (kind == Kind.CONFIRM || kind == Kind.REJECT) {
            val gap = if (kind == Kind.CONFIRM) 50L else 80L
            return VibrationEffect.createWaveform(longArrayOf(0, ms, gap, ms), -1)
        }
        return if (v.hasAmplitudeControl()) VibrationEffect.createOneShot(ms, amplitude(kind, s))
        else VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    companion object {
        /** Wie kräftig eine Aktion im Verhältnis zur eingestellten Stärke ist. */
        private fun weight(kind: Kind) = when (kind) {
            Kind.KEY -> 0.75f
            Kind.SPECIAL -> 0.9f
            Kind.POPUP -> 1.0f
            Kind.TICK -> 0.5f
            Kind.CONFIRM -> 0.7f
            Kind.REJECT -> 1.0f
        }

        /** Stärke eines feinen Effekts, 0..1. */
        fun scale(kind: Kind, strength: Int): Float =
            ((0.15f + strength.coerceIn(0, 100) / 100f * 0.85f) * weight(kind)).coerceIn(0.05f, 1f)

        /** Dauer eines einfachen Impulses: 4 bis 30 ms. */
        fun durationMs(kind: Kind, strength: Int): Long =
            ((6 + strength.coerceIn(0, 100) * 0.24f) * weight(kind)).toLong().coerceAtLeast(3)

        /** Ausschlag 1..255 (nur auf Handys, die das unterstützen). */
        fun amplitude(kind: Kind, strength: Int): Int =
            ((25 + strength.coerceIn(0, 100) * 2.3f) * weight(kind)).toInt().coerceIn(1, 255)
    }
}
