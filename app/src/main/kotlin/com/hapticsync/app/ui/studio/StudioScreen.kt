package com.hapticsync.app.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.components.ChoiceChips
import com.hapticsync.app.ui.components.FrequencyRangeSlider
import com.hapticsync.app.ui.components.LabeledSlider
import com.hapticsync.app.ui.components.NameDialog
import com.hapticsync.app.ui.components.ScreenScaffold
import com.hapticsync.app.ui.components.SectionCard
import com.hapticsync.app.ui.components.SwitchRow
import com.hapticsync.app.ui.components.screenContent
import com.hapticsync.app.ui.player.IsolationPicker
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.model.AnalysisQuality
import com.hapticsync.model.AnalysisSettings
import com.hapticsync.model.BandId
import com.hapticsync.model.EnvelopeShape
import com.hapticsync.model.HapticParameters
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import com.hapticsync.model.SoundFamily
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(
    viewModel: StudioViewModel,
    onBack: () -> Unit,
    onOpenPresets: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val profile by viewModel.profile.collectAsState()
    val presetName by viewModel.presetName.collectAsState()
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    ScreenScaffold(
        title = "Haptic Studio",
        onBack = onBack,
        bottomBar = bottomBar,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = onOpenPresets) { Icon(Icons.Rounded.Tune, contentDescription = "Presets") }
            IconButton(onClick = { saveDialog = true }) { Icon(Icons.Rounded.Save, contentDescription = "Save as preset") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = padding.screenContent(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { PresetHeader(presetName, profile, onSave = { saveDialog = true }, onIsolationOff = { viewModel.setIsolation(profile.isolation.copy(enabled = false)) }) }
            item { FeelSection(profile.haptics, viewModel::updateHaptics) }
            item {
                SectionCard("Sound isolation", subtitle = "Feel only the sounds you pick, as many as you like") {
                    IsolationPicker(
                        settings = profile.isolation,
                        onChange = viewModel::setIsolation,
                        showHeader = false,
                    )
                }
            }
            item { AnalysisSection(profile.analysis, viewModel::updateAnalysis) }
            item { SectionHeading("Frequency bands", "What each part of the audio spectrum contributes. Open a band to drag its frequency limits.") }
            item { BandMap(profile, Modifier.fillMaxWidth().height(132.dp)) }
            items(BandId.entries, key = { "band-${it.name}" }) { band ->
                BandCard(
                    settings = profile.band(band),
                    onChange = { transform -> viewModel.updateBand(band, transform) },
                    onReset = { viewModel.resetBand(band) },
                )
            }
            item { SectionHeading("Sound categories", "How strongly each kind of sound is felt. Detection works on acoustic character, so a category means \"sounds like\" rather than certainty.") }
            for (family in SoundFamily.entries) {
                item(key = "family-${family.name}") { FamilyHeading(family) }
                items(SoundCategory.byFamily(family), key = { "category-${it.name}" }) { category ->
                    CategoryCard(
                        settings = profile.category(category),
                        onChange = { transform -> viewModel.updateCategory(category, transform) },
                        onTest = {
                            if (!viewModel.capabilities.hasVibrator) {
                                notify("This device has no vibration motor.")
                            } else if (!viewModel.testCategory(category)) {
                                notify("This effect could not be played on this device.")
                            }
                        },
                        onReset = { viewModel.resetCategory(category) },
                    )
                }
            }
        }
    }

    if (saveDialog) {
        NameDialog(
            title = "Save as preset",
            initial = if (presetName == "Custom") "" else "$presetName (tuned)",
            confirmLabel = "Save",
            onConfirm = { name ->
                val preset = viewModel.savePreset(name)
                saveDialog = false
                notify("Saved “${preset.name}”")
            },
            onDismiss = { saveDialog = false },
        )
    }
}

@Composable
private fun PresetHeader(presetName: String, profile: HapticProfile, onSave: () -> Unit, onIsolationOff: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tuning", style = MaterialTheme.typography.labelLarge, color = HsColors.TextSecondary)
                    Text(presetName, style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = onSave) { Text("Save as preset") }
            }
            Text(
                "Changes apply instantly, also while a video plays. Edits are kept as your Custom preset; built-in presets are never overwritten.",
                style = MaterialTheme.typography.bodySmall,
                color = HsColors.TextSecondary,
            )
            if (profile.isolation.isActive) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = HsColors.Accent.copy(alpha = 0.14f)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CenterFocusStrong, contentDescription = null, tint = HsColors.Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Isolation on: feeling only ${profile.isolation.summary(maxNames = 3)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onIsolationOff) { Text("Turn off") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
    }
}

/** The ten global haptic parameters plus the layer switches. */
@Composable
private fun FeelSection(h: HapticParameters, update: ((HapticParameters) -> HapticParameters) -> Unit) {
    SectionCard("Feel", subtitle = "How detected sounds become vibration") {
        LabeledSlider("Master intensity", h.masterIntensity, { v -> update { it.copy(masterIntensity = v) } })
        LabeledSlider("Maximum intensity", h.maxIntensity, { v -> update { it.copy(maxIntensity = v) } }, valueRange = 0.05f..1f)
        LabeledSlider(
            "Pulse duration", h.pulseDurationScale, { v -> update { it.copy(pulseDurationScale = v) } },
            valueRange = 0.25f..3f, format = { "×%.2f".format(it) },
        )
        LabeledSlider(
            "Minimum gap between pulses", h.pulseSpacingMs, { v -> update { it.copy(pulseSpacingMs = v) } },
            valueRange = HapticParameters.MIN_SPACING_MS..HapticParameters.MAX_SPACING_MS, format = { "${it.toInt()} ms" },
        )
        LabeledSlider(
            "Pulse repetition", h.pulseRepetition, { v -> update { it.copy(pulseRepetition = v) } },
            valueRange = 0f..2f, format = ::repetitionLabel,
        )
        Spacer(Modifier.height(4.dp))
        Text("Envelope shape", style = MaterialTheme.typography.bodyMedium)
        ChoiceChips(EnvelopeShape.entries, h.envelope, { it.label }, { shape -> update { it.copy(envelope = shape) } })
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            "Attack", h.attackMs, { v -> update { it.copy(attackMs = v) } },
            valueRange = 0f..HapticParameters.MAX_ATTACK_MS, format = { "${it.toInt()} ms" },
        )
        LabeledSlider(
            "Release", h.releaseMs, { v -> update { it.copy(releaseMs = v) } },
            valueRange = HapticParameters.MIN_RELEASE_MS..HapticParameters.MAX_RELEASE_MS, format = { "${it.toInt()} ms" },
        )
        LabeledSlider("Event sensitivity", h.eventSensitivity, { v -> update { it.copy(eventSensitivity = v) } })
        LabeledSlider(
            "Ignore audio quieter than", h.minAmplitudeDb, { v -> update { it.copy(minAmplitudeDb = v) } },
            valueRange = -90f..-10f, format = { "${it.toInt()} dBFS" },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = HsColors.Outline)
        SwitchRow("Pulses for sudden sounds", h.transientLayer, { v -> update { it.copy(transientLayer = v) } },
            description = "Impacts, beats, gear shifts, gunshots")
        SwitchRow("Texture for sustained sounds", h.continuousLayer, { v -> update { it.copy(continuousLayer = v) } },
            description = "Engine rumble, bass notes, rain, machinery")
        LabeledSlider("Texture level", h.continuousGain, { v -> update { it.copy(continuousGain = v) } }, enabled = h.continuousLayer)
        SwitchRow("Fatigue protection", h.fatigueProtection, { v -> update { it.copy(fatigueProtection = v) } },
            description = "Eases off long, strong rumble so it stays pleasant")
        SwitchRow("Follow playback speed", h.scaleWithSpeed, { v -> update { it.copy(scaleWithSpeed = v) } },
            description = "Shorten patterns when playing faster than 1×")
    }
}

private fun repetitionLabel(value: Float): String = when {
    value < 0.05f -> "Single pulse"
    value in 0.95f..1.05f -> "Natural"
    value > 1.95f -> "Doubled"
    else -> "×%.1f".format(value)
}

@Composable
private fun AnalysisSection(a: AnalysisSettings, update: ((AnalysisSettings) -> AnalysisSettings) -> Unit) {
    SectionCard("Analysis", subtitle = "How the soundtrack is examined. Does not change what you hear.") {
        FrequencyRangeSlider(
            "Frequency range", a.rangeLowHz, a.rangeHighHz,
            onChange = { lo, hi -> update { it.copy(rangeLowHz = lo, rangeHighHz = hi) } },
            color = HsColors.Teal,
        )
        LabeledSlider(
            "Window length", a.windowMs, { v -> update { it.copy(windowMs = v) } },
            valueRange = AnalysisSettings.MIN_WINDOW_MS..AnalysisSettings.MAX_WINDOW_MS, format = { "${it.toInt()} ms" },
        )
        LabeledSlider(
            "Hop (overlap ${overlapPercent(a)} %)", a.hopMs, { v -> update { it.copy(hopMs = v) } },
            valueRange = AnalysisSettings.MIN_HOP_MS..AnalysisSettings.MAX_HOP_MS, format = { "${it.toInt()} ms" },
        )
        LabeledSlider(
            "Decision time", a.decisionDelayMs, { v -> update { it.copy(decisionDelayMs = v) } },
            valueRange = 0f..AnalysisSettings.MAX_DECISION_DELAY_MS, format = { "${it.toInt()} ms" },
        )
        Text(
            "Decision time is how long a sound is observed before it is classified. It is taken from the player's audio look-ahead, so it does not delay the haptics.",
            style = MaterialTheme.typography.bodySmall,
            color = HsColors.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        SwitchRow("Adapt to loudness", a.adaptiveNormalization, { v -> update { it.copy(adaptiveNormalization = v) } },
            description = "Quiet and loud videos feel alike")
        SwitchRow("Noise gate", a.noiseGate, { v -> update { it.copy(noiseGate = v) } },
            description = "Ignore steady background hiss")
        Spacer(Modifier.height(4.dp))
        Text("Quality", style = MaterialTheme.typography.bodyMedium)
        ChoiceChips(AnalysisQuality.entries, a.quality, { it.label }, { q -> update { it.copy(quality = q) } })
    }
}

private fun overlapPercent(a: AnalysisSettings): Int =
    (100f * (1f - minOf(a.hopMs, a.windowMs) / a.windowMs)).toInt().coerceIn(0, 99)

@Composable
private fun FamilyHeading(family: SoundFamily) {
    Row(Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(com.hapticsync.app.ui.components.familyIcon(family), contentDescription = null, tint = HsColors.family(family), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(family.label, style = MaterialTheme.typography.titleMedium, color = HsColors.family(family))
    }
}
