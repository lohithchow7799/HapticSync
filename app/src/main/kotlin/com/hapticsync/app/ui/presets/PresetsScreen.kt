package com.hapticsync.app.ui.presets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.components.ConfirmDialog
import com.hapticsync.app.ui.components.NameDialog
import com.hapticsync.app.ui.components.ScreenScaffold
import com.hapticsync.app.ui.components.screenContent
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.model.BandId
import com.hapticsync.model.BuiltInPresets
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.Preset
import com.hapticsync.model.SoundCategory
import kotlinx.coroutines.launch

@Composable
fun PresetsScreen(
    viewModel: PresetsViewModel,
    onBack: () -> Unit,
    onOpenStudio: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val presets by viewModel.presets.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val modified by viewModel.modified.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    val builtIns = presets.filter { it.builtIn }
    val userPresets = presets.filterNot { it.builtIn }

    ScreenScaffold(
        title = "Presets",
        onBack = onBack,
        bottomBar = bottomBar,
        snackbarHostState = snackbar,
        actions = { IconButton(onClick = { saveDialog = true }) { Icon(Icons.Rounded.Add, contentDescription = "Save current tuning as a preset") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding.screenContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "A preset is a complete tuning: frequency bands, sound categories, feel and analysis. Apply one, then refine it in Haptic Studio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HsColors.TextSecondary,
                )
            }
            item { Heading("Built-in") }
            items(builtIns, key = { it.id }) { preset ->
                PresetCard(
                    preset = preset,
                    active = preset.id == settings.activePresetId,
                    modified = modified && preset.id == settings.activePresetId,
                    onApply = { viewModel.apply(preset); notify("“${preset.name}” applied") },
                    onEdit = { viewModel.apply(preset); onOpenStudio() },
                    onDuplicate = { viewModel.duplicate(preset)?.let { notify("Created “${it.name}”") } },
                    onRename = null,
                    onDelete = null,
                )
            }
            item { Heading("Your presets") }
            if (userPresets.isEmpty()) {
                item {
                    Text(
                        "Save your own tuning with the + button, or duplicate a built-in preset to start from it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HsColors.TextMuted,
                    )
                }
            }
            items(userPresets, key = { it.id }) { preset ->
                PresetCard(
                    preset = preset,
                    active = preset.id == settings.activePresetId,
                    modified = modified && preset.id == settings.activePresetId,
                    onApply = { viewModel.apply(preset); notify("“${preset.name}” applied") },
                    onEdit = { viewModel.apply(preset); onOpenStudio() },
                    onDuplicate = { viewModel.duplicate(preset)?.let { notify("Created “${it.name}”") } },
                    onRename = { renameId = preset.id },
                    onDelete = { deleteId = preset.id },
                )
            }
        }
    }

    if (saveDialog) {
        NameDialog(
            title = "Save current tuning",
            initial = "",
            confirmLabel = "Save",
            onConfirm = { name ->
                saveDialog = false
                val saved = viewModel.saveCurrent(name)
                notify("Saved “${saved.name}”")
            },
            onDismiss = { saveDialog = false },
        )
    }
    presets.firstOrNull { it.id == renameId }?.let { preset ->
        NameDialog(
            title = "Rename preset",
            initial = preset.name,
            confirmLabel = "Rename",
            onConfirm = { name -> viewModel.rename(preset, name); renameId = null },
            onDismiss = { renameId = null },
        )
    }
    presets.firstOrNull { it.id == deleteId }?.let { preset ->
        ConfirmDialog(
            title = "Delete “${preset.name}”?",
            message = "This preset will be removed. Built-in presets are not affected.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.delete(preset); deleteId = null },
            onDismiss = { deleteId = null },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun PresetCard(
    preset: Preset,
    active: Boolean,
    modified: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val profile = preset.profile
    Surface(
        onClick = onApply,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().then(
            if (active) Modifier.border(1.5.dp, HsColors.Accent.copy(alpha = 0.7f), RoundedCornerShape(22.dp)) else Modifier,
        ),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        preset.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Badge(if (modified) "Active · edited" else "Active")
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${preset.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit in Haptic Studio") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                        if (onRename != null) DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        if (onDelete != null) DropdownMenuItem(text = { Text("Delete", color = HsColors.Danger) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp)) {
                val description = when {
                    preset.id == BuiltInPresets.CUSTOM_ID -> "Your latest Haptic Studio tuning."
                    preset.description.isNotBlank() -> preset.description
                    else -> "Your preset."
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    BandBars(profile, Modifier.width(96.dp).height(40.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(summary(profile), style = MaterialTheme.typography.labelMedium, color = HsColors.TextPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(strongest(profile), style = MaterialTheme.typography.labelSmall, color = HsColors.TextMuted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (active) {
                        OutlinedButton(onClick = onEdit) { Text("Fine-tune") }
                    } else {
                        Button(onClick = onApply, colors = ButtonDefaults.buttonColors(containerColor = HsColors.Accent, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Text("Apply")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = HsColors.Accent,
        modifier = Modifier.background(HsColors.Accent.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The six band intensities of a preset, as a miniature bar chart. */
@Composable
private fun BandBars(profile: HapticProfile, modifier: Modifier) {
    Canvas(modifier) {
        val n = BandId.COUNT
        val gap = 4.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        BandId.entries.forEachIndexed { i, band ->
            val s = profile.band(band)
            val level = if (s.enabled) s.intensity.coerceIn(0.04f, 1f) else 0.04f
            val h = size.height * level
            val x = i * (barW + gap)
            drawRoundRect(HsColors.SurfaceHighest, topLeft = Offset(x, 0f), size = Size(barW, size.height), cornerRadius = CornerRadius(barW / 3f))
            drawRoundRect(
                HsColors.band(band).copy(alpha = if (s.enabled) 1f else 0.3f),
                topLeft = Offset(x, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 3f),
            )
        }
    }
}

private fun summary(p: HapticProfile): String {
    val h = p.haptics
    val texture = if (h.continuousLayer) "texture ${(h.continuousGain * 100).toInt()}%" else "no texture"
    return "Intensity ${(h.masterIntensity * 100).toInt()}% · sensitivity ${(h.eventSensitivity * 100).toInt()}% · $texture · release ${h.releaseMs.toInt()} ms"
}

private fun strongest(p: HapticProfile): String {
    val enabled = p.categories.filter { it.enabled && it.category != SoundCategory.GENERAL }
    val top = enabled.sortedByDescending { it.intensity }.take(3).joinToString(", ") { "${it.category.label} ${(it.intensity * 100).toInt()}%" }
    val muted = p.categories.count { !it.enabled }
    return buildString {
        append("Strongest: ").append(top.ifEmpty { "none" })
        if (muted > 0) append(" · $muted muted")
    }
}
