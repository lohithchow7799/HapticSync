package com.hapticsync.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hapticsync.app.ui.components.CategoryChip
import com.hapticsync.app.ui.components.LiveIndicator
import com.hapticsync.app.ui.components.formatTime
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.engine.VisualizationSnapshot
import com.hapticsync.model.SoundCategory
import com.hapticsync.platform.playback.PlayerState
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit, onOpenStudio: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val status by viewModel.status.collectAsState()
    val request by viewModel.request.collectAsState()
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(0L) }
    var showIsolation by remember { mutableStateOf(false) }
    var dominantCategory by remember { mutableIntStateOf(-1) }
    val audioOnly = request?.audioOnly == true || (state.tracksKnown && !state.hasVideo)
    val immersive = (fullscreen || landscape) && !audioOnly

    val nothingToPlay by viewModel.nothingToPlay.collectAsState()
    LaunchedEffect(nothingToPlay) { if (nothingToPlay) onBack() }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    FullscreenEffect(fullscreen && !audioOnly)
    KeepScreenOn(state.isPlaying && settings.keepScreenOn)

    LaunchedEffect(controlsVisible, state.isPlaying, lastInteraction) {
        if (controlsVisible && state.isPlaying) {
            delay(settings.autoHideControlsMs)
            controlsVisible = false
        }
    }
    val interact: () -> Unit = {
        controlsVisible = true
        lastInteraction = System.nanoTime()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (immersive) {
            VideoSurface(viewModel, Modifier.fillMaxSize()) {
                if (controlsVisible) interact() else { controlsVisible = true; lastInteraction = System.nanoTime() }
            }
            AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsVisible = false }) {
                    TopBar(viewModel, state, settings.hapticsEnabled, status.analysing && state.isPlaying, dominantCategory,
                        onBack = { if (fullscreen) fullscreen = false else onBack() }, onIsolation = { showIsolation = true },
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding())
                    CenterControls(viewModel, state, Modifier.align(Alignment.Center), interact)
                    Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (settings.showVisualization) {
                            Box(Modifier.fillMaxWidth().height(56.dp)) {
                                LiveVisualizer(viewModel.engine, compact = true, onDominantCategory = { dominantCategory = it })
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        SeekBar(viewModel, state, interact)
                        BottomButtons(viewModel, state, fullscreen = true, onFullscreen = { fullscreen = !fullscreen; interact() }, interact)
                    }
                }
            }
            if (!controlsVisible && settings.showVisualization) {
                // Keep tracking the detected sound while controls are hidden.
                Box(Modifier.size(1.dp)) { LiveVisualizer(viewModel.engine, compact = true, onDominantCategory = { dominantCategory = it }) }
            }
        } else if (landscape) {
            // Audio-only content in landscape: visualisation beside the controls.
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(12.dp)
                        .background(Color.Black, RoundedCornerShape(20.dp)),
                ) {
                    Box(Modifier.fillMaxSize().padding(14.dp).clickable { viewModel.togglePlayPause() }) {
                        LiveVisualizer(viewModel.engine, onDominantCategory = { dominantCategory = it })
                    }
                    TopBar(viewModel, state, settings.hapticsEnabled, status.analysing && state.isPlaying, dominantCategory,
                        onBack = onBack, onIsolation = { showIsolation = true }, modifier = Modifier.align(Alignment.TopCenter), compact = true)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(state.title.ifEmpty { "Loading…" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SeekBar(viewModel, state, interact)
                    BottomButtons(viewModel, state, fullscreen = false, onFullscreen = {}, interact, showTransport = true)
                    HapticBar(viewModel, profile.haptics.masterIntensity, settings.hapticsEnabled, profile.isolation.enabled,
                        profile.isolation.target.label, onIsolation = { showIsolation = true }, onOpenStudio = onOpenStudio)
                    Banners(state, status.unsupportedReason, viewModel.capabilities.hasVibrator, Modifier)
                    if (request?.syncTest == true) SyncTestPanel(viewModel)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .aspectRatio(if (audioOnly) 1.6f else videoAspect(state))
                        .background(Color.Black),
                ) {
                    if (audioOnly) {
                        Box(Modifier.fillMaxSize().padding(16.dp).clickable { viewModel.togglePlayPause() }) {
                            LiveVisualizer(viewModel.engine, onDominantCategory = { dominantCategory = it })
                        }
                    } else {
                        VideoSurface(viewModel, Modifier.fillMaxSize()) { controlsVisible = !controlsVisible; lastInteraction = System.nanoTime() }
                        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                            CenterControls(viewModel, state, Modifier, interact)
                        }
                    }
                    TopBar(viewModel, state, settings.hapticsEnabled, status.analysing && state.isPlaying, dominantCategory,
                        onBack = onBack, onIsolation = { showIsolation = true }, modifier = Modifier.align(Alignment.TopCenter), compact = true)
                    if (state.isBuffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = HsColors.Accent)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text(state.title.ifEmpty { "Loading…" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SeekBar(viewModel, state, interact)
                    BottomButtons(viewModel, state, fullscreen = false, onFullscreen = { fullscreen = true }, interact, showTransport = audioOnly)
                    HapticBar(viewModel, profile.haptics.masterIntensity, settings.hapticsEnabled, profile.isolation.enabled,
                        profile.isolation.target.label, onIsolation = { showIsolation = true }, onOpenStudio = onOpenStudio)
                }
                Banners(state, status.unsupportedReason, viewModel.capabilities.hasVibrator, Modifier.padding(horizontal = 18.dp))
                if (settings.showVisualization && !audioOnly) {
                    Surface(
                        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Box(Modifier.padding(14.dp)) { LiveVisualizer(viewModel.engine, onDominantCategory = { dominantCategory = it }) }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (request?.syncTest == true) SyncTestPanel(viewModel, Modifier.navigationBarsPadding())
                Spacer(Modifier.navigationBarsPadding())
            }
        }
        if (state.error != null) {
            ErrorCard(state.error!!, Modifier.align(Alignment.Center).padding(24.dp)) { viewModel.play() }
        }
    }

    if (showIsolation) {
        IsolationSheet(
            enabled = profile.isolation.enabled,
            target = profile.isolation.target,
            onChange = { enabled, target -> viewModel.setIsolation(enabled, target) },
            onDismiss = { showIsolation = false },
        )
    }
}

private fun videoAspect(state: PlayerState): Float =
    if (state.videoWidth > 0 && state.videoHeight > 0) (state.videoWidth.toFloat() / state.videoHeight).coerceIn(0.5f, 2.6f) else 16f / 9f

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(viewModel: PlayerViewModel, modifier: Modifier, onTap: () -> Unit) {
    val player = remember(viewModel) { viewModel.exoPlayerForView() }
    AndroidView(
        modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap() },
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view -> view.player = null },
    )
}

@Composable
private fun TopBar(
    viewModel: PlayerViewModel,
    state: PlayerState,
    hapticsEnabled: Boolean,
    live: Boolean,
    dominantCategory: Int,
    onBack: () -> Unit,
    onIsolation: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
        if (!compact) {
            Text(state.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        val category = SoundCategory.entries.getOrNull(dominantCategory)
        if (category != null && live) {
            CategoryChip(category.label, category.family)
            Spacer(Modifier.width(8.dp))
        }
        LiveIndicator(active = live && hapticsEnabled, label = if (hapticsEnabled) "HAPTICS" else "HAPTICS OFF")
        IconButton(onClick = onIsolation) { Icon(Icons.Rounded.CenterFocusStrong, "Sound isolation", tint = Color.White) }
        HapticToggle(hapticsEnabled) { viewModel.setHapticsEnabled(!hapticsEnabled) }
    }
}

@Composable
private fun HapticToggle(enabled: Boolean, onToggle: () -> Unit) {
    FilledIconButton(
        onClick = onToggle,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (enabled) HsColors.Accent else HsColors.SurfaceHighest,
            contentColor = if (enabled) Color.Black else HsColors.TextSecondary,
        ),
    ) { Icon(Icons.Rounded.Vibration, if (enabled) "Turn haptics off" else "Turn haptics on") }
}

@Composable
private fun CenterControls(viewModel: PlayerViewModel, state: PlayerState, modifier: Modifier, interact: () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        IconButton(onClick = { viewModel.previous(); interact() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(34.dp)) }
        IconButton(onClick = { viewModel.seekBy(-10_000); interact() }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(30.dp)) }
        FilledIconButton(
            onClick = { viewModel.togglePlayPause(); interact() },
            modifier = Modifier.size(68.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.92f), contentColor = Color.Black),
        ) {
            Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pause" else "Play", modifier = Modifier.size(38.dp))
        }
        IconButton(onClick = { viewModel.seekBy(10_000); interact() }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(30.dp)) }
        IconButton(onClick = { viewModel.next(); interact() }, enabled = state.hasNext) {
            Icon(Icons.Rounded.SkipNext, "Next", tint = if (state.hasNext) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun SeekBar(viewModel: PlayerViewModel, state: PlayerState, interact: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val value = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it; interact() },
            onValueChangeFinished = { viewModel.seekTo((dragValue * duration).toLong()); dragging = false },
            colors = SliderDefaults.colors(thumbColor = HsColors.Accent, activeTrackColor = HsColors.Accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(if (dragging) (dragValue * duration).toLong() else state.positionMs), color = HsColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(formatTime(state.durationMs), color = HsColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BottomButtons(
    viewModel: PlayerViewModel,
    state: PlayerState,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
    interact: () -> Unit,
    showTransport: Boolean = false,
) {
    var speedMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showTransport) {
            IconButton(onClick = { viewModel.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White) }
            FilledIconButton(onClick = { viewModel.togglePlayPause() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = HsColors.Accent, contentColor = Color.Black)) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = { viewModel.next() }, enabled = state.hasNext) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White) }
        }
        IconButton(onClick = { viewModel.toggleMute(); interact() }) {
            Icon(if (state.volume > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Mute", tint = Color.White)
        }
        Slider(
            value = state.volume,
            onValueChange = { viewModel.setVolume(it); interact() },
            modifier = Modifier.width(110.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
        )
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { speedMenu = true; interact() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Speed, "Playback speed", tint = Color.White)
                    Text("${trimSpeed(state.speed)}×", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                for (s in SPEEDS) {
                    DropdownMenuItem(text = { Text("${trimSpeed(s)}×") }, onClick = { viewModel.setSpeed(s); speedMenu = false })
                }
            }
        }
        if (!showTransport) {
            IconButton(onClick = onFullscreen) {
                Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (fullscreen) "Exit full screen" else "Full screen", tint = Color.White)
            }
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun trimSpeed(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else "%.2f".format(speed).trimEnd('0')

/** Haptics quick controls under the video: intensity, isolation, studio shortcut. */
@Composable
private fun HapticBar(
    viewModel: PlayerViewModel,
    intensity: Float,
    hapticsEnabled: Boolean,
    isolationEnabled: Boolean,
    isolationLabel: String,
    onIsolation: () -> Unit,
    onOpenStudio: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HapticPulse(viewModel, Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Haptic intensity", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${(intensity * 100).toInt()}%", color = HsColors.Accent, style = MaterialTheme.typography.labelLarge)
            }
            Slider(value = intensity, onValueChange = { viewModel.setMasterIntensity(it) }, enabled = hapticsEnabled,
                colors = SliderDefaults.colors(inactiveTrackColor = HsColors.SurfaceHighest))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onIsolation,
                    shape = RoundedCornerShape(50),
                    color = if (isolationEnabled) HsColors.Accent.copy(alpha = 0.18f) else HsColors.SurfaceHighest,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CenterFocusStrong, null, tint = if (isolationEnabled) HsColors.Accent else HsColors.TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isolationEnabled) "Only: $isolationLabel" else "Isolate a sound", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.weight(1f))
                Surface(onClick = onOpenStudio, shape = RoundedCornerShape(50), color = HsColors.SurfaceHighest) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = HsColors.Teal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Haptic Studio", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** A ring that swells with the commanded haptic intensity. */
@Composable
fun HapticPulse(viewModel: PlayerViewModel, modifier: Modifier) {
    val snapshot = remember { VisualizationSnapshot() }
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewModel) {
        while (true) {
            androidx.compose.runtime.withFrameNanos {
                viewModel.engine.snapshot(snapshot)
                level = snapshot.hapticIntensity
            }
        }
    }
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(HsColors.SurfaceHighest, r)
        drawCircle(HsColors.Accent.copy(alpha = 0.25f + 0.75f * level), r * (0.35f + 0.65f * level))
        drawCircle(HsColors.Accent, r, style = Stroke(2f))
    }
}

@Composable
private fun Banners(state: PlayerState, unsupportedReason: String?, hasVibrator: Boolean, modifier: Modifier) {
    val message = when {
        !hasVibrator -> "This device has no vibration motor. You can still watch and see the live analysis."
        state.tracksKnown && !state.hasAudio -> "This video has no audio track, so there is nothing to feel."
        state.tracksKnown && !state.audioSupported -> "This video's audio format can't be decoded on this device, so haptics are unavailable."
        unsupportedReason != null -> unsupportedReason
        else -> null
    } ?: return
    Surface(modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = HsColors.Danger.copy(alpha = 0.12f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = HsColors.Danger)
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BoxScope.ErrorCard(message: String, modifier: Modifier, onRetry: () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Warning, null, tint = HsColors.Danger, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** Locks landscape and hides the system bars while in full-screen mode. */
@Composable
private fun FullscreenEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(enabled) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (enabled) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
