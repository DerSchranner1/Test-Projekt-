package de.laermwaechter.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * A-weighting filter (IEC 61672) as a cascade of first-order sections, derived from the
 * analog prototype with a pre-warped bilinear transform and normalised to 0 dB at 1 kHz.
 *
 * A-weighting mimics how sensitive the ear is at each frequency, so the result is in dB(A),
 * the unit hearing-protection limits are defined in.
 */
class AWeighting(sampleRate: Int) {

    private class Section(val b0: Double, val b1: Double, val a1: Double) {
        var x1 = 0.0
        var y1 = 0.0
    }

    private val sections: List<Section>
    private val gain: Double

    init {
        val fs = sampleRate.toDouble()
        val list = mutableListOf<Section>()
        // Analog prototype: four zeros at 0 Hz, poles at 20.6 Hz (x2), 107.7 Hz, 737.9 Hz, 12194 Hz (x2).
        for (f in doubleArrayOf(F1, F1, F2, F3)) list += highPass(f, fs)
        // The 12.2 kHz low-pass pair only exists below Nyquist.
        if (F4 < fs * 0.45) repeat(2) { list += lowPass(F4, fs) }
        sections = list
        gain = 1.0 / magnitudeAt(1000.0, fs)
    }

    fun process(x: Double): Double {
        var v = x
        for (s in sections) {
            val y = s.b0 * v + s.b1 * s.x1 - s.a1 * s.y1
            s.x1 = v
            s.y1 = y
            v = y
        }
        return v * gain
    }

    private fun magnitudeAt(freq: Double, fs: Double): Double {
        val w = 2 * PI * freq / fs
        val re = cos(w)
        val im = -sin(w)
        var mag = 1.0
        for (s in sections) {
            mag *= hypot(s.b0 + s.b1 * re, s.b1 * im) / hypot(1 + s.a1 * re, s.a1 * im)
        }
        return mag
    }

    private companion object {
        const val F1 = 20.598997
        const val F2 = 107.65265
        const val F3 = 737.86223
        const val F4 = 12194.217
        const val MATCH_HZ = 8000.0

        fun highPass(f: Double, fs: Double): Section {
            val k = 2 * fs
            val w = k * tan(PI * f / fs)
            return Section(k / (k + w), -k / (k + w), (w - k) / (k + w))
        }

        /**
         * The bilinear transform squeezes high frequencies together. Warping the pole so the
         * response is exact at [MATCH_HZ] keeps the top of the audible band within 0.3 dB;
         * warping at the pole itself (12.2 kHz) would be 0.7 dB off at 8 kHz.
         */
        fun lowPass(f: Double, fs: Double): Section {
            val k = 2 * fs
            val w = k * tan(PI * MATCH_HZ / fs) * f / MATCH_HZ
            return Section(w / (k + w), w / (k + w), (w - k) / (k + w))
        }
    }
}
