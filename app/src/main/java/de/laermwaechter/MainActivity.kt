package de.laermwaechter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.laermwaechter.core.Settings
import de.laermwaechter.core.SettingsStore
import de.laermwaechter.ui.AppTheme
import de.laermwaechter.ui.MainScreen
import de.laermwaechter.ui.SystemStatus

class MainActivity : ComponentActivity() {

    private lateinit var store: SettingsStore
    private var settings by mutableStateOf(Settings())
    private var status by mutableStateOf(SystemStatus())
    private var micDenied by mutableStateOf(false)

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMicPermission()) {
                MonitorService.start(this)
            } else {
                micDenied = true
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = SettingsStore(this)
        settings = store.load()
        Notifications.createChannels(this)

        setContent {
            val snapshot by MonitorState.snapshot.collectAsState()
            AppTheme {
                MainScreen(
                    snapshot = snapshot,
                    settings = settings,
                    status = status.copy(micDenied = micDenied),
                    onToggle = { if (snapshot.running) MonitorService.stop(this) else startMonitoring() },
                    onSettingsChange = {
                        settings = it
                        store.save(it)
                    },
                    onOpenAppSettings = ::openAppSettings,
                    onAllowBackground = ::requestBatteryExemption,
                    onAllowExactAlarms = ::requestExactAlarms,
                )
            }
        }
        handleAutostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    override fun onStart() {
        super.onStart()
        MonitorState.uiVisible = true
        MonitorService.instance?.wakeUpAndMeasure()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onStop() {
        MonitorState.uiVisible = false
        super.onStop()
    }

    /** Set by the "tap to restart" notification after a reboot. */
    private fun handleAutostart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            intent.removeExtra(EXTRA_AUTOSTART)
            if (!MonitorState.snapshot.value.running) startMonitoring()
        }
    }

    private fun startMonitoring() {
        val missing = buildList {
            if (!hasMicPermission()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) MonitorService.start(this) else permissionRequest.launch(missing.toTypedArray())
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        if (hasMicPermission()) micDenied = false
        status = SystemStatus(
            exactAlarmsAllowed = MonitorService.canUseExactAlarms(this),
            batteryOptimized = !getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun openAppSettings() = open(
        Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )

    // Sideloaded personal tool that has to wake up on time; Play Store rules on this do not apply.
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() = open(
        Intent(
            SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", packageName, null),
        ),
    )

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            open(
                Intent(
                    SystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Some manufacturers leave these settings screens out; fall back to the app's page.
            try {
                startActivity(
                    Intent(
                        SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}
