package de.laermwaechter

import kotlinx.coroutines.flow.MutableStateFlow

/** What the service last measured, for the screen to show. */
data class MonitorSnapshot(
    val running: Boolean = false,
    val lastDb: Double? = null,
    val clipped: Boolean = false,
    val windowDb: Double? = null,
    val dosePercent: Double = 0.0,
    /** The last attempt got no audio (permission missing or microphone in use by a call). */
    val micUnavailable: Boolean = false,
)

object MonitorState {
    val snapshot = MutableStateFlow(MonitorSnapshot())

    /** While the screen is open the service measures every second for a live display. */
    @Volatile
    var uiVisible = false
}
