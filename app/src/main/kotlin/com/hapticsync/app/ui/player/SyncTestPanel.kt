package com.hapticsync.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.components.InfoRow
import com.hapticsync.app.ui.components.LabeledSlider
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.engine.VisualizationSnapshot
import com.hapticsync.model.CalibrationProfile

/**
 * Synchronisation test: plays a click every second. The circle flashes when the analysis says
 * a click is being heard; the phone should tap at the same moment. Adjust the offset until
 * sound, flash and tap coincide.
 */
@Composable
fun SyncTestPanel(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val calibration by viewModel.calibration.collectAsState()
    val status by viewModel.status.collectAsState()
    val snapshot = remember { VisualizationSnapshot() }
    var flash by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewModel) {
        while (true) {
            withFrameNanos {
                viewModel.engine.snapshot(snapshot)
                var newest = 0f
                for (i in 0 until snapshot.markerCount) newest = maxOf(newest, snapshot.markerPosition[i])
                // Flash for ~120 ms after a click is heard (markers sit at 1.0 when "now").
                val ageSec = (1f - newest) * VisualizationSnapshot.HISTORY_SECONDS
                flash = if (snapshot.markerCount > 0 && ageSec < 0.12f) 1f - ageSec / 0.12f else 0f
            }
        }
    }
    Surface(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(56.dp)) {
                    drawCircle(HsColors.SurfaceHighest)
                    drawCircle(HsColors.Accent.copy(alpha = flash), radius = size.minDimension / 2f * (0.6f + 0.4f * flash))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Synchronisation test", style = MaterialTheme.typography.titleMedium)
                    Text("Listen to the click, watch the flash and feel the tap. Move the slider until all three happen together.",
                        style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
                }
            }
            LabeledSlider(
                label = "Sync offset (negative = earlier)",
                value = calibration.syncOffsetMs,
                onValueChange = { viewModel.setSyncOffset(it) },
                valueRange = -CalibrationProfile.MAX_SYNC_OFFSET_MS..CalibrationProfile.MAX_SYNC_OFFSET_MS,
                format = { "${if (it > 0) "+" else ""}${it.toInt()} ms" },
            )
            InfoRow("Analysis look-ahead", "${status.lookaheadMs.toInt()} ms")
            InfoRow("Dispatch timing error (mean)", "%.1f ms".format(status.meanDispatchErrorMs))
            InfoRow("Haptic pulses sent", status.transientsDispatched.toString())
            InfoRow("Late events dropped", status.droppedStale.toString())
            FilledTonalButton(onClick = { viewModel.saveCalibration() }, modifier = Modifier.padding(top = 6.dp)) { Text("Save offset") }
        }
    }
}
