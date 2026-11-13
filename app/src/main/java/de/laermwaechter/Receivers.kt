package de.laermwaechter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.laermwaechter.core.SettingsStore

/** The exact alarm that ends a sleep phase. Android keeps the phone awake until this returns. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MonitorService.instance?.wakeUpAndMeasure()
    }
}

/**
 * Android does not allow a microphone service to start from the background after a reboot,
 * so the user gets a notification to tap instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (SettingsStore(context).monitoringEnabled) {
            Notifications.createChannels(context)
            Notifications.askForRestart(context)
        }
    }
}
