package com.hapticsync.app.ui.studio

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hapticsync.app.ui.components.FrequencyRangeSlider
import com.hapticsync.app.ui.components.LabeledSlider
import com.hapticsync.app.ui.components.LogFrequency
import com.hapticsync.app.ui.components.formatHz
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.model.BandId
import com.hapticsync.model.BandSettings
import com.hapticsync.model.CategorySettings
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import com.hapticsync.model.SoundKind

/** Overview of the six bands on a logarithmic frequency axis, with the analysis range shaded. */
@Composable
fun BandMap(profile: HapticProfile, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(color = HsColors.TextMuted, fontSize = 10.sp) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            val w = size.width
            val axisH = 16.dp.toPx()
            val rowsH = size.height - axisH
            val a0 = LogFrequency.toPosition(profile.analysis.rangeLowHz) * w
            val a1 = LogFrequency.toPosition(profile.analysis.rangeHighHz) * w
            drawRect(HsColors.Teal.copy(alpha = 0.08f), topLeft = Offset(a0, 0f), size = Size((a1 - a0).coerceAtLeast(1f), rowsH))
            for (hz in GRID_HZ) {
                val x = LogFrequency.toPosition(hz) * w
                drawLine(HsColors.Outline, Offset(x, 0f), Offset(x, rowsH), strokeWidth = 1f)
            }
            val rowH = rowsH / BandId.COUNT
            BandId.entries.forEachIndexed { i, band ->
                val s = profile.band(band)
                val x0 = LogFrequency.toPosition(s.lowHz) * w
                val x1 = LogFrequency.toPosition(s.highHz) * w
                val alpha = if (s.enabled) 0.35f + 0.65f * s.intensity else 0.12f
                drawRoundRect(
                    color = HsColors.band(band).copy(alpha = alpha),
                    topLeft = Offset(x0, i * rowH + rowH * 0.18f),
                    size = Size((x1 - x0).coerceAtLeast(3f), rowH * 0.64f),
                    cornerRadius = CornerRadius(rowH * 0.32f),
                )
            }
            for ((hz, text) in AXIS_LABELS) {
                val layout = measurer.measure(text, labelStyle)
                val x = (LogFrequency.toPosition(hz) * w - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
                drawText(layout, topLeft = Offset(x, rowsH + 3.dp.toPx()))
            }
        }
    }
}

private val GRID_HZ = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10_000f, 20_000f)
private val AXIS_LABELS = listOf(20f to "20", 100f to "100", 500f to "500", 2000f to "2k", 10_000f to "10k", 20_000f to "20k Hz")

@Composable
fun BandCard(
    settings: BandSettings,
    onChange: ((BandSettings) -> BandSettings) -> Unit,
    onReset: () -> Unit,
) {
    val band = settings.band
    var expanded by rememberSaveable(band.name) { mutableStateOf(false) }
    val color = HsColors.band(band)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(color.copy(alpha = if (settings.enabled) 1f else 0.3f), CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(band.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatHz(settings.lowHz)} – ${formatHz(settings.highHz)} · ${band.interpretation}",
                        style = MaterialTheme.typography.bodySmall,
                        color = HsColors.TextSecondary,
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = settings.enabled, onCheckedChange = { v -> onChange { it.copy(enabled = v) } })
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse ${band.label}" else "Expand ${band.label}",
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (expanded) {
                val on = settings.enabled
                FrequencyRangeSlider(
                    "Range", settings.lowHz, settings.highHz,
                    onChange = { lo, hi -> onChange { it.copy(lowHz = lo, highHz = hi) } },
                    enabled = on, color = color,
                )
                LabeledSlider("Sensitivity", settings.sensitivity, { v -> onChange { it.copy(sensitivity = v) } }, enabled = on)
                LabeledSlider("Haptic intensity", settings.intensity, { v -> onChange { it.copy(intensity = v) } }, enabled = on)
                LabeledSlider(
                    "Threshold", settings.thresholdDb, { v -> onChange { it.copy(thresholdDb = v) } },
                    valueRange = BandSettings.MIN_THRESHOLD_DB..BandSettings.MAX_THRESHOLD_DB,
                    format = { "${it.toInt()} dBFS" }, enabled = on,
                )
                LabeledSlider("Pulse strength", settings.pulseStrength, { v -> onChange { it.copy(pulseStrength = v) } }, enabled = on)
                LabeledSlider("Smoothing", settings.smoothing, { v -> onChange { it.copy(smoothing = v) } }, enabled = on)
                TextButton(onClick = onReset) { Text("Reset ${band.label.lowercase()} to defaults") }
            }
        }
    }
}

@Composable
fun CategoryCard(
    settings: CategorySettings,
    onChange: ((CategorySettings) -> CategorySettings) -> Unit,
    onTest: () -> Unit,
    onReset: () -> Unit,
) {
    val category = settings.category
    var expanded by rememberSaveable(category.name) { mutableStateOf(false) }
    val color = HsColors.category(category)
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color.copy(alpha = if (settings.enabled) 1f else 0.3f), CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(category.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.enabled) "${kindLabel(category.kind)} · ${(settings.intensity * 100).toInt()}%" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = HsColors.TextSecondary,
                    )
                }
                Switch(checked = settings.enabled, onCheckedChange = { v -> onChange { it.copy(enabled = v) } })
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse ${category.label}" else "Expand ${category.label}",
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (expanded) {
                val on = settings.enabled
                Text(category.description, style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
                if (category == SoundCategory.DIALOGUE) {
                    Text(
                        "Speech is detected so it can be kept silent. Enable this only if you want to feel voices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HsColors.TextMuted,
                    )
                }
                LabeledSlider("Detection sensitivity", settings.sensitivity, { v -> onChange { it.copy(sensitivity = v) } }, enabled = on)
                LabeledSlider("Intensity", settings.intensity, { v -> onChange { it.copy(intensity = v) } }, enabled = on)
                LabeledSlider(
                    "Duration", settings.duration, { v -> onChange { it.copy(duration = v) } },
                    valueRange = CategorySettings.MIN_DURATION..CategorySettings.MAX_DURATION,
                    format = { "×%.2f".format(it) }, enabled = on,
                )
                LabeledSlider(
                    "Priority", settings.priority.toFloat(), { v -> onChange { it.copy(priority = v.toInt()) } },
                    valueRange = CategorySettings.MIN_PRIORITY.toFloat()..CategorySettings.MAX_PRIORITY.toFloat(),
                    steps = CategorySettings.MAX_PRIORITY - CategorySettings.MIN_PRIORITY - 1,
                    format = { "${it.toInt()}" }, enabled = on,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onTest, enabled = on) {
                        Icon(Icons.Rounded.Vibration, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Feel it")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onReset) { Text("Reset") }
                }
            }
        }
    }
}

private fun kindLabel(kind: SoundKind): String = when (kind) {
    SoundKind.TRANSIENT -> "Pulses"
    SoundKind.CONTINUOUS -> "Texture"
    SoundKind.MIXED -> "Pulses and texture"
}
