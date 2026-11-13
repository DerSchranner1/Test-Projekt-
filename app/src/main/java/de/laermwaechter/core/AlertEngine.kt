package de.laermwaechter.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Decides when a warning is due: the energy average (Leq) over the configured time span has to
 * reach the threshold, and the span has to be fully observed, so a slammed door does not count
 * as three loud minutes.
 */
class AlertEngine {

    data class Result(val windowDb: Double, val alert: Boolean)

    private class Sample(val time: Long, val db: Double, val weightMs: Long)

    private val samples = ArrayDeque<Sample>()
    private var observingSince = 0L
    private var lastAlertAt: Long? = null

    fun reset() {
        samples.clear()
        lastAlertAt = null
    }

    /**
     * @param nowMs monotonic time of the reading
     * @param plannedGapMs the pause that was scheduled before this reading
     */
    fun add(nowMs: Long, db: Double, plannedGapMs: Long, settings: Settings): Result {
        val previous = samples.lastOrNull()
        val gap = if (previous == null) plannedGapMs else nowMs - previous.time
        // After a long hole (phone could not wake us up) the old readings say nothing about now.
        if (previous == null || gap > max(3 * plannedGapMs, MAX_HOLE_MS)) {
            samples.clear()
            observingSince = nowMs
        }
        val weight = if (samples.isEmpty()) plannedGapMs else gap
        samples.addLast(Sample(nowMs, db, weight.coerceAtLeast(1)))
        while (samples.first().time < nowMs - KEEP_MS) samples.removeFirst()

        val spanMs = settings.durationSec * 1000L
        var energy = 0.0
        var weightSum = 0.0
        for (s in samples) {
            val inWindow = if (spanMs == 0L) s.time == nowMs else s.time > nowMs - spanMs
            if (inWindow) {
                energy += s.weightMs * 10.0.pow(s.db / 10)
                weightSum += s.weightMs
            }
        }
        val windowDb = 10 * log10(energy / weightSum)

        val observedLongEnough = nowMs - observingSince >= spanMs
        val last = lastAlertAt
        val cooledDown = last == null || nowMs - last >= settings.cooldownMin * 60_000L
        val alert = observedLongEnough && cooledDown && windowDb >= settings.thresholdDb
        if (alert) lastAlertAt = nowMs
        return Result(windowDb, alert)
    }

    private companion object {
        const val MAX_HOLE_MS = 90_000L
        const val KEEP_MS = 15 * 60_000L
    }
}
