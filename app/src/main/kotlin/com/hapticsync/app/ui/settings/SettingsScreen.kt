package com.hapticsync.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.components.ConfirmDialog
import com.hapticsync.app.ui.components.InfoRow
import com.hapticsync.app.ui.components.LabeledSlider
import com.hapticsync.app.ui.components.ScreenScaffold
import com.hapticsync.app.ui.components.SectionCard
import com.hapticsync.app.ui.components.SwitchRow
import com.hapticsync.app.ui.components.screenContent
import com.hapticsync.app.ui.theme.HsColors
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenCalibration: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    ScreenScaffold(title = "Settings", onBack = onBack, snackbarHostState = snackbar) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding.screenContent(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SectionCard("Haptics") {
                    SwitchRow(
                        "Haptic feedback",
                        settings.hapticsEnabled && viewModel.hasVibrator,
                        { v -> viewModel.update { it.copy(hapticsEnabled = v) } },
                        description = if (viewModel.hasVibrator) "Master switch. The analysis keeps running while it is off." else "This device has no vibration motor.",
                        enabled = viewModel.hasVibrator,
                    )
                    TextButton(onClick = onOpenCalibration) { Text("Calibrate this phone") }
                    TextButton(onClick = { confirmRestore = true }) { Text("Restore default tuning") }
                }
            }
            item {
                SectionCard("Player") {
                    SwitchRow(
                        "Prepare haptics before playing",
                        settings.prepareHaptics,
                        { v -> viewModel.update { it.copy(prepareHaptics = v) } },
                        description = if (settings.prepareHaptics) {
                            "The soundtrack is analysed ahead of playback (a second or two before it starts), so vibrations land exactly with the sound and only clear sounds are felt"
                        } else {
                            "Haptics are generated live while playing; starts instantly but timing and selection are less exact"
                        },
                    )
                    SwitchRow(
                        "Live visualisation",
                        settings.showVisualization,
                        { v -> viewModel.update { it.copy(showVisualization = v) } },
                        description = "Spectrum, band levels, detected events and haptic output",
                    )
                    SwitchRow(
                        "Keep screen on",
                        settings.keepScreenOn,
                        { v -> viewModel.update { it.copy(keepScreenOn = v) } },
                        description = "While a video is playing",
                    )
                    LabeledSlider(
                        "Hide controls after",
                        settings.autoHideControlsMs / 1000f,
                        { v -> viewModel.update { it.copy(autoHideControlsMs = (v * 2f).roundToLong() * 500L) } },
                        valueRange = 2f..10f,
                        format = { "%.1f s".format(it) },
                    )
                }
            }
            item {
                SectionCard("Privacy") {
                    Text(
                        "HapticSync works entirely offline. It analyses the audio of the video it is playing, inside the app. It never uses the microphone, never captures other apps' audio and sends nothing anywhere. The only permission it needs is vibration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HsColors.TextSecondary,
                    )
                }
            }
            item {
                SectionCard("About") {
                    InfoRow("Version", viewModel.versionName)
                    Text(
                        "Sound categories are recognised from acoustic character (spectral balance, attack and decay, pitch movement, rhythm), so a category means a sound behaves like an engine or a drum, not certainty about its source. A phone's vibration motor cannot reproduce audio frequencies; HapticSync maps the energy and dynamics of each band onto patterns the motor can play.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HsColors.TextSecondary,
                    )
                }
            }
        }
    }

    if (confirmRestore) {
        ConfirmDialog(
            title = "Restore default tuning?",
            message = "The Cinematic preset is applied. Your saved presets are kept.",
            confirmLabel = "Restore",
            onConfirm = {
                confirmRestore = false
                viewModel.restoreDefaultTuning()
                scope.launch { snackbar.showSnackbar("Default tuning restored") }
            },
            onDismiss = { confirmRestore = false },
        )
    }
}
