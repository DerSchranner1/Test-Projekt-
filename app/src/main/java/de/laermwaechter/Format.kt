package de.laermwaechter

import android.content.Context
import kotlin.math.roundToInt

object Format {
    /** "30 s", "3 min" */
    fun span(context: Context, seconds: Int): String =
        if (seconds < 60) context.getString(R.string.unit_seconds, seconds)
        else context.getString(R.string.unit_minutes, seconds / 60)

    /** "8 h", "1 h 35 min", "4 min", "unter 1 min" */
    fun hours(context: Context, hours: Double): String {
        val minutes = (hours * 60).roundToInt()
        return when {
            hours >= 24 -> context.getString(R.string.unit_unlimited)
            minutes < 1 -> context.getString(R.string.unit_under_minute)
            minutes < 60 -> context.getString(R.string.unit_minutes, minutes)
            minutes % 60 == 0 -> context.getString(R.string.unit_hours, minutes / 60)
            else -> context.getString(R.string.unit_hours_minutes, minutes / 60, minutes % 60)
        }
    }
}
