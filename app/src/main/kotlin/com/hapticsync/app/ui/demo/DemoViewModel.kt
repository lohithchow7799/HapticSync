package com.hapticsync.app.ui.demo

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hapticsync.app.AppContainer
import com.hapticsync.app.PlaybackRequest
import com.hapticsync.dsp.synth.DemoScenario
import com.hapticsync.platform.playback.PlayableItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Demo mode: procedurally synthesised scenes (no bundled media) played through the normal
 * player. The haptics come from analysing the scene's audio live, exactly as for a video.
 */
class DemoViewModel(private val container: AppContainer) : ViewModel() {

    private val _preparing = MutableStateFlow<DemoScenario?>(null)
    /** The scene being synthesised, if any. */
    val preparing: StateFlow<DemoScenario?> = _preparing.asStateFlow()

    private val _openingFile = MutableStateFlow(false)
    val openingFile: StateFlow<Boolean> = _openingFile.asStateFlow()

    val scenarios: List<DemoScenario> = DemoScenario.entries

    fun isReady(scenario: DemoScenario): Boolean = container.demoClips.isRendered(scenario)

    fun play(scenario: DemoScenario) {
        if (_preparing.value != null) return
        _preparing.value = scenario
        viewModelScope.launch {
            try {
                val uri = container.demoClips.uri(scenario)
                container.playbackQueue.open(
                    PlaybackRequest(
                        items = listOf(PlayableItem(uri, scenario.title, "audio/wav")),
                        audioOnly = true,
                        recordInRecents = false,
                        repeat = scenario == DemoScenario.SYNC_CLICKS,
                    ),
                )
            } finally {
                _preparing.value = null
            }
        }
    }

    /** Plays an audio file of the user's choice with the full visualisation. */
    fun playAudioFile(uri: Uri) {
        if (_openingFile.value) return
        _openingFile.value = true
        viewModelScope.launch {
            try {
                container.mediaLibrary.persistAccess(uri)
                val info = container.mediaLibrary.info(uri)
                container.playbackQueue.open(
                    PlaybackRequest(items = listOf(PlayableItem(uri, info.title, info.mimeType)), audioOnly = !info.hasVideo),
                )
            } finally {
                _openingFile.value = false
            }
        }
    }
}
