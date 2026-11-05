package com.hapticsync.model

import kotlinx.serialization.Serializable

/**
 * Application-wide settings.
 *
 * @property hapticsEnabled master haptic switch. When off, analysis and visualisation keep
 *   running but the vibrator is never driven.
 * @property activePresetId preset whose profile is currently applied.
 * @property showVisualization show the live audio visualisation during playback.
 * @property keepScreenOn keep the display awake while a video is playing.
 * @property autoHideControlsMs delay before player controls hide during playback.
 */
@Serializable
data class AppSettings(
    val hapticsEnabled: Boolean = true,
    val activePresetId: String = BuiltInPresets.CINEMATIC_ID,
    val showVisualization: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoHideControlsMs: Long = 3500L,
)

/** An entry in the "Recently played" list. */
@Serializable
data class RecentMedia(
    val uri: String,
    val title: String,
    val durationMs: Long = 0L,
    val lastPositionMs: Long = 0L,
    val lastPlayedAtMs: Long = 0L,
    val audioOnly: Boolean = false,
    val mimeType: String? = null,
) {
    /** Playback progress in [0, 1], or 0 when the duration is unknown. */
    val progress: Float get() = if (durationMs > 0) (lastPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        const val MAX_ENTRIES = 30
    }
}
