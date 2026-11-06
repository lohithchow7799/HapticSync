package com.hapticsync.app

import android.app.Application
import android.os.Build
import android.os.Process
import com.hapticsync.app.data.DemoClipStore
import com.hapticsync.app.data.HapticSettingsController
import com.hapticsync.app.data.MediaLibrary
import com.hapticsync.app.data.SettingsRepository
import com.hapticsync.engine.EngineThreadFactory
import com.hapticsync.engine.HapticSyncEngine
import com.hapticsync.model.BuiltInPresets
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.platform.playback.MediaAudioDecoder
import com.hapticsync.platform.playback.PlayableItem
import com.hapticsync.platform.playback.PlayerState
import com.hapticsync.platform.vibration.AndroidHapticOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** What the player should open next. */
data class PlaybackRequest(
    val items: List<PlayableItem>,
    val startIndex: Int = 0,
    val startPositionMs: Long = 0L,
    /** Show the synchronisation-test overlay (calibration). */
    val syncTest: Boolean = false,
    /** Audio-only content (demo scenes, audio clips): the visualisation takes the stage. */
    val audioOnly: Boolean = false,
    /** Whether the items belong in "Recently played" (user media, not demo scenes). */
    val recordInRecents: Boolean = true,
    /** Loop the current item (used by the synchronisation test). */
    val repeat: Boolean = false,
    /** Start playing as soon as the media is ready (false when restoring after process death). */
    val autoPlay: Boolean = true,
)

/** Hand-off of the next playback request from any screen to the player screen. */
class PlaybackQueue {
    private val _request = MutableStateFlow<PlaybackRequest?>(null)
    val request: StateFlow<PlaybackRequest?> = _request.asStateFlow()

    private val _navigation = Channel<Unit>(Channel.CONFLATED)

    /** Emits once per [open] so the navigation host can bring the player to the front. */
    val navigation: Flow<Unit> = _navigation.receiveAsFlow()

    fun open(request: PlaybackRequest) {
        _request.value = request
        _navigation.trySend(Unit)
    }

    fun consume(): PlaybackRequest? = _request.value.also { _request.value = null }
}

/** The player that is currently open, so other screens can show a compact now-playing bar. */
interface NowPlaying {
    val state: StateFlow<PlayerState>
    fun togglePlayPause()

    /** The app left the foreground: stop playback so nothing plays or vibrates unseen. */
    fun onBackgrounded()
}

/**
 * Manual dependency container (one per process). Owns the long-lived objects: persistence, the
 * vibrator output and the haptic engine.
 */
class AppContainer(val application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val deviceKey: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    val repository = SettingsRepository(application)
    val hapticOutput = AndroidHapticOutput(application)

    /** The audio-to-haptics engine. Its threads only run while a player holds it. */
    val engine = HapticSyncEngine(
        output = hapticOutput,
        profile = BuiltInPresets.CINEMATIC.profile,
        calibration = CalibrationProfile(deviceKey = deviceKey),
        threadFactory = AUDIO_THREAD_FACTORY,
    )

    val settings = HapticSettingsController(scope, repository, engine, hapticOutput, deviceKey)

    /** Decodes soundtracks ahead of playback for preparing haptics. */
    val audioDecoder = MediaAudioDecoder(application)

    /** Thread factory for engine and preparation threads (audio or background priority). */
    val threadFactory: EngineThreadFactory = AUDIO_THREAD_FACTORY
    val mediaLibrary = MediaLibrary(application)
    val demoClips = DemoClipStore(application)
    val playbackQueue = PlaybackQueue()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var engineUsers = 0

    /** Starts the engine threads for a player (main thread). Pair with [releaseEngine]. */
    fun acquireEngine() {
        if (engineUsers++ == 0) engine.start()
    }

    /** Stops the engine threads once no player needs them (main thread). */
    fun releaseEngine() {
        if (engineUsers > 0 && --engineUsers == 0) engine.close()
    }

    fun setNowPlaying(player: NowPlaying) {
        _nowPlaying.value = player
    }

    fun clearNowPlaying(player: NowPlaying) {
        if (_nowPlaying.value === player) _nowPlaying.value = null
    }

    /** Called when the activity stops for real (not for a configuration change). */
    fun onAppBackgrounded() {
        _nowPlaying.value?.onBackgrounded()
        engine.stopOutput()
    }

    private companion object {
        /** Engine threads run at audio priority so analysis keeps up with playback. */
        val AUDIO_THREAD_FACTORY = EngineThreadFactory { name, realtime, body ->
            Thread({
                Process.setThreadPriority(if (realtime) Process.THREAD_PRIORITY_AUDIO else Process.THREAD_PRIORITY_BACKGROUND)
                body.run()
            }, name).apply { isDaemon = true }
        }
    }
}
