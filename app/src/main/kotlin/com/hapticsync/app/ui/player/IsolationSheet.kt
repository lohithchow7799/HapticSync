package com.hapticsync.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.components.SwitchRow
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.model.BandId
import com.hapticsync.model.IsolationGroup
import com.hapticsync.model.IsolationTarget
import com.hapticsync.model.SoundCategory
import com.hapticsync.model.SoundFamily

/**
 * Component isolation: feel exactly one component (a sound category, a frequency band or a
 * group) while the haptic engine ignores everything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IsolationSheet(
    enabled: Boolean,
    target: IsolationTarget,
    onChange: (Boolean, IsolationTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        IsolationPicker(
            enabled,
            target,
            onChange,
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun IsolationPicker(
    enabled: Boolean,
    target: IsolationTarget,
    onChange: (Boolean, IsolationTarget) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    // Not scrollable itself, so it can sit inside a lazy list; the sheet adds scrolling.
    Column(modifier) {
        if (showHeader) {
            Text("Sound isolation", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Feel only one component of the soundtrack. Everything else is ignored by the haptic engine.",
                style = MaterialTheme.typography.bodyMedium,
                color = HsColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
        }
        SwitchRow("Isolation mode", enabled, { onChange(it, target) }, description = if (enabled) "Feeling only: ${target.label}" else "Off: all enabled sounds are felt")

        @Composable
        fun chips(title: String, targets: List<IsolationTarget>) {
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = HsColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in targets) {
                    val selected = enabled && t == target
                    FilterChip(
                        selected = selected,
                        onClick = { onChange(true, t) },
                        label = { Text(t.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HsColors.Accent.copy(alpha = 0.22f),
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
