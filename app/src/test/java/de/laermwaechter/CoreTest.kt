package de.laermwaechter

import de.laermwaechter.audio.AWeighting
import de.laermwaechter.core.AlertEngine
import de.laermwaechter.core.EnergyEstimate
import de.laermwaechter.core.LevelMath
import de.laermwaechter.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AWeightingTest {

    /** Gain in dB the filter applies to a sine of [freq] Hz. */
    private fun gainDb(freq: Double, rate: Int): Double {
        val filter = AWeighting(rate)
        var inSq = 0.0
        var outSq = 0.0
        for (n in 0 until rate * 2) {
            val x = sin(2 * PI * freq * n / rate)
            val y = filter.process(x)
            if (n >= rate) { // let the filter settle for one second first
                inSq += x * x
                outSq += y * y
            }
        }
        return 10 * log10(outSq / inSq)
    }

    @Test
    fun matchesTheStandardCurve() {
        // Reference values from IEC 61672-1, table 2.
        val expected = mapOf(
            31.5 to -39.4, 63.0 to -26.2, 125.0 to -16.1, 250.0 to -8.6, 500.0 to -3.2,
            1000.0 to 0.0, 2000.0 to 1.2, 4000.0 to 1.0, 8000.0 to -1.1,
        )
        for (rate in intArrayOf(48000, 44100)) {
            for ((freq, db) in expected) {
                assertEquals("$freq Hz at $rate Hz", db, gainDb(freq, rate), 0.3)
            }
        }
    }
}

class LevelMathTest {

    @Test
    fun referenceLevelMapsTo90Db() {
        assertEquals(90.0, LevelMath.rmsToDb(2500.0, 0.0), 1e-9)
        assertEquals(96.0, LevelMath.rmsToDb(5000.0, 0.0), 0.03)
        assertEquals(87.0, LevelMath.rmsToDb(2500.0, -3.0), 1e-9)
        assertEquals(LevelMath.MIN_DB, LevelMath.rmsToDb(0.0, 0.0), 1e-9)
    }

    @Test
    fun doseFollowsThe3DbRule() {
        val hour = 3_600_000L
        assertEquals(100.0, LevelMath.dosePercent(85.0, 8 * hour), 1e-6)
        assertEquals(100.0, LevelMath.dosePercent(88.0, 4 * hour), 1e-6)
        assertEquals(100.0, LevelMath.dosePercent(100.0, hour / 4), 1e-6)
        assertEquals(0.0, LevelMath.dosePercent(79.9, 8 * hour), 1e-9)
    }
}

class AlertEngineTest {
    private val settings = Settings(thresholdDb = 85, durationSec = 180, cooldownMin = 15)
    private val gap = 30_000L

    /** Feeds one reading every [gap] ms and returns the times at which a warning fired. */
    private fun run(engine: AlertEngine, start: Long, count: Int, s: Settings = settings, level: (Int) -> Double): List<Long> {
        val alerts = mutableListOf<Long>()
        for (i in 0 until count) {
            val t = start + i * gap
            if (engine.add(t, level(i), gap, s).alert) alerts += t
        }
        return alerts
    }

    @Test
    fun warnsOnlyAfterTheFullTimeSpan() {
        val alerts = run(AlertEngine(), 0, 20) { 92.0 }
        assertEquals(listOf(180_000L), alerts)
    }

    @Test
    fun shortBangDoesNotWarn() {
        // One 100 dB reading among 60 dB ones averages to about 92 dB over 3 min, so use a
        // realistic bang: one reading of 90 dB. Average stays well under the threshold.
        val alerts = run(AlertEngine(), 0, 40) { if (it == 10) 90.0 else 60.0 }
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun instantModeWarnsOnFirstLoudReading() {
        val instant = settings.copy(durationSec = 0)
        val alerts = run(AlertEngine(), 0, 5, instant) { if (it == 2) 95.0 else 60.0 }
        assertEquals(listOf(60_000L), alerts)
    }

    @Test
    fun cooldownSpacesWarnings() {
        val alerts = run(AlertEngine(), 0, 80) { 95.0 } // 40 minutes of noise
        assertEquals(listOf(180_000L, 1_080_000L, 1_980_000L), alerts)
    }

    @Test
    fun averageIsEnergyBased() {
        val engine = AlertEngine()
        engine.add(0, 60.0, gap, settings)
        val result = engine.add(gap, 90.0, gap, settings)
        // Half the time at 90 dB is 87 dB, not the arithmetic 75 dB.
        assertEquals(87.0, result.windowDb, 0.05)
    }

    @Test
    fun longHoleStartsObservationAgain() {
        val engine = AlertEngine()
        run(engine, 0, 5) { 92.0 } // 2 minutes, not enough yet
        // Phone was unreachable for 10 minutes; the old readings must not complete the span.
        val alerts = run(engine, 720_000, 3) { 92.0 }
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun quietAverageNeverWarns() {
        assertFalse(run(AlertEngine(), 0, 100) { 70.0 }.isNotEmpty())
    }
}

class SettingsTest {
    @Test
    fun adaptiveShortensThePauseNearTheThreshold() {
        val s = Settings(thresholdDb = 85, intervalSec = 30, adaptive = true)
        assertEquals(30, s.intervalSecFor(74.9))
        assertEquals(5, s.intervalSecFor(75.0))
        assertEquals(50, s.copy(intervalSec = 300).intervalSecFor(90.0))
        assertEquals(5, s.copy(intervalSec = 5).intervalSecFor(90.0))
        assertEquals(30, s.copy(adaptive = false).intervalSecFor(90.0))
    }
}

class EnergyEstimateTest {
    @Test
    fun defaultsAreCheapAndWakeLockModeIsNot() {
        val sleeping = EnergyEstimate.percentPerDay(30, 500, exactAlarmsAllowed = true)
        val awake = EnergyEstimate.percentPerDay(30, 500, exactAlarmsAllowed = false)
        assertTrue("sleeping=$sleeping", sleeping in 0.5..3.0)
        assertTrue("awake=$awake", awake > 3 * sleeping)
        // Shorter pauses must cost more.
        assertTrue(EnergyEstimate.percentPerDay(10, 500, true) > sleeping)
        assertTrue(sqrt(EnergyEstimate.dutyCycle(5, 2000)) <= 1.0)
    }
}
