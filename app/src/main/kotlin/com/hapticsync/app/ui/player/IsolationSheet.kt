package com.hapticsync.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.components.SwitchRow
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.model.BandId
import com.hapticsync.model.IsolationGroup
import com.hapticsync.model.IsolationSettings
import com.hapticsync.model.IsolationTarget
import com.hapticsync.model.SoundCategory
import com.hapticsync.model.SoundFamily

/**
 * Sound isolation: feel only the selected components (any number of sound categories,
 * frequency bands and groups) while the haptic engine ignores everything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IsolationSheet(
    settings: IsolationSettings,
    onChange: (IsolationSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        IsolationPicker(
            settings,
            onChange,
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IsolationPicker(
    settings: IsolationSettings,
    onChange: (IsolationSettings) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    // Not scrollable itself, so it can sit inside a lazy list; the sheet adds scrolling.
    Column(modifier) {
        if (showHeader) {
            Text("Sound isolation", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pick as many sounds as you like: only those are felt, everything else is ignored by the haptic engine.",
                style = MaterialTheme.typography.bodyMedium,
                color = HsColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
        }
        val count = settings.targets.size
        SwitchRow(
            "Isolation mode",
            settings.isActive,
            { on -> onChange(settings.copy(enabled = on).validated()) },
            description = when {
                count == 0 -> "Select one or more sounds below"
                settings.isActive -> "Feeling only: ${settings.summary(maxNames = 3)}"
                else -> "Off: all enabled sounds are felt ($count selected)"
            },
            enabled = count > 0,
        )
        if (count > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$count selected", style = MaterialTheme.typography.labelLarge, color = HsColors.Accent, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(IsolationSettings()) }) { Text("Clear all") }
            }
        }

        @Composable
        fun chips(title: String, targets: List<IsolationTarget>) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = HsColors.TextSecondary, modifier = Modifier.weight(1f))
                val allSelected = targets.all { settings.contains(it) }
                TextButton(onClick = {
                    val next = if (allSelected) settings.targets - targets.toSet() else (settings.targets + targets).distinct()
                    onChange(IsolationSettings(enabled = next.isNotEmpty(), targets = next).validated())
                }) { Text(if (allSelected) "None" else "All") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in targets) {
                    val selected = settings.contains(t)
                    FilterChip(
                        selected = selected,
                        onClick = { onChange(settings.toggled(t).validated()) },
                        label = { Text(t.label) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HsColors.Accent.copy(alpha = if (settings.isActive) 0.24f else 0.1f),
                            selectedLabelColor = HsColors.TextPrimary,
                        ),
                    )
                }
            }
        }
        chips("Quick picks", IsolationGroup.entries.map { IsolationTarget.of(it) })
        for (family in SoundFamily.entries) {
            val categories = SoundCategory.byFamily(family).filter { it != SoundCategory.DIALOGUE }
            if (categories.isNotEmpty()) chips(family.label, categories.map { IsolationTarget.of(it) })
        }
        chips("Frequency bands", BandId.entries.map { IsolationTarget.of(it) })
        if (showHeader) Spacer(Modifier.height(24.dp))
    }
}
