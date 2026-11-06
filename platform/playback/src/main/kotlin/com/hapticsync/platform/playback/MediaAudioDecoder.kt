package com.hapticsync.platform.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.MediaExtractorCompat
import com.hapticsync.dsp.PcmEncoding
import com.hapticsync.dsp.PcmFormat
import java.io.IOException
import java.nio.ByteBuffer

/** Why audio could not be decoded ahead of playback. */
class AudioDecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Decodes the audio track of a media item ahead of playback, for preparing haptics.
 *
 * Uses Media3's own extractors ([MediaExtractorCompat]), so sample timestamps are exactly the
 * ones ExoPlayer plays, and the platform decoder. PCM is delivered on the media timeline with
 * the encoder delay (priming samples) removed: the decoder is configured not to trim, and the
 * delay is subtracted here, which is the time base of a prepared haptic track.
 */
@OptIn(UnstableApi::class)
class MediaAudioDecoder(private val context: Context) {

    /** Outcome of a [decode] call. */
    class Result(
        /** The end of the audio was reached. */
        val endOfStream: Boolean,
        /** Duration of the audio track, or 0 when unknown. */
        val durationUs: Long,
    )

    /**
     * Decodes from [startUs] (media time) and passes interleaved PCM to [onPcm] as
     * `(buffer, startByte, endByte, mediaTimeUs)`, after announcing the format with [onFormat].
     * Stops when [onPcm] returns false, when [shouldStop] returns true, or at the end.
     * Runs on the calling thread; call from a background thread.
     */
    fun decode(
        uri: Uri,
        startUs: Long,
        shouldStop: () -> Boolean,
        onFormat: (PcmFormat) -> Unit,
        onPcm: (ByteBuffer, Int, Int, Long) -> Boolean,
    ): Result {
        // The player's own extractors (incl. seek indexing), so timestamps match exactly.
        val extractor = MediaExtractorCompat(SeekIndexingExtractorsFactory(context), DefaultDataSource.Factory(context))
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(uri, 0L)
            } catch (e: IOException) {
                throw AudioDecodeException("The file could not be read.", e)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("The file has no audio track.")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw AudioDecodeException("Unknown audio format.")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val delayFrames = format.getInteger(KEY_ENCODER_DELAY, 0).coerceAtLeast(0)
            // The delay is removed here, exactly like the player's sink does, not by the decoder.
            format.removeKey(KEY_ENCODER_DELAY)
            format.removeKey(KEY_ENCODER_PADDING)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)

            if (mime == MimeTypes.AUDIO_RAW) {
                return decodeRaw(extractor, format, durationUs, delayFrames, shouldStop, onFormat, onPcm)
            }

            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            codec = try {
                if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw AudioDecodeException("No decoder for $mime on this device.", e)
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                throw AudioDecodeException("The $mime decoder could not be started.", e)
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var pcmFormat: PcmFormat? = null
            var delayUs = 0L
            while (!shouldStop()) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex) ?: throw AudioDecodeException("Decoder input unavailable.")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = pcmFormatOf(codec.outputFormat) ?: throw AudioDecodeException("Unsupported decoded audio format.")
                        pcmFormat = f
                        delayUs = delayFrames * 1_000_000L / f.sampleRate
                        onFormat(f)
                    }
                    outIndex >= 0 -> {
                        val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val f = pcmFormat ?: pcmFormatOf(codec.outputFormat)?.also {
                            pcmFormat = it
                            delayUs = delayFrames * 1_000_000L / it.sampleRate
                            onFormat(it)
                        }
                        var keepGoing = true
                        if (f != null && info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null) keepGoing = deliver(buffer, info.offset, info.offset + info.size, info.presentationTimeUs - delayUs, f, onPcm)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (endOfStream) return Result(endOfStream = true, durationUs = durationUs)
                        if (!keepGoing) return Result(endOfStream = false, durationUs = durationUs)
                    }
                }
            }
            return Result(endOfStream = false, durationUs = durationUs)
        } finally {
            codec?.let { c ->
                runCatching { c.stop() }
                runCatching { c.release() }
            }
            runCatching { extractor.release() }
        }
    }

    /** Raw PCM (WAV and similar): samples come straight from the extractor. */
    private fun decodeRaw(
        extractor: MediaExtractorCompat,
        format: MediaFormat,
        durationUs: Long,
        delayFrames: Int,
        shouldStop: () -> Boolean,
        onFormat: (PcmFormat) -> Unit,
        onPcm: (ByteBuffer, Int, Int, Long) -> Boolean,
    ): Result {
        val f = pcmFormatOf(format) ?: throw AudioDecodeException("Unsupported PCM format.")
        onFormat(f)
        val delayUs = delayFrames * 1_000_000L / f.sampleRate
        var buffer = ByteBuffer.allocateDirect(RAW_BUFFER_BYTES)
        while (!shouldStop()) {
            val needed = extractor.sampleSize
            if (needed < 0) return Result(endOfStream = true, durationUs = durationUs)
            if (needed > buffer.capacity()) buffer = ByteBuffer.allocateDirect(needed.toInt())
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return Result(endOfStream = true, durationUs = durationUs)
            val time = extractor.sampleTime - delayUs
            extractor.advance()
            if (!deliver(buffer, 0, size, time, f, onPcm)) return Result(endOfStream = false, durationUs = durationUs)
        }
        return Result(endOfStream = false, durationUs = durationUs)
    }

    /** Drops frames before media time zero (the encoder delay at the start) and delivers the rest. */
    private fun deliver(
        buffer: ByteBuffer,
        start: Int,
        end: Int,
        mediaTimeUs: Long,
        format: PcmFormat,
        onPcm: (ByteBuffer, Int, Int, Long) -> Boolean,
    ): Boolean {
        var from = start
        var time = mediaTimeUs
        if (time < 0) {
            val skipFrames = ((-time) * format.sampleRate + 999_999L) / 1_000_000L
            from = (start + skipFrames * format.bytesPerFrame).coerceAtMost(end.toLong()).toInt()
            time += skipFrames * 1_000_000L / format.sampleRate
        }
        if (from >= end) return true
        return onPcm(buffer, from, end, time.coerceAtLeast(0L))
    }

    private fun pcmFormatOf(format: MediaFormat): PcmFormat? {
        val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else return null
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else return null
        val encoding = when (format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)) {
            AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16BIT
            AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.PCM_FLOAT
            AudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.PCM_8BIT
            else -> return null // decoders output 16-bit unless asked otherwise
        }
        if (rate !in PcmFormat.MIN_SAMPLE_RATE..PcmFormat.MAX_SAMPLE_RATE || channels !in 1..PcmFormat.MAX_CHANNELS) return null
        return PcmFormat(rate, channels, encoding)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val RAW_BUFFER_BYTES = 64 * 1024

        // MediaFormat.KEY_ENCODER_DELAY / KEY_ENCODER_PADDING (string values; the constants are API 30).
        const val KEY_ENCODER_DELAY = "encoder-delay"
        const val KEY_ENCODER_PADDING = "encoder-padding"
    }
}
