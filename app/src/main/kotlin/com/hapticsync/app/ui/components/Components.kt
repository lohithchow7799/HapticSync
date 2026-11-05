package com.hapticsync.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Forest
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.model.SoundFamily

/** The HapticSync mark: a waveform resolving into haptic pulse rings. */
@Composable
fun HapticSyncLogo(modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.055f
        val points = listOf(0.12f to 0.5f, 0.24f to 0.5f, 0.31f to 0.36f, 0.4f to 0.66f, 0.48f to 0.2f, 0.56f to 0.8f, 0.64f to 0.32f, 0.7f to 0.56f, 0.75f to 0.5f)
        for (i in 0 until points.size - 1) {
            drawLine(
                brush = Brush.linearGradient(listOf(HsColors.Accent, Color(0xFFFF9A6B))),
                start = Offset(points[i].first * w, points[i].second * h),
                end = Offset(points[i + 1].first * w, points[i + 1].second * h),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        drawArc(HsColors.Teal, -55f, 110f, false, topLeft = Offset(w * 0.62f, h * 0.36f), size = androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.28f), style = Stroke(stroke * 0.8f, cap = StrokeCap.Round))
        drawArc(HsColors.Teal.copy(alpha = 0.55f), -55f, 110f, false, topLeft = Offset(w * 0.6f, h * 0.24f), size = androidx.compose.ui.geometry.Size(w * 0.38f, h * 0.52f), style = Stroke(stroke * 0.8f, cap = StrokeCap.Round))
    }
}

/** Pulsing dot shown while the haptic engine is analysing live audio. */
@Composable
fun LiveIndicator(active: Boolean, modifier: Modifier = Modifier, label: String = "LIVE") {
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
            .border(1.dp, if (active) HsColors.Accent.copy(alpha = 0.6f) else HsColors.Outline, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (active) pulse else 0.4f)
                .background(if (active) HsColors.Accent else HsColors.TextMuted, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) HsColors.TextPrimary else HsColors.TextMuted)
    }
}

/** Rounded section container. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.size(12.dp))
            content()
        }
    }
}

/** A labelled slider with its formatted value. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    format: (Float) -> String = { "${(it * 100).toInt()}%" },
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = if (enabled) HsColors.Accent else HsColors.TextMuted)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(inactiveTrackColor = HsColors.SurfaceHighest),
        )
    }
}

/** A row with a label, optional description and a switch. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Compact key/value line for capability and statistics read-outs. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = HsColors.TextPrimary) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = HsColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

fun familyIcon(family: SoundFamily): ImageVector = when (family) {
    SoundFamily.VEHICLE -> Icons.Rounded.DirectionsCar
    SoundFamily.MUSIC -> Icons.Rounded.Album
    SoundFamily.CINEMATIC -> Icons.Rounded.LocalFireDepartment
    SoundFamily.ENVIRONMENT -> Icons.Rounded.Forest
    SoundFamily.BACKGROUND -> Icons.Rounded.RecordVoiceOver
}

/** Pill naming the sound currently detected. */
@Composable
fun CategoryChip(label: String, family: SoundFamily?, modifier: Modifier = Modifier) {
    val color = family?.let { HsColors.family(it) } ?: HsColors.TextMuted
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(family?.let(::familyIcon) ?: Icons.Rounded.GraphicEq, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = HsColors.TextPrimary, maxLines = 1)
    }
}

/** mm:ss or h:mm:ss. */
fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatHz(hz: Float): String = if (hz >= 1000f) "%.1f kHz".format(hz / 1000f) else "%.0f Hz".format(hz)
