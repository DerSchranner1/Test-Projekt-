package de.laermwaechter

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.laermwaechter.audio.LevelMeter
import de.laermwaechter.core.AlertEngine
import de.laermwaechter.core.EnergyEstimate
import de.laermwaechter.core.LevelMath
import de.laermwaechter.core.Settings
import de.laermwaechter.core.SettingsStore
import java.time.LocalDate
import kotlin.math.min

/**
 * Takes a short reading, then lets the phone sleep until the next one.
 *
 * Energy design: the microphone is only open during a reading. For pauses of
 * [EnergyEstimate.MIN_SLEEP_GAP_SEC] or more no wake lock is held; an exact alarm wakes the
 * processor for the next reading. Shorter pauses are bridged with a wake lock and a timer.
 */
class MonitorService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var alarmManager: AlarmManager
    private lateinit var store: SettingsStore
    private lateinit var meter: LevelMeter
    private val engine = AlertEngine()

    @Volatile
    private var running = false
    private var plannedGapMs = 0L
    private var lastReadingAt = 0L
    private val tick = Runnable { takeReading() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        meter = LevelMeter(this)
        alarmManager = getSystemService(AlarmManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "laermwaechter:reading")
            .apply { setReferenceCounted(false) }
        thread = HandlerThread("readings").apply { start() }
        handler = Handler(thread.looper)
        Notifications.createChannels(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            store.monitoringEnabled = false
            shutDown()
            return START_NOT_STICKY
        }
        // A null intent means Android restarted us after killing the process.
        if (!enterForeground()) {
            // Android 14+ only lets a microphone service start while the app is visible.
            if (store.monitoringEnabled) Notifications.askForRestart(this)
            shutDown()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            store.monitoringEnabled = true
            Notifications.cancelRestart(this)
            engine.reset()
            lastReadingAt = 0
            MonitorState.snapshot.value = MonitorSnapshot(running = true, dosePercent = todaysDose())
            wakeUpAndMeasure()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        alarmManager.cancel(alarmIntent())
        handler.removeCallbacks(tick)
        thread.quitSafely()
        if (wakeLock.isHeld) wakeLock.release()
        MonitorState.snapshot.value = MonitorState.snapshot.value.copy(running = false)
        super.onDestroy()
    }

    /** Called by the alarm, and by the screen when it opens, to measure right away. */
    fun wakeUpAndMeasure() {
        if (!running) return
        // Taken here, on the caller's thread, so the phone cannot doze off before the reading.
        wakeLock.acquire(WAKE_LOCK_GUARD_MS)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_MONITOR,
            Notifications.monitor(this, MonitorState.snapshot.value, store.load()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun shutDown() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun takeReading() {
        if (!running) return
        val settings = store.load()
        val reading = meter.measure(settings.measureMs, settings.calibrationDb)
        val now = SystemClock.elapsedRealtime()
        if (!running) return

        var nextGapSec = settings.intervalSec
        var snapshot = MonitorState.snapshot.value
        if (reading == null) {
            snapshot = snapshot.copy(micUnavailable = true)
        } else {
            val result = engine.add(now, reading.db, plannedGapMs, settings)
            val dose = addToDose(reading.db, now, settings)
            nextGapSec = settings.intervalSecFor(reading.db)
            snapshot = snapshot.copy(
                lastDb = reading.db,
                clipped = reading.clipped,
                windowDb = result.windowDb,
                dosePercent = dose,
                micUnavailable = false,
            )
            if (result.alert) Notifications.alertTooLoud(this, result.windowDb, settings)
            lastReadingAt = now
        }
        MonitorState.snapshot.value = snapshot
        Notifications.updateMonitor(this, snapshot, settings)

        val gapMs = if (MonitorState.uiVisible) LIVE_GAP_MS else nextGapSec * 1000L
        scheduleNext(gapMs)
    }

    private fun addToDose(db: Double, now: Long, settings: Settings): Double {
        var dose = todaysDose()
        if (lastReadingAt != 0L) {
            // A reading stands for the time since the previous one, unless that was a long hole.
            val covered = min(now - lastReadingAt, 2 * plannedGapMs + 5_000)
            dose += LevelMath.dosePercent(db, covered)
            store.dosePercent = dose.toFloat()
        }
        if (dose >= 100 && settings.doseWarning && !store.doseWarned) {
            store.doseWarned = true
            Notifications.alertDose(this, settings)
        }
        return dose
    }

    /** Today's stored dose; starts from zero on a new day. */
    private fun todaysDose(): Double {
        val today = LocalDate.now().toString()
        if (store.doseDay != today) {
            store.doseDay = today
            store.dosePercent = 0f
            store.doseWarned = false
        }
        return store.dosePercent.toDouble()
    }

    private fun scheduleNext(gapMs: Long) {
        plannedGapMs = gapMs
        handler.removeCallbacks(tick)
        alarmManager.cancel(alarmIntent())
        if (gapMs >= EnergyEstimate.MIN_SLEEP_GAP_SEC * 1000L && canUseExactAlarms(this)) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + gapMs,
                alarmIntent(),
            )
            // Nothing keeps the processor awake now: it sleeps until the alarm.
            if (wakeLock.isHeld) wakeLock.release()
        } else {
            wakeLock.acquire(gapMs + WAKE_LOCK_GUARD_MS)
            handler.postDelayed(tick, gapMs)
        }
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        0,
        Intent(this, TickReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_STOP = "de.laermwaechter.STOP"
        private const val LIVE_GAP_MS = 1000L
        private const val WAKE_LOCK_GUARD_MS = 30_000L

        /** The running service, for the alarm receiver and the screen in the same process. */
        @Volatile
        var instance: MonitorService? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            SettingsStore(context).monitoringEnabled = false
            context.stopService(Intent(context, MonitorService::class.java))
        }

        fun canUseExactAlarms(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
