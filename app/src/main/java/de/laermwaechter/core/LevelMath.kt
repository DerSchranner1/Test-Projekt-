package de.laermwaechter.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

object LevelMath {
    /**
     * Android's compatibility definition asks devices to map 90 dB SPL at 1 kHz to an RMS of
     * 2500 (16-bit) for the VOICE_RECOGNITION source. Real phones deviate by a few dB, which is
     * what the user-facing calibration offset is for.
     */
    private const val REFERENCE_RMS = 2500.0
    private const val REFERENCE_DB = 90.0

    const val MIN_DB = 20.0

    fun rmsToDb(rms: Double, calibrationDb: Double): Double {
        if (rms <= 0.0) return MIN_DB
        return max(MIN_DB, 20 * log10(rms / REFERENCE_RMS) + REFERENCE_DB + calibrationDb)
    }

    /** Hours per day a level is considered safe: 8 h at 85 dB(A), halved for every +3 dB (NIOSH). */
    fun safeHoursPerDay(db: Double): Double = 8.0 * 2.0.pow((85.0 - db) / 3.0)

    /**
     * Share of the daily noise allowance (in percent) used up by [durationMs] at [db].
     * Levels below 80 dB(A) do not count, as in the NIOSH method.
     */
    fun dosePercent(db: Double, durationMs: Long): Double {
        if (db < 80.0 || durationMs <= 0) return 0.0
        return 100.0 * durationMs / (safeHoursPerDay(db) * 3_600_000.0)
    }
}
