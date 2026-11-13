package de.laermwaechter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import de.laermwaechter.core.LevelMath
import de.laermwaechter.core.Settings
import kotlin.math.roundToInt

object Notifications {
    const val ID_MONITOR = 1
    private const val ID_ALERT = 2
    private const val ID_DOSE = 3
    private const val ID_RESTART = 4

    private const val CHANNEL_MONITOR = "monitor"
    private const val CHANNEL_ALERT_SOUND = "alert_sound"
    private const val CHANNEL_ALERT_SILENT = "alert_silent"
    private const val CHANNEL_STATUS = "status"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR,
                context.getString(R.string.channel_monitor),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        // Vibration is done by the app itself so it can be switched in the app's settings.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT_SOUND,
                context.getString(R.string.channel_alert_sound),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { enableVibration(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT_SILENT,
                context.getString(R.string.channel_alert_silent),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(false)
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun monitor(context: Context, snapshot: MonitorSnapshot, settings: Settings): Notification {
        val text = when {
            snapshot.micUnavailable -> context.getString(R.string.notif_mic_unavailable)
            snapshot.lastDb == null -> context.getString(R.string.notif_waiting)
            else -> context.getString(
                R.string.notif_level,
                snapshot.lastDb.roundToInt(),
                Format.span(context, settings.durationSec),
                (snapshot.windowDb ?: snapshot.lastDb).roundToInt(),
            )
        }
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(context.getString(R.string.notif_monitor_title))
            .setContentText(text)
            .setContentIntent(openApp(context, autostart = false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .build()
    }

    fun updateMonitor(context: Context, snapshot: MonitorSnapshot, settings: Settings) {
        context.getSystemService(NotificationManager::class.java)
            .notify(ID_MONITOR, monitor(context, snapshot, settings))
    }

    fun alertTooLoud(context: Context, windowDb: Double, settings: Settings) {
        val title = if (settings.durationSec == 0) {
            context.getString(R.string.alert_title_instant, windowDb.roundToInt())
        } else {
            context.getString(
                R.string.alert_title_average,
                windowDb.roundToInt(),
                Format.span(context, settings.durationSec),
            )
        }
        val text = context.getString(
            R.string.alert_text,
            Format.hours(context, LevelMath.safeHoursPerDay(windowDb)),
        )
        alert(context, ID_ALERT, title, text, settings)
    }

    fun alertDose(context: Context, settings: Settings) {
        alert(
            context,
            ID_DOSE,
            context.getString(R.string.dose_alert_title),
            context.getString(R.string.dose_alert_text),
            settings,
        )
    }

    private fun alert(context: Context, id: Int, title: String, text: String, settings: Settings) {
        val channel = if (settings.sound) CHANNEL_ALERT_SOUND else CHANNEL_ALERT_SILENT
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(context, autostart = false))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        if (settings.vibrate) {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        }
    }

    /** Shown when Android will not let the service start by itself (after a reboot or a kill). */
    fun askForRestart(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(context.getString(R.string.restart_title))
            .setContentText(context.getString(R.string.restart_text))
            .setContentIntent(openApp(context, autostart = true))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_RESTART, notification)
    }

    fun cancelRestart(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_RESTART)
    }

    private fun openApp(context: Context, autostart: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            if (autostart) 2 else 0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTOSTART, autostart)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
