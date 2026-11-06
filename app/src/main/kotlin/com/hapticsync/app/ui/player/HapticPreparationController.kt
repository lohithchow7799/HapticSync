package com.hapticsync.app.ui.player

import android.net.Uri
import androidx.media3.common.Player
import com.hapticsync.app.AppContainer
import com.hapticsync.engine.DetectionSettings
import com.hapticsync.engine.HapticTrack
import com.hapticsync.platform.playback.HapticPlayer
import com.hapticsync.platform.playback.HapticTrackPreparer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the player shows about haptic preparation. */
data class PreparationState(
    /** Playback is held until enough of the soundtrack is prepared. */
    val waiting: Boolean = false,
    /** Fraction of the audio needed before playback starts that is prepared. */
    val progress: Float = 0f,
    /** Haptics come from a prepared (pre-analysed) track. */
    val prepared: Boolean = false,
    /** Why haptics are generated live for this item, when they are. */
    val liveReason: String? = null,
)

/**
 * "Prepare haptics, then play": analyses the soundtrack ahead of playback and holds playback
 * until enough is ready, so every vibration can be placed exactly with its sound and judged
 * against what comes after it.
 *
 * - When an item opens, playback waits until [START_LEAD_US] of audio is prepared (a second or
 *   two on a modern phone), then starts; preparation continues in the background, far ahead of
 *   playback.
 * - After a seek outside the prepared range, playback pauses briefly while that part is
 *   prepared ([SEEK_LEAD_US]).
 * - Changing detection settings (bands, sensitivities, analysis) re-prepares from the current
 *   position in the background and switches over once ready; output settings apply at once.
 * - If the audio cannot be decoded ahead of playback, haptics fall back to live analysis.
 *
 * Main thread only.
 */
class HapticPreparationController(
    private val container: AppContainer,
    private val player: HapticPlayer,
) {
    private val _state = MutableStateFlow(PreparationState())
    val state: StateFlow<PreparationState> = _state.asStateFlow()

    private var uri: Uri? = null
    private var preparer: HapticTrackPreparer? = null
    private var pending: HapticTrackPreparer? = null
    private var detection: DetectionSettings? = null
    private var failure: String? = null

    private var holdForStart = false
    private var wantPlay = false
    private var autoPaused = false
    private var skipUntilSeek = false

    /** New items were set (with playWhenReady false): hold playback until prepared. */
    fun onOpen(autoPlay: Boolean) {
        holdForStart = true
        wantPlay = autoPlay
        autoPaused = false
        skipUntilSeek = false
    }

    /** Whether playback is being held for preparation (the play button then means "play now"). */
    val isWaiting: Boolean get() = holdForStart || autoPaused

    /** Starts playback without waiting; haptics join as soon as their audio is prepared. */
    fun playNow() {
        holdForStart = false
        autoPaused = false
        wantPlay = true
        skipUntilSeek = true
        player.play()
        update()
    }

    /** The user paused: never resume automatically. */
    fun onUserPause() {
        autoPaused = false
        wantPlay = false
        if (holdForStart) holdForStart = false
        update()
    }

    fun onSeek(positionMs: Long) {
        skipUntilSeek = false
        preparer?.ensure(positionMs * 1000L)
        update()
    }

    /** The app left the foreground: playback was paused and must not restart by itself. */
    fun onBackgrounded() {
        autoPaused = false
        wantPlay = false
    }

    /** Periodic update from the player's poll loop. */
    fun update() {
        val s = player.state.value
        val settings = container.settings.settings.value
        val profile = container.settings.profile.value
        val hasVibrator = container.hapticOutput.capabilities.hasVibrator
        val current = s.currentUri
        if (!settings.prepareHaptics || !hasVibrator || current == null) {
            if (preparer != null) teardown()
            releaseHold()
            _state.value = PreparationState(liveReason = if (hasVibrator && current != null) "Live analysis (preparing is off in Settings)" else null)
            return
        }
        if (current != uri) {
            teardown()
            uri = current
            failure = null
            detection = DetectionSettings.of(profile)
            preparer = newPreparer(current, s.positionMs * 1000L)
            container.engine.setPreparedTrack(preparer!!.track)
        }
        val failed = failure
        if (failed != null) {
            releaseHold()
            _state.value = PreparationState(liveReason = failed)
            return
        }
        val active = preparer ?: return
        val status = active.status.value
        if (status is HapticTrackPreparer.Status.Failed) {
            // Live analysis for this item.
            failure = "Live analysis: ${status.message}"
            active.close()
            preparer = null
            pending?.close()
            pending = null
            container.engine.setPreparedTrack(null)
            releaseHold()
            _state.value = PreparationState(liveReason = failure)
            return
        }

        val positionUs = s.positionMs * 1000L
        val durationUs = s.durationMs * 1000L
        active.playbackPositionUs = positionUs
        if (durationUs > 0) active.track.durationUs = durationUs

        // Detection settings changed: prepare again in the background, switch when ready.
        val det = DetectionSettings.of(profile)
        if (det != detection) {
            detection = det
            pending?.close()
            pending = newPreparer(current, positionUs)
        }
        pending?.let { next ->
            next.playbackPositionUs = positionUs
            if (durationUs > 0) next.track.durationUs = durationUs
            when {
                next.status.value is HapticTrackPreparer.Status.Failed -> {
                    next.close()
                    pending = null
                }
                next.track.isReady(positionUs, limit(positionUs + SWAP_LEAD_US, durationUs)) -> {
                    container.engine.setPreparedTrack(next.track)
                    active.close()
                    preparer = next
                    pending = null
                }
            }
        }

        val track: HapticTrack = (preparer ?: return).track
        val lead = if (holdForStart) START_LEAD_US else SEEK_LEAD_US
        val target = limit(positionUs + lead, durationUs)
        val ready = track.isReady(positionUs, target)
        if (!ready) preparer?.ensure(positionUs)
        val readyEnd = track.readyEndFrom(positionUs)
        val progress = if (ready) 1f else (((readyEnd ?: positionUs) - positionUs).toFloat() / (target - positionUs).coerceAtLeast(1L)).coerceIn(0f, 1f)
        val mustWait = settings.hapticsEnabled && !skipUntilSeek
        when {
            holdForStart -> {
                val loaded = s.playbackState != Player.STATE_IDLE || s.error != null
                if (loaded && (ready || !mustWait)) {
                    holdForStart = false
                    if (wantPlay) player.play()
                }
            }
            autoPaused -> if (ready || !mustWait) {
                autoPaused = false
                player.play()
            }
            s.isPlaying && mustWait && !track.isReady(positionUs, limit(positionUs + MIN_PLAYING_LEAD_US, durationUs)) -> {
                // Seeked (or played) into audio that is not prepared yet: hold briefly.
                player.pause()
                autoPaused = true
            }
        }
        _state.value = PreparationState(waiting = holdForStart || autoPaused, progress = progress, prepared = true)
    }

    fun release() {
        teardown()
    }

    private fun releaseHold() {
        if (holdForStart && (player.state.value.playbackState != Player.STATE_IDLE || player.state.value.error != null)) {
            holdForStart = false
            if (wantPlay) player.play()
        }
        if (autoPaused) {
            autoPaused = false
            player.play()
        }
    }

    private fun newPreparer(uri: Uri, fromUs: Long): HapticTrackPreparer {
        val track = HapticTrack()
        val durationUs = player.state.value.durationMs * 1000L
        if (durationUs > 0) track.durationUs = durationUs
        return HapticTrackPreparer(container.audioDecoder, uri, track, container.settings.profile.value, container.threadFactory)
            .also { it.start(fromUs) }
    }

    private fun teardown() {
        preparer?.close()
        pending?.close()
        preparer = null
        pending = null
        uri = null
        container.engine.setPreparedTrack(null)
    }

    private fun limit(timeUs: Long, durationUs: Long): Long = if (durationUs > 0) minOf(timeUs, durationUs) else timeUs

    companion object {
        /** Audio prepared before playback starts. */
        const val START_LEAD_US = 15_000_000L

        /** Audio prepared before playback resumes after a seek into unprepared audio. */
        const val SEEK_LEAD_US = 5_000_000L

        /** Playback pauses when less than this is prepared ahead of it. */
        const val MIN_PLAYING_LEAD_US = 1_000_000L

        /** A re-prepared track takes over once this much is ready at the current position. */
        const val SWAP_LEAD_US = 3_000_000L
    }
}
