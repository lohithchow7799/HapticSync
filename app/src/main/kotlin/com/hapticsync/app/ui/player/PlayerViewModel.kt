package com.hapticsync.app.ui.player

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hapticsync.app.AppContainer
import com.hapticsync.app.NowPlaying
import com.hapticsync.app.PlaybackRequest
import com.hapticsync.engine.EngineStatus
import com.hapticsync.model.IsolationSettings
import com.hapticsync.model.RecentMedia
import com.hapticsync.platform.playback.HapticPlayer
import com.hapticsync.platform.playback.PlayableItem
import com.hapticsync.platform.playback.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the player and drives the haptic engine for the player screen. Both survive
 * configuration changes (rotation), so playback and analysis continue seamlessly; the playlist
 * and position are also kept in [savedState] so the player can be rebuilt after process death.
 */
class PlayerViewModel(
    private val container: AppContainer,
    private val savedState: SavedStateHandle,
) : ViewModel(), NowPlaying {

    val engine = container.engine
    private val player = HapticPlayer(container.application, engine)
    private val preparationController = HapticPreparationController(container, player)

    /** Haptic preparation ("preparing haptics… then play"). */
    val preparation: StateFlow<PreparationState> = preparationController.state

    override val state: StateFlow<PlayerState> = player.state
    val profile = container.settings.profile
    val settings = container.settings.settings
    val calibration = container.settings.calibration
    val capabilities = container.hapticOutput.capabilities

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _request = MutableStateFlow<PlaybackRequest?>(null)
    val request: StateFlow<PlaybackRequest?> = _request.asStateFlow()

    /** True when the screen was restored without anything to play; the UI closes itself. */
    private val _nothingToPlay = MutableStateFlow(false)
    val nothingToPlay: StateFlow<Boolean> = _nothingToPlay.asStateFlow()

    private var lastRecordedUri: String? = null
    private var mutedVolume = 1f

    init {
        container.acquireEngine()
        container.setNowPlaying(this)
        val initial = container.playbackQueue.consume() ?: restoreRequest()
        if (initial != null) open(initial) else _nothingToPlay.value = true
        viewModelScope.launch {
            // Later requests (another video opened from elsewhere) replace the playlist.
            container.playbackQueue.request.filterNotNull().collect {
                container.playbackQueue.consume()?.let(::open)
            }
        }
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                player.refresh()
                preparationController.update()
                engine.onPlayerPosition(player.exoPlayer.currentPosition * 1000L, System.nanoTime())
                _status.value = engine.status
                val tick = ticks++
                trackRecents(savePosition = tick % 25 == 0)
                if (tick % 5 == 0) savePosition()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun open(request: PlaybackRequest) {
        _request.value = request
        _nothingToPlay.value = false
        lastRecordedUri = null
        saveRequest(request)
        player.setRepeat(request.repeat)
        // Playback starts once the haptics are prepared (see HapticPreparationController).
        player.setItems(request.items, request.startIndex, request.startPositionMs, playWhenReady = false)
        preparationController.onOpen(autoPlay = request.autoPlay)
        preparationController.update()
    }

    private fun trackRecents(savePosition: Boolean) {
        val req = _request.value ?: return
        if (!req.recordInRecents) return
        val s = state.value
        val uri = s.currentUri ?: return
        val key = uri.toString()
        if (key != lastRecordedUri && s.tracksKnown) {
            lastRecordedUri = key
            container.settings.recordPlayed(
                RecentMedia(
                    uri = key,
                    title = s.title.ifEmpty { uri.lastPathSegment ?: "Video" },
                    durationMs = s.durationMs,
                    lastPositionMs = s.positionMs,
                    audioOnly = !s.hasVideo,
                    mimeType = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                        runCatching { container.application.contentResolver.getType(uri) }.getOrNull()
                    } else {
                        null
                    },
                ),
            )
        } else if (savePosition && key == lastRecordedUri) {
            container.settings.updateProgress(key, s.positionMs, s.durationMs)
        }
    }

    // ---- Saved state (process death) --------------------------------------------------------

    private fun saveRequest(request: PlaybackRequest) {
        savedState[KEY_URIS] = ArrayList(request.items.map { it.uri.toString() })
        savedState[KEY_TITLES] = ArrayList(request.items.map { it.title })
        savedState[KEY_MIMES] = ArrayList(request.items.map { it.mimeType.orEmpty() })
        savedState[KEY_INDEX] = request.startIndex
        savedState[KEY_POSITION] = request.startPositionMs
        savedState[KEY_FLAGS] = booleanArrayOf(request.syncTest, request.audioOnly, request.recordInRecents, request.repeat)
    }

    private fun savePosition() {
        val s = state.value
        if (_request.value == null || s.itemCount == 0) return
        savedState[KEY_INDEX] = s.currentIndex
        savedState[KEY_POSITION] = s.positionMs
    }

    private fun restoreRequest(): PlaybackRequest? {
        val uris = savedState.get<ArrayList<String>>(KEY_URIS) ?: return null
        if (uris.isEmpty()) return null
        val titles = savedState.get<ArrayList<String>>(KEY_TITLES).orEmpty()
        val mimes = savedState.get<ArrayList<String>>(KEY_MIMES).orEmpty()
        val flags = savedState.get<BooleanArray>(KEY_FLAGS) ?: BooleanArray(4)
        val items = uris.mapIndexed { i, uri ->
            PlayableItem(Uri.parse(uri), titles.getOrNull(i).orEmpty(), mimes.getOrNull(i)?.takeIf { it.isNotEmpty() })
        }
        return PlaybackRequest(
            items = items,
            startIndex = savedState.get<Int>(KEY_INDEX) ?: 0,
            startPositionMs = savedState.get<Long>(KEY_POSITION) ?: 0L,
            syncTest = flags.getOrElse(0) { false },
            audioOnly = flags.getOrElse(1) { false },
            recordInRecents = flags.getOrElse(2) { true },
            repeat = flags.getOrElse(3) { false },
            // The app was killed in the background: come back paused at the same spot.
            autoPlay = false,
        )
    }

    // ---- Controls ------------------------------------------------------------------------------

    /** The underlying player, for attaching a video surface. */
    fun exoPlayerForView(): androidx.media3.common.Player = player.exoPlayer

    override fun togglePlayPause() {
        val s = state.value
        when {
            preparationController.isWaiting -> preparationController.playNow()
            s.isPlaying || (s.playWhenReady && s.isBuffering) -> pause()
            else -> player.play()
        }
    }

    fun play() = if (preparationController.isWaiting) preparationController.playNow() else player.play()

    /** Starts playback without waiting for haptic preparation to finish. */
    fun playNow() = preparationController.playNow()

    fun pause() {
        player.pause()
        preparationController.onUserPause()
    }

    fun seekTo(positionMs: Long) {
        if (!state.value.seekable) return
        player.seekTo(positionMs)
        preparationController.onSeek(positionMs.coerceAtLeast(0L))
    }

    fun seekBy(deltaMs: Long) = seekTo(player.exoPlayer.currentPosition + deltaMs)
    fun next() = player.next()
    fun previous() = player.previous()
    fun setSpeed(speed: Float) = player.setSpeed(speed)

    fun setVolume(volume: Float) {
        player.setVolume(volume)
        if (volume > 0f) mutedVolume = volume
    }

    fun toggleMute() {
        if (state.value.volume > 0f) {
            mutedVolume = state.value.volume
            player.setVolume(0f)
        } else {
            player.setVolume(mutedVolume.coerceAtLeast(0.2f))
        }
    }

    fun setHapticsEnabled(enabled: Boolean) = container.settings.updateSettings { it.copy(hapticsEnabled = enabled) }

    fun setMasterIntensity(value: Float) = container.settings.updateProfile { it.copy(haptics = it.haptics.copy(masterIntensity = value)) }

    fun setIsolation(isolation: IsolationSettings) =
        container.settings.updateProfileInPlace { it.copy(isolation = isolation.validated()) }

    fun setSyncOffset(ms: Float) = container.settings.updateCalibration { it.copy(syncOffsetMs = ms) }

    fun saveCalibration() = container.settings.saveCalibration()

    /** The app went to the background: pause so nothing plays or vibrates unseen. */
    override fun onBackgrounded() {
        player.pause()
        preparationController.onBackgrounded()
        trackRecents(savePosition = true)
        savePosition()
    }

    override fun onCleared() {
        trackRecents(savePosition = true)
        container.clearNowPlaying(this)
        preparationController.release()
        player.release()
        container.releaseEngine()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
        const val KEY_URIS = "player.uris"
        const val KEY_TITLES = "player.titles"
        const val KEY_MIMES = "player.mimes"
        const val KEY_INDEX = "player.index"
        const val KEY_POSITION = "player.position"
        const val KEY_FLAGS = "player.flags"
    }
}
