package com.hapticsync.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.model.BandId
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** A single-choice row of chips (wraps onto several lines on narrow screens). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (option in options) {
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = HsColors.Accent.copy(alpha = 0.22f),
                    selectedLabelColor = HsColors.TextPrimary,
                ),
            )
        }
    }
}

/** Frequency on a logarithmic 0..1 axis spanning the full analysis range. */
object LogFrequency {
    private val LOG_MIN = ln(BandId.MIN_HZ)
    private val LOG_MAX = ln(BandId.MAX_HZ)

    fun toPosition(hz: Float): Float = ((ln(hz.coerceIn(BandId.MIN_HZ, BandId.MAX_HZ)) - LOG_MIN) / (LOG_MAX - LOG_MIN)).coerceIn(0f, 1f)

    fun toHz(position: Float): Float = round(exp(LOG_MIN + position.coerceIn(0f, 1f) * (LOG_MAX - LOG_MIN)))

    /** Rounds to a step that reads naturally at each scale (1 Hz, 5 Hz, 10 Hz, 50 Hz, 100 Hz). */
    fun round(hz: Float): Float {
        val step = when {
            hz < 100f -> 1f
            hz < 500f -> 5f
            hz < 1000f -> 10f
            hz < 5000f -> 50f
            else -> 100f
        }
        return ((hz / step).roundToInt() * step).coerceIn(BandId.MIN_HZ, BandId.MAX_HZ)
    }
}

/** A draggable frequency range on a logarithmic scale. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrequencyRangeSlider(
    label: String,
    lowHz: Float,
    highHz: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = HsColors.Accent,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${formatHz(lowHz)} – ${formatHz(highHz)}", style = MaterialTheme.typography.labelLarge, color = if (enabled) color else HsColors.TextMuted)
        }
        RangeSlider(
            value = LogFrequency.toPosition(lowHz)..LogFrequency.toPosition(highHz),
            onValueChange = { range -> onChange(LogFrequency.toHz(range.start), LogFrequency.toHz(range.endInclusive)) },
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = HsColors.SurfaceHighest,
            ),
        )
    }
}

/** Asks for a name (new preset, rename). */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.length <= MAX_NAME_LENGTH) value = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirmation for destructive actions. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = HsColors.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_NAME_LENGTH = 40
