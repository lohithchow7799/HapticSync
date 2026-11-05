package com.hapticsync.app.ui.demo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hapticsync.app.ui.components.ScreenScaffold
import com.hapticsync.app.ui.components.screenContent
import com.hapticsync.app.ui.theme.HsColors
import com.hapticsync.dsp.synth.DemoScenario

@Composable
fun DemoScreen(viewModel: DemoViewModel, onBack: () -> Unit) {
    val preparing by viewModel.preparing.collectAsState()
    val openingFile by viewModel.openingFile.collectAsState()
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.playAudioFile(uri)
    }

    ScreenScaffold(title = "Demo mode", onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding.screenContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Each scene is synthesised on your phone, then analysed live exactly like a video's soundtrack. Nothing is scripted: what you feel comes from the analysis.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HsColors.TextSecondary,
                )
            }
            items(viewModel.scenarios, key = { it.name }) { scenario ->
                ScenarioCard(
                    scenario = scenario,
                    preparing = preparing == scenario,
                    enabled = preparing == null,
                    firstRender = !viewModel.isReady(scenario),
                    onPlay = { viewModel.play(scenario) },
                )
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Your own audio", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Play any music or sound file with the full visualisation and haptics.",
                            style = MaterialTheme.typography.bodySmall,
                            color = HsColors.TextSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }, enabled = !openingFile) {
                                Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Choose audio file")
                            }
                            if (openingFile) {
                                Spacer(Modifier.width(12.dp))
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = HsColors.Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(scenario: DemoScenario, preparing: Boolean, enabled: Boolean, firstRender: Boolean, onPlay: () -> Unit) {
    val (icon, tint) = visual(scenario)
    Surface(onClick = onPlay, enabled = enabled, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(tint.copy(alpha = 0.16f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(scenario.title, style = MaterialTheme.typography.titleSmall)
                Text(scenario.description, style = MaterialTheme.typography.bodySmall, color = HsColors.TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (preparing && firstRender) "Synthesising…" else "Feel: ${hint(scenario)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (preparing) HsColors.Accent else HsColors.TextMuted,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (preparing) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = HsColors.Accent)
            } else {
                FilledIconButton(
                    onClick = onPlay,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = HsColors.Accent, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${scenario.title}") }
            }
        }
    }
}

private fun visual(scenario: DemoScenario): Pair<ImageVector, Color> = when (scenario) {
    DemoScenario.RACING_ONBOARD -> Icons.Rounded.DirectionsCar to HsColors.Accent
    DemoScenario.TRACKSIDE -> Icons.Rounded.Speed to HsColors.Accent
    DemoScenario.DRUM_GROOVE -> Icons.Rounded.Album to HsColors.Violet
    DemoScenario.BASS_DROP -> Icons.Rounded.GraphicEq to HsColors.Violet
    DemoScenario.EXPLOSIONS -> Icons.Rounded.LocalFireDepartment to HsColors.Danger
    DemoScenario.FIREFIGHT -> Icons.Rounded.Whatshot to HsColors.Danger
    DemoScenario.FOOTSTEPS -> Icons.AutoMirrored.Rounded.DirectionsWalk to HsColors.Success
    DemoScenario.DIALOGUE -> Icons.Rounded.RecordVoiceOver to HsColors.TextSecondary
    DemoScenario.THUNDERSTORM -> Icons.Rounded.FlashOn to HsColors.Teal
    DemoScenario.WORKSHOP -> Icons.Rounded.Build to HsColors.Success
    DemoScenario.TRAILER_MIX -> Icons.Rounded.Movie to HsColors.Danger
    DemoScenario.SYNC_CLICKS -> Icons.Rounded.Timer to HsColors.Teal
}

private fun hint(scenario: DemoScenario): String = when (scenario) {
    DemoScenario.RACING_ONBOARD -> "engine texture that climbs with the revs, a kick at every gear change"
    DemoScenario.TRACKSIDE -> "a swell as each car approaches, peaking as it passes"
    DemoScenario.DRUM_GROOVE -> "kicks as deep thumps, snares as sharper taps, hats kept light"
    DemoScenario.BASS_DROP -> "rising tension, a moment of silence, then a heavy surge"
    DemoScenario.EXPLOSIONS -> "big blasts with rumbling aftershocks and solid impacts"
    DemoScenario.FIREFIGHT -> "crisp single shots and bursts as rapid pulses"
    DemoScenario.FOOTSTEPS -> "light, rhythmic steps that speed up"
    DemoScenario.DIALOGUE -> "almost nothing: speech is kept silent"
    DemoScenario.THUNDERSTORM -> "a distant roll, then a close crack; rain stays subtle"
    DemoScenario.WORKSHOP -> "a low machine hum with clanks and slams"
    DemoScenario.TRAILER_MIX -> "everything together, like a movie trailer"
    DemoScenario.SYNC_CLICKS -> "one crisp tap per second, for checking synchronisation"
}
