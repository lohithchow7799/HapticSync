package com.hapticsync.app.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hapticsync.app.AppContainer
import com.hapticsync.app.PlaybackRequest
import com.hapticsync.dsp.synth.DemoScenario
import com.hapticsync.haptics.CalibrationPattern
import com.hapticsync.haptics.DeviceCapabilities
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.platform.playback.PlayableItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class CalibrationViewModel(private val container: AppContainer) : ViewModel() {

    private val controller = container.settings

    val calibration: StateFlow<CalibrationProfile> = controller.calibration
    val capabilities: DeviceCapabilities = container.hapticOutput.capabilities
    val predefinedEffects: String = container.hapticOutput.predefinedEffectSupport
    val deviceKey: String = controller.deviceKey

    private val _preparingSyncTest = MutableStateFlow(false)
    val preparingSyncTest: StateFlow<Boolean> = _preparingSyncTest.asStateFlow()

    private val _lastPattern = MutableStateFlow<CalibrationPattern?>(null)
    /** The test pattern played most recently (highlighted in the UI). */
    val lastPattern: StateFlow<CalibrationPattern?> = _lastPattern.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun play(pattern: CalibrationPattern) {
        if (!capabilities.hasVibrator) {
            _messages.trySend("This device has no vibration motor.")
            return
        }
        _lastPattern.value = pattern
        if (!container.engine.preview(pattern)) _messages.trySend("This pattern could not be played on this device.")
    }

    fun stop() {
        _lastPattern.value = null
        container.engine.stopOutput()
    }

    fun update(transform: (CalibrationProfile) -> CalibrationProfile) = controller.updateCalibration(transform)

    fun save() {
        controller.saveCalibration()
        _messages.trySend("Calibration saved for $deviceKey")
    }

    fun reset() {
        controller.resetCalibration()
        _messages.trySend("Calibration reset to defaults")
    }

    /** Opens the synchronisation test: a looping click track with the offset slider on screen. */
    fun startSyncTest() {
        if (_preparingSyncTest.value) return
        _preparingSyncTest.value = true
        viewModelScope.launch {
            try {
                val scenario = DemoScenario.SYNC_CLICKS
                val uri = container.demoClips.uri(scenario)
                container.playbackQueue.open(
                    PlaybackRequest(
                        items = listOf(PlayableItem(uri, scenario.title, "audio/wav")),
                        syncTest = true,
                        audioOnly = true,
                        recordInRecents = false,
                        repeat = true,
                    ),
                )
            } finally {
                _preparingSyncTest.value = false
            }
        }
    }
}
