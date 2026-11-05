package com.hapticsync.platform.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import com.hapticsync.engine.MediaAudioInput

/**
 * A [DefaultRenderersFactory] whose audio renderer writes into a [PcmTapAudioSink], so that the
 * haptic engine receives the decoded audio of exactly the media item being played.
 */
@OptIn(UnstableApi::class)
class HapticRenderersFactory(
    context: Context,
    private val audioInput: MediaAudioInput,
) : DefaultRenderersFactory(context) {

    init {
        // Prefer platform decoders, fall back to others if one fails to initialise.
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val delegate = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            ?: return null
        return PcmTapAudioSink(delegate, audioInput)
    }
}
