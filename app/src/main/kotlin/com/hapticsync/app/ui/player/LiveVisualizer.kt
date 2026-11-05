package com.hapticsync.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.engine.HapticSyncEngine
import com.hapticsync.engine.VisualizationSnapshot
import com.hapticsync.model.SoundCategory
import kotlinx.coroutines.isActive

/**
 * Real-time view of what the engine hears and does, drawn every display frame from the
 * engine's analysis (never synthetic): spectrum, band energies, amplitude envelope with
 * detected-event markers, transient indicator and the commanded haptic intensity.
 *
 * [onDominantCategory] reports changes of the currently detected sound (for the category chip).
 */
@Composable
fun LiveVisualizer(
    engine: HapticSyncEngine,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onDominantCategory: (Int) -> Unit = {},
    onFrame: (VisualizationSnapshot) -> Unit = {},
) {
    val snapshot = remember { VisualizationSnapshot() }
    var frame by remember { mutableLongStateOf(0L) }
    var lastCategory by remember { mutableIntStateOf(-2) }
    val textMeasurer = rememberTextMeasurer()
    LaunchedEffect(engine) {
        while (isActive) {
            withFrameNanos { now ->
                engine.snapshot(snapshot)
                onFrame(snapshot)
                if (snapshot.dominantCategory != lastCategory) {
                    lastCategory = snapshot.dominantCategory
                    onDominantCategory(lastCategory)
                }
                frame = now
            }
        }
    }
    val bandLabels = remember { listOf("SUB", "BASS", "LOW", "MID", "HIGH", "AIR") }
    val labelStyle = remember { TextStyle(color = HsColors.TextMuted, fontSize = 9.sp) }

    Canvas(modifier.fillMaxSize()) {
        if (frame < 0L) return@Canvas // reading the frame clock subscribes this draw to every frame
        val w = size.width
        val h = size.height
        val pad = 8f
        if (compact) {
            drawSpectrum(snapshot, Offset(0f, 0f), Size(w * 0.62f, h))
            drawBands(snapshot, Offset(w * 0.65f, 0f), Size(w * 0.27f, h), null, labelStyle, textMeasurer)
            drawHapticMeter(snapshot, Offset(w * 0.95f, 0f), Size(w * 0.05f, h))
            return@Canvas
        }
        val topH = h * 0.42f
        drawSpectrum(snapshot, Offset(0f, 0f), Size(w * 0.68f, topH))
        drawBands(snapshot, Offset(w * 0.71f, 0f), Size(w * 0.23f, topH - 14f), bandLabels, labelStyle, textMeasurer)
        drawHapticMeter(snapshot, Offset(w * 0.96f, 0f), Size(w * 0.04f, topH - 14f))
        drawTimeline(snapshot, Offset(0f, topH + pad * 2), Size(w, h - topH - pad * 2))
    }
}

private fun DrawScope.drawSpectrum(s: VisualizationSnapshot, origin: Offset, area: Size) {
    val bars = s.spectrum.size
    val gap = 2f
    val barWidth = (area.width - gap * (bars - 1)) / bars
    for (i in 0 until bars) {
        val v = if (s.valid) s.spectrum[i] else 0f
        val barHeight = (area.height * v).coerceAtLeast(2f)
        val x = origin.x + i * (barWidth + gap)
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(HsColors.Accent, HsColors.Teal),
                startY = origin.y + area.height - barHeight,
                endY = origin.y + area.height,
            ),
            topLeft = Offset(x, origin.y + area.height - barHeight),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f),
            alpha = 0.35f + 0.65f * v,
        )
    }
}

private fun DrawScope.drawBands(
    s: VisualizationSnapshot,
    origin: Offset,
    area: Size,
    labels: List<String>?,
    labelStyle: TextStyle,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val n = s.bandLevels.size
    val gap = 6f
    val barWidth = (area.width - gap * (n - 1)) / n
    for (i in 0 until n) {
        val v = if (s.valid) s.bandLevels[i] else 0f
        val x = origin.x + i * (barWidth + gap)
        drawRoundRect(HsColors.SurfaceHighest, Offset(x, origin.y), Size(barWidth, area.height), CornerRadius(6f))
        val barHeight = area.height * v
        drawRoundRect(
            color = if (i <= 1) HsColors.Accent else HsColors.Teal,
            topLeft = Offset(x, origin.y + area.height - barHeight),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(6f),
        )
        if (labels != null) {
            val layout = textMeasurer.measure(labels[i], labelStyle)
            drawText(layout, topLeft = Offset(x + (barWidth - layout.size.width) / 2f, origin.y + area.height + 2f))
        }
    }
}

private fun DrawScope.drawHapticMeter(s: VisualizationSnapshot, origin: Offset, area: Size) {
    drawRoundRect(HsColors.SurfaceHighest, origin, area, CornerRadius(area.width / 2))
    val v = s.hapticIntensity.coerceIn(0f, 1f)
    val hh = area.height * v
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color.White, HsColors.Accent), startY = origin.y + area.height - hh, endY = origin.y + area.height),
        topLeft = Offset(origin.x, origin.y + area.height - hh),
        size = Size(area.width, hh),
        cornerRadius = CornerRadius(area.width / 2),
    )
}

private fun DrawScope.drawTimeline(s: VisualizationSnapshot, origin: Offset, area: Size) {
    drawRoundRect(HsColors.SurfaceHigh, origin, area, CornerRadius(14f))
    val n = s.envelope.size
    val step = area.width / (n - 1)
    // Amplitude envelope (filled).
    val envelope = Path().apply {
        moveTo(origin.x, origin.y + area.height)
        for (i in 0 until n) lineTo(origin.x + i * step, origin.y + area.height * (1f - 0.85f * s.envelope[i]))
        lineTo(origin.x + area.width, origin.y + area.height)
        close()
    }
    drawPath(envelope, Brush.verticalGradient(listOf(HsColors.Teal.copy(alpha = 0.55f), HsColors.Teal.copy(alpha = 0.05f)), origin.y, origin.y + area.height))
    // Commanded haptic intensity (line).
    val haptic = Path().apply {
        for (i in 0 until n) {
            val x = origin.x + i * step
            val y = origin.y + area.height * (1f - 0.9f * s.hapticHistory[i])
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    drawPath(haptic, HsColors.Accent, style = Stroke(width = 3f, cap = StrokeCap.Round))
    // Event markers, coloured by sound family.
    for (m in 0 until s.markerCount) {
        val x = origin.x + area.width * s.markerPosition[m]
        val category = SoundCategory.entries.getOrNull(s.markerCategory[m]) ?: continue
        val color = HsColors.category(category)
        drawLine(color.copy(alpha = 0.5f), Offset(x, origin.y + 6f), Offset(x, origin.y + area.height - 6f), strokeWidth = 2f)
        drawCircle(color, radius = 4f + 6f * s.markerStrength[m], center = Offset(x, origin.y + 12f))
    }
    // "Now" line and transient ring.
    val nowX = origin.x + area.width - 2f
    drawLine(Color.White.copy(alpha = 0.6f), Offset(nowX, origin.y), Offset(nowX, origin.y + area.height), strokeWidth = 2f)
    val t = s.transientIntensity
    if (t > 0.05f) {
        drawCircle(HsColors.Accent.copy(alpha = 0.25f + 0.6f * t), radius = 8f + 18f * t, center = Offset(nowX - 22f, origin.y + 22f), style = Stroke(3f))
    }
}
