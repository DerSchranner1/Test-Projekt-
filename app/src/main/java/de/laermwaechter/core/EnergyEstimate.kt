package de.laermwaechter.core

import kotlin.math.min

/**
 * Rough battery model. The currents are typical values for current phones, not measurements
 * of this device, so the result is an order of magnitude and is labelled as such in the UI.
 */
object EnergyEstimate {
    /** Waking up, starting the microphone, settling and going back to sleep, per reading. */
    const val OVERHEAD_MS = 700

    /** Pauses shorter than this are bridged awake; waking the phone up would cost more. */
    const val MIN_SLEEP_GAP_SEC = 10

    private const val ACTIVE_MA = 60.0
    private const val AWAKE_IDLE_MA = 12.0
    const val DEFAULT_CAPACITY_MAH = 4500.0

    /** Share of time the microphone and processor are busy, 0..1. */
    fun dutyCycle(intervalSec: Int, measureMs: Int): Double =
        min(1.0, (measureMs + OVERHEAD_MS) / (intervalSec * 1000.0 + measureMs))

    fun sleepsBetween(intervalSec: Int, exactAlarmsAllowed: Boolean): Boolean =
        exactAlarmsAllowed && intervalSec >= MIN_SLEEP_GAP_SEC

    fun milliampHoursPerDay(intervalSec: Int, measureMs: Int, exactAlarmsAllowed: Boolean): Double {
        val duty = dutyCycle(intervalSec, measureMs)
        val idle = if (sleepsBetween(intervalSec, exactAlarmsAllowed)) 0.0 else AWAKE_IDLE_MA * (1 - duty)
        return 24 * (duty * ACTIVE_MA + idle)
    }

    fun percentPerDay(
        intervalSec: Int,
        measureMs: Int,
        exactAlarmsAllowed: Boolean,
        capacityMah: Double = DEFAULT_CAPACITY_MAH,
    ): Double = 100 * milliampHoursPerDay(intervalSec, measureMs, exactAlarmsAllowed) / capacityMah
}
