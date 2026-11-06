package com.hapticsync.app.ui.calibration

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.components.ConfirmDialog
import com.hapticsync.app.ui.components.InfoRow
import com.hapticsync.app.ui.components.LabeledSlider
import com.hapticsync.app.ui.components.ScreenScaffold
import com.hapticsync.app.ui.components.SectionCard
import com.hapticsync.app.ui.components.SwitchRow
import com.hapticsync.app.ui.components.formatHz
import com.hapticsync.app.ui.components.screenContent
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.haptics.CalibrationPattern
import com.hapticsync.haptics.DeviceCapabilities
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.VibrationUsage
import java.text.DateFormat
import java.util.Date

@Composable
fun CalibrationScreen(viewModel: CalibrationViewModel, onBack: () -> Unit, bottomBar: @Composable () -> Unit) {
    val calibration by viewModel.calibration.collectAsState()
    val preparing by viewModel.preparingSyncTest.collectAsState()
    val lastPattern by viewModel.lastPattern.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val caps = viewModel.capabilities

    ScreenScaffold(title = "Calibration", onBack = onBack, bottomBar = bottomBar, snackbarHostState = snackbar) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding.screenContent(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { CapabilitiesCard(caps, viewModel.predefinedEffects, calibration, viewModel.deviceKey) }
            item { PatternsCard(lastPattern, caps.hasVibrator, viewModel::play, viewModel::stop) }
            item { StrengthCard(calibration, viewModel::update) }
            item { TimingCard(calibration, viewModel::update, preparing, viewModel::startSyncTest) }
            item { RenderingCard(calibration, caps, viewModel::update) }
            item { SystemSettingsCard() }
            item { SaveCard(calibration, viewModel.deviceKey, onSave = viewModel::save, onReset = { confirmReset = true }) }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset calibration?",
            message = "Strength, timing and rendering choices for this phone return to their defaults.",
            confirmLabel = "Reset",
            onConfirm = { confirmReset = false; viewModel.reset() },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun CapabilitiesCard(caps: DeviceCapabilities, predefined: String, calibration: CalibrationProfile, deviceKey: String) {
    SectionCard("Your phone's vibration hardware", subtitle = "Detected with Android's public vibrator APIs") {
        InfoRow("Device", caps.deviceName.ifEmpty { deviceKey })
        InfoRow("Android", "${Build.VERSION.RELEASE} (API ${caps.apiLevel})")
        InfoRow(
            "Vibration motor",
            when {
                !caps.hasVibrator -> "Not found"
                caps.vibratorCount > 1 -> "Yes (${caps.vibratorCount} actuators)"
                else -> "Yes"
            },
            valueColor = if (caps.hasVibrator) HsColors.TextPrimary else HsColors.Danger,
        )
        if (caps.hasVibrator) {
            InfoRow("Amplitude control", if (caps.hasAmplitudeControl) "Yes" else "No (on/off only)")
            InfoRow(
                "Haptic primitives",
                if (caps.supportsComposition) "${caps.supportedPrimitives.size} supported" else if (caps.apiLevel < 30) "Requires Android 11" else "Not supported",
            )
            if (caps.supportedPrimitives.isNotEmpty()) {
                val list = caps.supportedPrimitives.sortedBy { it.ordinal }.joinToString(" · ") { p ->
                    caps.primitiveDurationsMs[p]?.let { "${p.label} ($it ms)" } ?: p.label
                }
                Text(list, style = MaterialTheme.typography.bodySmall, color = HsColors.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
            InfoRow("Predefined effects", predefined)
            val envelope = caps.envelope
            InfoRow(
                "Envelope effects",
                when {
                    caps.supportsEnvelopes && envelope != null -> "Up to ${envelope.maxControlPoints} points, ${envelope.maxDurationMs} ms"
                    caps.apiLevel >= 36 -> "Not supported by this motor"
                    else -> "Requires Android 16"
                },
            )
            InfoRow(
                "Frequency control",
                envelope?.frequencyRangeHz?.let { "${formatHz(it.start)} – ${formatHz(it.endInclusive)}" } ?: "Not available",
            )
            InfoRow("Resonant frequency", caps.resonantFrequencyHz?.let { formatHz(it) } ?: "Not reported")
            InfoRow("Q factor", caps.qFactor?.let { "%.1f".format(it) } ?: "Not reported")
        }
        InfoRow(
            "Live rendering",
            caps.strategyFor(calibration.preferEnvelopeEffects, calibration.preferPrimitives).label,
            valueColor = HsColors.Accent,
        )
        if (caps.hasVibrator) {
            InfoRow(
                "Prepared rendering",
                if (caps.hasAmplitudeControl) "One waveform per ~1 s window, 5 ms steps" else "One on/off pattern per ~1 s window",
                valueColor = HsColors.Accent,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternsCard(last: CalibrationPattern?, enabled: Boolean, onPlay: (CalibrationPattern) -> Unit, onStop: () -> Unit) {
    SectionCard("Test patterns", subtitle = "Rendered exactly as during playback, with your current settings") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (pattern in CalibrationPattern.entries) {
                if (pattern == last) {
                    Button(onClick = { onPlay(pattern) }, enabled = enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = HsColors.Accent, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Text(pattern.label)
                    }
                } else {
                    FilledTonalButton(onClick = { onPlay(pattern) }, enabled = enabled) { Text(pattern.label) }
                }
            }
            OutlinedButton(onClick = onStop, enabled = enabled) {
                Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            last?.description ?: "Tap a pattern to feel it.",
            style = MaterialTheme.typography.bodySmall,
            color = HsColors.TextSecondary,
        )
    }
}

@Composable
private fun StrengthCard(c: CalibrationProfile, update: ((CalibrationProfile) -> CalibrationProfile) -> Unit) {
    SectionCard("Strength", subtitle = "Applies to every preset on this phone") {
        LabeledSlider(
            "Master intensity (this phone)", c.outputGain, { v -> update { it.copy(outputGain = v) } },
            valueRange = 0.2f..1.5f, format = { "${(it * 100).toInt()}%" },
        )
        LabeledSlider(
            "Response curve", c.amplitudeGamma, { v -> update { it.copy(amplitudeGamma = v) } },
            valueRange = 0.4f..2f, format = { "γ %.2f".format(it) },
        )
        Text(
            "Below 1.0 makes gentle vibrations stronger; above 1.0 keeps them subtle.",
            style = MaterialTheme.typography.bodySmall,
            color = HsColors.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        LabeledSlider(
            "Weakest vibration", c.minPerceptibleAmplitude, { v -> update { it.copy(minPerceptibleAmplitude = v) } },
            valueRange = 0f..0.5f,
        )
        Text(
            "Faint effects are raised to this level so they stay noticeable. Play \"Soft\" and lower it until you can only just feel it.",
            style = MaterialTheme.typography.bodySmall,
            color = HsColors.TextMuted,
        )
    }
}

@Composable
private fun TimingCard(
    c: CalibrationProfile,
    update: ((CalibrationProfile) -> CalibrationProfile) -> Unit,
    preparing: Boolean,
    onSyncTest: () -> Unit,
) {
    SectionCard("Timing", subtitle = "Keep sound, picture and vibration together") {
        LabeledSlider(
            "Motor latency", c.actuatorLatencyMs, { v -> update { it.copy(actuatorLatencyMs = v) } },
            valueRange = 0f..CalibrationProfile.MAX_LATENCY_MS, format = { "${it.toInt()} ms" },
        )
        Text(
            "How long this phone's motor takes to respond. Haptics are sent this much earlier.",
            style = MaterialTheme.typography.bodySmall,
            color = HsColors.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        LabeledSlider(
            "Sync offset (negative = earlier)", c.syncOffsetMs, { v -> update { it.copy(syncOffsetMs = v) } },
            valueRange = -CalibrationProfile.MAX_SYNC_OFFSET_MS..CalibrationProfile.MAX_SYNC_OFFSET_MS,
            format = { "${if (it > 0) "+" else ""}${it.toInt()} ms" },
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onSyncTest, enabled = !preparing) {
                Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Run synchronisation test")
            }
            if (preparing) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = HsColors.Accent)
            }
        }
        Text(
            "Plays a click every second. Adjust the offset until the click, the flash and the tap happen together.",
            style = MaterialTheme.typography.bodySmall,
            color = HsColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RenderingCard(c: CalibrationProfile, caps: DeviceCapabilities, update: ((CalibrationProfile) -> CalibrationProfile) -> Unit) {
    SectionCard("Rendering", subtitle = "How effects are built for this motor") {
        SwitchRow(
            "Use haptic primitives",
            c.preferPrimitives && caps.supportsComposition,
            { v -> update { it.copy(preferPrimitives = v) } },
            description = if (caps.supportsComposition) "Crisp clicks and thuds for short hits" else "Not supported on this phone",
            enabled = caps.supportsComposition,
        )
        SwitchRow(
            "Use frequency envelopes",
            c.preferEnvelopeEffects && caps.supportsFrequencyControl,
            { v -> update { it.copy(preferEnvelopeEffects = v) } },
            description = if (caps.supportsFrequencyControl) "Android 16 amplitude and frequency control" else "Requires Android 16 and a motor with frequency control",
            enabled = caps.supportsFrequencyControl,
        )
        Spacer(Modifier.height(8.dp))
        Text("Vibration category", style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.selectableGroup()) {
            for (usage in VibrationUsage.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = c.usage == usage, onClick = { update { it.copy(usage = usage) } }, role = Role.RadioButton)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = c.usage == usage, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(usage.label, style = MaterialTheme.typography.bodyLarge)
                        Text(usage.description, style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemSettingsCard() {
    val context = LocalContext.current
    SectionCard("System vibration settings", trailing = { Icon(Icons.Rounded.Info, contentDescription = null, tint = HsColors.TextSecondary) }) {
        Text(
            "Android applies its own vibration settings on top of HapticSync. If haptics feel weak or do not play, check that media vibration is on and not set to low (Settings › Sound & vibration › Vibration & haptics). Some phones also turn vibration down in battery-saver mode.",
            style = MaterialTheme.typography.bodySmall,
            color = HsColors.TextSecondary,
        )
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }) { Text("Open sound settings") }
    }
}

@Composable
private fun SaveCard(c: CalibrationProfile, deviceKey: String, onSave: () -> Unit, onReset: () -> Unit) {
    SectionCard("Device profile", subtitle = "Calibration is kept separately for each phone model") {
        Text(
            if (c.savedAtMs > 0) {
                "Saved for $deviceKey on ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.savedAtMs))}."
            } else {
                "Changes take effect immediately and are kept for $deviceKey."
            },
            style = MaterialTheme.typography.bodySmall,
            color = HsColors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = HsColors.Accent, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Text("Save calibration")
            }
            OutlinedButton(onClick = onReset) { Text("Reset") }
        }
    }
}
