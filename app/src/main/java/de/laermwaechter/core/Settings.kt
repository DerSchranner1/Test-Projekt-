package de.laermwaechter.core

import android.content.Context
import kotlin.math.max

data class Settings(
    /** Warn when the average reaches this level, in dB(A). */
    val thresholdDb: Int = 85,
    /** The average is taken over this many seconds; 0 warns on a single loud reading. */
    val durationSec: Int = 180,
    /** Minutes of silence after a warning. */
    val cooldownMin: Int = 15,
    val vibrate: Boolean = true,
    val sound: Boolean = true,
    /** Extra warning when the daily noise dose reaches 100 %. */
    val doseWarning: Boolean = true,
    /** Pause between two readings while it is quiet. */
    val intervalSec: Int = 30,
    /** Length of one reading. */
    val measureMs: Int = 500,
    /** Measure more often while the level is close to the threshold. */
    val adaptive: Boolean = true,
    /** Correction for this phone's microphone, in dB. */
    val calibrationDb: Int = 0,
) {
    /** Pause between readings once it gets loud (adaptive mode). */
    val fastIntervalSec: Int
        get() = if (adaptive) max(MIN_INTERVAL_SEC, intervalSec / 6).coerceAtMost(intervalSec) else intervalSec

    fun intervalSecFor(db: Double): Int =
        if (db >= thresholdDb - ADAPTIVE_MARGIN_DB) fastIntervalSec else intervalSec

    companion object {
        const val MIN_INTERVAL_SEC = 5
        const val ADAPTIVE_MARGIN_DB = 10
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            thresholdDb = prefs.getInt("thresholdDb", d.thresholdDb),
            durationSec = prefs.getInt("durationSec", d.durationSec),
            cooldownMin = prefs.getInt("cooldownMin", d.cooldownMin),
            vibrate = prefs.getBoolean("vibrate", d.vibrate),
            sound = prefs.getBoolean("sound", d.sound),
            doseWarning = prefs.getBoolean("doseWarning", d.doseWarning),
            intervalSec = prefs.getInt("intervalSec", d.intervalSec),
            measureMs = prefs.getInt("measureMs", d.measureMs),
            adaptive = prefs.getBoolean("adaptive", d.adaptive),
            calibrationDb = prefs.getInt("calibrationDb", d.calibrationDb),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putInt("thresholdDb", s.thresholdDb)
            .putInt("durationSec", s.durationSec)
            .putInt("cooldownMin", s.cooldownMin)
            .putBoolean("vibrate", s.vibrate)
            .putBoolean("sound", s.sound)
            .putBoolean("doseWarning", s.doseWarning)
            .putInt("intervalSec", s.intervalSec)
            .putInt("measureMs", s.measureMs)
            .putBoolean("adaptive", s.adaptive)
            .putInt("calibrationDb", s.calibrationDb)
            .apply()
    }

    /** Whether the user wants monitoring on; used to offer a restart after a reboot. */
    var monitoringEnabled: Boolean
        get() = prefs.getBoolean("monitoringEnabled", false)
        set(value) = prefs.edit().putBoolean("monitoringEnabled", value).apply()

    /** Today's noise dose, kept across restarts of the service. */
    var doseDay: String
        get() = prefs.getString("doseDay", "") ?: ""
        set(value) = prefs.edit().putString("doseDay", value).apply()

    var dosePercent: Float
        get() = prefs.getFloat("dosePercent", 0f)
        set(value) = prefs.edit().putFloat("dosePercent", value).apply()

    var doseWarned: Boolean
        get() = prefs.getBoolean("doseWarned", false)
        set(value) = prefs.edit().putBoolean("doseWarned", value).apply()
}
