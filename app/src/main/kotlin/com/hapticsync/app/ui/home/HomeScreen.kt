package com.hapticsync.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.components.HapticSyncLogo
import com.hapticsync.app.ui.components.formatTime
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.haptics.DeviceCapabilities
import com.hapticsync.model.RecentMedia

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenStudio: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenDemo: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val recents by viewModel.recents.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val presetName by viewModel.activePresetName.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    // Photo Picker: no storage permission needed; falls back to the document picker where absent.
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED)) { uris ->
        viewModel.openUris(uris)
    }
    // Storage Access Framework: any video or audio file from any document provider.
    val browseFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.openUris(uris)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header(onOpenSettings) }
            item {
                OpenCard(
                    busy = busy,
                    onPickVideo = { pickVideos.launch(PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    onBrowse = { browseFiles.launch(arrayOf("video/*", "audio/*")) },
                )
            }
            item {
                HapticsCard(
                    enabled = settings.hapticsEnabled,
                    presetName = presetName,
                    capabilities = viewModel.capabilities,
                    onToggle = viewModel::setHapticsEnabled,
                    onPresets = onOpenPresets,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToolTile("Haptic Studio", "Bands, sounds and feel", Icons.Rounded.GraphicEq, HsColors.Teal, onOpenStudio, Modifier.weight(1f))
                        ToolTile("Presets", "Cinematic, Racing, Music…", Icons.Rounded.Tune, HsColors.Violet, onOpenPresets, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToolTile("Calibration", "Test patterns and sync", Icons.Rounded.Vibration, HsColors.Accent, onOpenCalibration, Modifier.weight(1f))
                        ToolTile("Demo mode", "Feel built-in scenes", Icons.Rounded.PlayCircle, HsColors.Success, onOpenDemo, Modifier.weight(1f))
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recently played", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (recents.isNotEmpty()) TextButton(onClick = viewModel::clearRecents) { Text("Clear") }
                }
            }
            if (recents.isEmpty()) {
                item {
                    Text(
                        "Videos you play appear here so you can pick up where you left off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HsColors.TextSecondary,
                    )
                }
            } else {
                items(recents, key = { it.uri }) { item ->
                    RecentRow(item, viewModel, onOpen = { viewModel.openRecent(item) }, onRemove = { viewModel.removeRecent(item) })
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

private const val MAX_PICKED = 20

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HapticSyncLogo(size = 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("HapticSync", style = MaterialTheme.typography.headlineMedium)
            Text("Feel what you watch", style = MaterialTheme.typography.bodyMedium, color = HsColors.TextSecondary)
        }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun OpenCard(busy: Boolean, onPickVideo: () -> Unit, onBrowse: () -> Unit) {
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(HsColors.Accent.copy(alpha = 0.22f), HsColors.Teal.copy(alpha = 0.06f))))
                .padding(20.dp),
        ) {
            Column {
                Text("Play a video", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "HapticSync listens to the soundtrack as it plays and turns engines, impacts, beats and rumble into vibration, in sync with the picture.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HsColors.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onPickVideo,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = HsColors.Accent, contentColor = MaterialTheme.colorScheme.onPrimary),
                    ) {
                        Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Choose video")
                    }
                    OutlinedButton(onClick = onBrowse, enabled = !busy) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Browse files")
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = HsColors.Accent)
                }
            }
        }
    }
}

@Composable
private fun HapticsCard(
    enabled: Boolean,
    presetName: String,
    capabilities: DeviceCapabilities,
    onToggle: (Boolean) -> Unit,
    onPresets: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Vibration, contentDescription = null, tint = if (enabled) HsColors.Accent else HsColors.TextMuted)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (enabled) "Haptics on" else "Haptics off", style = MaterialTheme.typography.titleMedium)
                    Text("Preset: $presetName", style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
                }
                TextButton(onClick = onPresets) { Text("Change") }
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = capabilities.hasVibrator)
            }
            Spacer(Modifier.height(6.dp))
            if (capabilities.hasVibrator) {
                Text(
                    "This phone: ${capabilities.bestStrategy.label.replaceFirstChar { it.lowercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = HsColors.TextMuted,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = HsColors.Danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "No vibration motor found. Videos play normally with the live analysis, without haptics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HsColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolTile(title: String, subtitle: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(38.dp).background(tint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = tint) }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecentRow(item: RecentMedia, viewModel: HomeViewModel, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val thumbnail by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = if (item.audioOnly) null else viewModel.thumbnail(item.uri)
    }
    Surface(onClick = onOpen, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(112.dp)
                    .height(63.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(HsColors.SurfaceHighest)
                    .border(1.dp, HsColors.Outline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumbnail
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(if (item.audioOnly) Icons.Rounded.MusicNote else Icons.Rounded.VideoLibrary, contentDescription = null, tint = HsColors.TextMuted)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val detail = when {
                    item.durationMs <= 0 -> "Duration unknown"
                    item.progress >= 0.97f -> "${formatTime(item.durationMs)} · watched"
                    item.lastPositionMs > 0 -> "${formatTime(item.lastPositionMs)} of ${formatTime(item.durationMs)}"
                    else -> formatTime(item.durationMs)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
                if (item.progress > 0f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = HsColors.Accent,
                        trackColor = HsColors.SurfaceHighest,
                        drawStopIndicator = {},
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Play from start") }, onClick = {
                        menu = false
                        viewModel.openRecent(item.copy(lastPositionMs = 0L))
                    })
                    DropdownMenuItem(text = { Text("Remove from list") }, onClick = {
                        menu = false
                        onRemove()
                    })
                }
            }
        }
    }
}
