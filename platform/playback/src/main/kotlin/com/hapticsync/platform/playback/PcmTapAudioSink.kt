package com.hapticsync.platform.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.hapticsync.dsp.PcmEncoding
import com.hapticsync.dsp.PcmFormat
import com.hapticsync.engine.MediaAudioInput
import java.nio.ByteBuffer

/**
 * An [AudioSink] decorator that taps the decoded PCM of the playing media and forwards it, with
 * exact presentation timestamps, to the haptic engine.
 *
 * Why tap here (instead of an `AudioProcessor`, the microphone or playback capture):
 * - [handleBuffer] receives every decoded buffer together with its presentation timestamp, so
 *   each analysed sample can be mapped to the media timeline exactly.
 * - The data is taken *before* the sink's speed-changing processor, so analysis always runs on
 *   the original media timeline regardless of playback speed.
 * - The sink buffers roughly 250 ms or more of audio ahead of the output, so the analysis sees
 *   audio before it is heard. That lookahead is what allows haptics to be scheduled on time
 *   rather than chasing the sound.
 * - [getCurrentPositionUs] exposes the sink's output clock (corrected for the device's output
 *   latency by Media3), which the scheduler uses as its media clock.
 *
 * The audio itself passes through unmodified: analysis never alters what the user hears.
 *
 * Threading: every method is invoked on ExoPlayer's playback thread. The [MediaAudioInput]
 * implementation must not block.
 */
@OptIn(UnstableApi::class)
class PcmTapAudioSink(
    delegate: AudioSink,
    private val input: MediaAudioInput,
) : ForwardingAudioSink(delegate) {

    private var format: PcmFormat? = null
    private var bytesPerFrame = 0

    // Tracks the buffer currently being consumed. The renderer re-offers a buffer until the sink
    // has consumed it completely, so a single buffer may arrive over several calls.
    private var trackedBuffer: ByteBuffer? = null
    private var trackedPresentationTimeUs = Long.MIN_VALUE
    private var trackedStartPosition = 0
    private var trackedTrimFrames = 0

    // Encoder delay (priming samples) that the sink trims after a (re)configuration. Those frames
    // are never played, so they must not shift the timestamps of the following audio.
    private var pendingStartTrimFrames = 0

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        val pcmFormat = toPcmFormat(inputFormat)
        format = pcmFormat
        bytesPerFrame = pcmFormat?.bytesPerFrame ?: 0
        trackedBuffer = null
        // Media3 trims encoder delay for 16-bit PCM only (TrimmingAudioProcessor).
        pendingStartTrimFrames =
            if (pcmFormat?.encoding == PcmEncoding.PCM_16BIT) inputFormat.encoderDelay.coerceAtLeast(0) else 0
        input.onFormat(pcmFormat, if (pcmFormat == null) describeUnsupported(inputFormat) else null)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val startPosition = buffer.position()
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val currentFormat = format
        val frameBytes = bytesPerFrame
        val endPosition = buffer.position()
        if (currentFormat == null || frameBytes == 0 || endPosition <= startPosition) return handled

        if (buffer !== trackedBuffer || presentationTimeUs != trackedPresentationTimeUs) {
            trackedBuffer = buffer
            trackedPresentationTimeUs = presentationTimeUs
            trackedStartPosition = startPosition
            val bufferFrames = (buffer.limit() - startPosition) / frameBytes
            trackedTrimFrames = minOf(pendingStartTrimFrames, bufferFrames)
            pendingStartTrimFrames -= trackedTrimFrames
        }

        val firstFrame = (startPosition - trackedStartPosition) / frameBytes
        val endFrame = (endPosition - trackedStartPosition) / frameBytes
        val firstPlayedFrame = maxOf(firstFrame, trackedTrimFrames)
        if (endFrame > firstPlayedFrame) {
            val ptsUs = presentationTimeUs +
                (firstPlayedFrame - trackedTrimFrames).toLong() * 1_000_000L / currentFormat.sampleRate
            input.onPcm(
                buffer,
                trackedStartPosition + firstPlayedFrame * frameBytes,
                trackedStartPosition + endFrame * frameBytes,
                ptsUs,
            )
        }
        if (handled) trackedBuffer = null
        return handled
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val positionUs = super.getCurrentPositionUs(sourceEnded)
        if (positionUs != AudioSink.CURRENT_POSITION_NOT_SET) {
            input.onPosition(positionUs, System.nanoTime())
        }
        return positionUs
    }

    override fun play() {
        super.play()
        input.onPlaying(true)
    }

    override fun pause() {
        super.pause()
        input.onPlaying(false)
    }

    override fun flush() {
        super.flush()
        trackedBuffer = null
        pendingStartTrimFrames = 0
        input.onDiscontinuity()
    }

    override fun reset() {
        super.reset()
        format = null
        bytesPerFrame = 0
        trackedBuffer = null
        pendingStartTrimFrames = 0
        input.onReset()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        super.setPlaybackParameters(playbackParameters)
        input.onSpeed(playbackParameters.speed)
    }

    private fun toPcmFormat(inputFormat: Format): PcmFormat? {
        if (inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW) return null
        val encoding = when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> PcmEncoding.PCM_8BIT
            C.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16BIT
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> PcmEncoding.PCM_16BIT_BIG_ENDIAN
            C.ENCODING_PCM_24BIT -> PcmEncoding.PCM_24BIT
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> PcmEncoding.PCM_24BIT_BIG_ENDIAN
            C.ENCODING_PCM_32BIT -> PcmEncoding.PCM_32BIT
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> PcmEncoding.PCM_32BIT_BIG_ENDIAN
            C.ENCODING_PCM_FLOAT -> PcmEncoding.PCM_FLOAT
            else -> return null
        }
        val sampleRate = inputFormat.sampleRate
        val channels = inputFormat.channelCount
        if (sampleRate !in PcmFormat.MIN_SAMPLE_RATE..PcmFormat.MAX_SAMPLE_RATE) return null
        if (channels !in 1..PcmFormat.MAX_CHANNELS) return null
        return PcmFormat(sampleRate, channels, encoding)
    }

    private fun describeUnsupported(inputFormat: Format): String {
        val mime = inputFormat.sampleMimeType
        return when {
            mime != null && mime != MimeTypes.AUDIO_RAW ->
                "The audio is sent to the output as encoded $mime (passthrough/offload) and cannot be analysed."
            inputFormat.channelCount > PcmFormat.MAX_CHANNELS ->
                "Audio with ${inputFormat.channelCount} channels is not supported for analysis."
            else -> "The decoded PCM encoding (${inputFormat.pcmEncoding}) is not supported for analysis."
        }
    }
}
