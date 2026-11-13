package de.laermwaechter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.laermwaechter.Format
import de.laermwaechter.MonitorSnapshot
import de.laermwaechter.R
import de.laermwaechter.core.EnergyEstimate
import de.laermwaechter.core.LevelMath
import de.laermwaechter.core.Settings
import java.util.Locale
import kotlin.math.roundToInt

data class SystemStatus(
    val exactAlarmsAllowed: Boolean = true,
    val batteryOptimized: Boolean = false,
    val micDenied: Boolean = false,
)

private val DURATIONS_SEC = listOf(0, 30, 60, 180, 300, 600)
private val COOLDOWNS_MIN = listOf(5, 15, 30, 60)
private val INTERVALS_SEC = listOf(5, 10, 15, 30, 60, 120, 300)
private val MEASURE_MS = listOf(300, 500, 1000, 2000)

@Composable
fun MainScreen(
    snapshot: MonitorSnapshot,
    settings: Settings,
    status: SystemStatus,
    onToggle: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    onOpenAppSettings: () -> Unit,
    onAllowBackground: () -> Unit,
    onAllowExactAlarms: () -> Unit,
) {
    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

            LevelCard(snapshot, settings, onToggle)

            if (status.micDenied) {
                HintCard(
                    R.string.hint_permission_title,
                    R.string.hint_permission_text,
                    R.string.hint_permission_button,
                    onOpenAppSettings,
                )
            }
            if (!status.exactAlarmsAllowed) {
                HintCard(
                    R.string.hint_alarm_title,
                    R.string.hint_alarm_text,
                    R.string.hint_alarm_button,
                    onAllowExactAlarms,
                )
            }
            if (status.batteryOptimized) {
                HintCard(
                    R.string.hint_battery_title,
                    R.string.hint_battery_text,
                    R.string.hint_battery_button,
                    onAllowBackground,
                )
            }

            DoseCard(snapshot.dosePercent)
            EnergyCard(settings, status.exactAlarmsAllowed)
            WarningSettings(settings, onSettingsChange)
            MeasuringSettings(settings, onSettingsChange)
            CalibrationSettings(settings, onSettingsChange)

            Text(
                stringResource(R.string.privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LevelCard(snapshot: MonitorSnapshot, settings: Settings, onToggle: () -> Unit) {
    val colors = levelColors()
    val db = snapshot.lastDb.takeIf { snapshot.running }
    // The colour follows what the warning is based on: the average, not a single reading.
    val judged = (snapshot.windowDb ?: db)
    val (statusText, statusColor) = when {
        !snapshot.running -> stringResource(R.string.level_off) to MaterialTheme.colorScheme.onSurfaceVariant
        snapshot.micUnavailable ->
            stringResource(R.string.status_mic_unavailable) to MaterialTheme.colorScheme.onSurfaceVariant
        judged == null -> stringResource(R.string.level_waiting) to MaterialTheme.colorScheme.onSurfaceVariant
        judged >= settings.thresholdDb -> stringResource(R.string.status_loud) to colors.loud
        judged >= settings.thresholdDb - Settings.ADAPTIVE_MARGIN_DB ->
            stringResource(R.string.status_near) to colors.near
        else -> stringResource(R.string.status_quiet) to colors.quiet
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = db?.roundToInt()?.toString() ?: stringResource(R.string.level_no_value),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (db == null) MaterialTheme.colorScheme.onSurfaceVariant else statusColor,
                )
                Text(
                    text = stringResource(R.string.unit_db),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
                )
            }
            Text(statusText, style = MaterialTheme.typography.titleMedium, color = statusColor)
            if (db != null) {
                Text(
                    text = if (settings.durationSec == 0) {
                        stringResource(R.string.level_instant_mode)
                    } else {
                        stringResource(
                            R.string.level_average,
                            Format.span(LocalContext.current, settings.durationSec),
                            (snapshot.windowDb ?: db).roundToInt(),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (snapshot.clipped) {
                    Text(
                        stringResource(R.string.level_over_range),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.loud,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (snapshot.running) {
                OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.button_stop))
                }
            } else {
                Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.button_start))
                }
            }
        }
    }
}

@Composable
private fun HintCard(title: Int, text: Int, button: Int, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onClick) { Text(stringResource(button)) }
        }
    }
}

@Composable
private fun DoseCard(dosePercent: Double) {
    val colors = levelColors()
    Section(R.string.dose_title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { (dosePercent / 100).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f),
                color = when {
                    dosePercent >= 100 -> colors.loud
                    dosePercent >= 50 -> colors.near
                    else -> colors.quiet
                },
            )
            Text(
                stringResource(R.string.dose_value, dosePercent.roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Help(stringResource(R.string.dose_caption))
    }
}

@Composable
private fun EnergyCard(settings: Settings, exactAlarmsAllowed: Boolean) {
    val quiet = EnergyEstimate.percentPerDay(settings.intervalSec, settings.measureMs, exactAlarmsAllowed)
    val loud = EnergyEstimate.percentPerDay(settings.fastIntervalSec, settings.measureMs, exactAlarmsAllowed)
    val duty = 100 * EnergyEstimate.dutyCycle(settings.intervalSec, settings.measureMs)
    val sleeps = EnergyEstimate.sleepsBetween(settings.intervalSec, exactAlarmsAllowed)

    Section(R.string.energy_title) {
        Text(
            text = if (settings.fastIntervalSec != settings.intervalSec) {
                stringResource(R.string.energy_range, decimal(quiet), decimal(loud))
            } else {
                stringResource(R.string.energy_single, decimal(quiet))
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(
                if (sleeps) R.string.energy_duty else R.string.energy_duty_awake,
                decimal(duty),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Help(stringResource(R.string.energy_disclaimer))
    }
}

@Composable
private fun WarningSettings(settings: Settings, onChange: (Settings) -> Unit) {
    val context = LocalContext.current
    Section(R.string.section_warning) {
        LabeledSlider(
            label = stringResource(R.string.setting_threshold),
            valueText = stringResource(R.string.setting_threshold_value, settings.thresholdDb),
            value = settings.thresholdDb,
            range = 70..110,
            onChange = { onChange(settings.copy(thresholdDb = it)) },
        )
        Help(
            stringResource(
                R.string.setting_threshold_help,
                Format.hours(context, LevelMath.safeHoursPerDay(settings.thresholdDb.toDouble())),
            ),
        )
        ChoiceSlider(
            label = stringResource(R.string.setting_duration),
            options = DURATIONS_SEC,
            value = settings.durationSec,
            format = { if (it == 0) context.getString(R.string.span_instant) else Format.span(context, it) },
            onChange = { onChange(settings.copy(durationSec = it)) },
        )
        ChoiceSlider(
            label = stringResource(R.string.setting_cooldown),
            options = COOLDOWNS_MIN,
            value = settings.cooldownMin,
            format = { context.getString(R.string.unit_minutes, it) },
            onChange = { onChange(settings.copy(cooldownMin = it)) },
        )
        SwitchRow(R.string.setting_vibrate, settings.vibrate) { onChange(settings.copy(vibrate = it)) }
        SwitchRow(R.string.setting_sound, settings.sound) { onChange(settings.copy(sound = it)) }
        SwitchRow(R.string.setting_dose_warning, settings.doseWarning) {
            onChange(settings.copy(doseWarning = it))
        }
    }
}

@Composable
private fun MeasuringSettings(settings: Settings, onChange: (Settings) -> Unit) {
    val context = LocalContext.current
    Section(R.string.section_measuring) {
        ChoiceSlider(
            label = stringResource(R.string.setting_interval),
            options = INTERVALS_SEC,
            value = settings.intervalSec,
            format = { Format.span(context, it) },
            onChange = { onChange(settings.copy(intervalSec = it)) },
        )
        ChoiceSlider(
            label = stringResource(R.string.setting_measure),
            options = MEASURE_MS,
            value = settings.measureMs,
            format = { context.getString(R.string.setting_measure_value, decimal(it / 1000.0)) },
            onChange = { onChange(settings.copy(measureMs = it)) },
        )
        SwitchRow(R.string.setting_adaptive, settings.adaptive) { onChange(settings.copy(adaptive = it)) }
        Help(
            if (settings.fastIntervalSec != settings.intervalSec) {
                stringResource(
                    R.string.setting_adaptive_help,
                    settings.thresholdDb - Settings.ADAPTIVE_MARGIN_DB,
                    Format.span(context, settings.fastIntervalSec),
                    Format.span(context, settings.intervalSec),
                )
            } else {
                stringResource(R.string.setting_adaptive_off_help)
            },
        )
    }
}

@Composable
private fun CalibrationSettings(settings: Settings, onChange: (Settings) -> Unit) {
    Section(R.string.section_calibration) {
        LabeledSlider(
            label = stringResource(R.string.setting_calibration),
            valueText = stringResource(
                R.string.setting_calibration_value,
                String.format(Locale.GERMANY, "%+d", settings.calibrationDb),
            ),
            value = settings.calibrationDb,
            range = -20..20,
            onChange = { onChange(settings.copy(calibrationDb = it)) },
        )
        Help(stringResource(R.string.setting_calibration_help))
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabeledSlider(label: String, valueText: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** A slider that snaps to a fixed list of values. */
@Composable
private fun ChoiceSlider(
    label: String,
    options: List<Int>,
    value: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    val index = options.indexOf(value).coerceAtLeast(0)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(options[index]), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(options[it.roundToInt().coerceIn(0, options.lastIndex)]) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
        )
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun decimal(value: Double): String = String.format(Locale.GERMANY, "%.1f", value)
