package com.hapticsync.platform.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.hapticsync.engine.MediaAudioInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A media item to play. */
data class PlayableItem(val uri: Uri, val title: String, val mimeType: String? = null)

/** Observable player state for the UI. */
data class PlayerState(
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentIndex: Int = 0,
    val itemCount: Int = 0,
    val title: String = "",
    val currentUri: Uri? = null,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = true,
    val audioSupported: Boolean = true,
    val tracksKnown: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val error: String? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /** Whether the player can seek in the current item (false for unindexed streams). */
    val seekable: Boolean = true,
    /** Container format of the current item (e.g. video/mp4), once known. */
    val containerMimeType: String? = null,
) {
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
    val isEnded: Boolean get() = playbackState == Player.STATE_ENDED
}

/**
 * ExoPlayer configured for HapticSync:
 * - its audio renderer writes into a [PcmTapAudioSink], so the haptic engine ([audioInput])
 *   analyses exactly the decoded audio being played, with presentation timestamps;
 * - it requests audio focus (pausing for calls and other apps) and pauses when headphones are
 *   unplugged ("becoming noisy");
 * - its state is exposed as a [StateFlow].
 *
 * Must be created and used on the main thread.
 */
@OptIn(UnstableApi::class)
class HapticPlayer(context: Context, audioInput: MediaAudioInput) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context, HapticRenderersFactory(context, audioInput))
        // Seek indexing for files whose container has no index (otherwise seeks restart at 0).
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, SeekIndexingExtractorsFactory(context)))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        .build()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _state.update { it.copy(tracksKnown = false, error = null) }
            refresh()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = refresh()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = refresh()
        override fun onVolumeChanged(volume: Float) = refresh()
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
        }
        override fun onTracksChanged(tracks: Tracks) {
            _state.update {
                it.copy(
                    tracksKnown = true,
                    hasVideo = tracks.containsType(C.TRACK_TYPE_VIDEO),
                    hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO),
                    audioSupported = !tracks.containsType(C.TRACK_TYPE_AUDIO) || tracks.isTypeSupported(C.TRACK_TYPE_AUDIO),
                    containerMimeType = tracks.groups.firstNotNullOfOrNull { group ->
                        (0 until group.length).firstNotNullOfOrNull { group.getTrackFormat(it).containerMimeType }
                    },
                )
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = describe(error)) }
            refresh()
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    /** Replaces the playlist and prepares playback of [startIndex] at [startPositionMs]. */
    fun setItems(items: List<PlayableItem>, startIndex: Int = 0, startPositionMs: Long = 0L, playWhenReady: Boolean = true) {
        val mediaItems = items.map { item ->
            MediaItem.Builder()
                .setUri(item.uri)
                .setMediaId(item.uri.toString())
                .apply { item.mimeType?.let { setMimeType(it) } }
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
                .build()
        }
        _state.update { it.copy(error = null, tracksKnown = false) }
        exoPlayer.setMediaItems(mediaItems, startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)), startPositionMs.coerceAtLeast(0L))
        exoPlayer.playWhenReady = playWhenReady
        exoPlayer.prepare()
        refresh()
    }

    fun play() {
        if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0L)
        if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
        exoPlayer.play()
    }

    fun pause() = exoPlayer.pause()

    fun togglePlayPause() = if (exoPlayer.isPlaying || exoPlayer.playWhenReady && exoPlayer.playbackState == Player.STATE_BUFFERING) pause() else play()

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
        refresh()
    }

    fun seekBy(deltaMs: Long) = seekTo(exoPlayer.currentPosition + deltaMs)

    fun next() {
        if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNextMediaItem()
    }

    fun previous() {
        // Within the first 3 s go to the previous item, otherwise restart the current one.
        if (exoPlayer.currentPosition > 3_000 || !exoPlayer.hasPreviousMediaItem()) exoPlayer.seekTo(0L) else exoPlayer.seekToPreviousMediaItem()
    }

    fun setSpeed(speed: Float) = exoPlayer.setPlaybackSpeed(speed.coerceIn(0.25f, 3f))

    /** Loops the current item (used by the synchronisation test). */
    fun setRepeat(enabled: Boolean) {
        exoPlayer.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setVolume(volume: Float) {
        exoPlayer.volume = volume.coerceIn(0f, 1f)
    }

    /** Re-reads positions (call periodically from the UI while visible). */
    fun refresh() {
        val p = exoPlayer
        val duration = p.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        _state.update {
            it.copy(
                isPlaying = p.isPlaying,
                playWhenReady = p.playWhenReady,
                playbackState = p.playbackState,
                positionMs = p.currentPosition.coerceAtLeast(0L),
                bufferedMs = p.bufferedPosition.coerceAtLeast(0L),
                durationMs = duration,
                currentIndex = p.currentMediaItemIndex,
                itemCount = p.mediaItemCount,
                title = p.mediaMetadata.title?.toString() ?: it.title,
                currentUri = p.currentMediaItem?.localConfiguration?.uri,
                speed = p.playbackParameters.speed,
                volume = p.volume,
                hasNext = p.hasNextMediaItem(),
                hasPrevious = p.hasPreviousMediaItem(),
                seekable = p.isCurrentMediaItemSeekable || p.playbackState == Player.STATE_IDLE,
            )
        }
    }

    fun release() {
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This file's format is not supported on this device."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "The file could not be opened. It may have been moved or deleted."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "The file is damaged or not a supported video."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "Audio output failed. Try again or restart playback."
        else -> error.localizedMessage ?: "Playback failed (${error.errorCodeName})."
    }

    companion object {
        const val SEEK_INCREMENT_MS = 10_000L
    }
}
