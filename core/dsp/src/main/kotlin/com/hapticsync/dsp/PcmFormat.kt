package com.hapticsync.dsp

/**
 * Sample encodings that the analysis pipeline can convert into its internal float representation.
 *
 * These mirror the linear PCM encodings that Android's media stack (Media3 `C.ENCODING_PCM_*`)
 * can deliver to an audio sink. All "little endian" encodings are the platform-native layout on
 * Android; big-endian variants appear for some WAV/AIFF/MP4 (`twos`, `in24`, `in32`) sources.
 */
enum class PcmEncoding(val bytesPerSample: Int, val bigEndian: Boolean) {
    /** Unsigned 8-bit samples (offset binary, 128 = silence). */
    PCM_8BIT(1, false),

    /** Signed 16-bit little-endian samples. The most common decoder output. */
    PCM_16BIT(2, false),

    /** Signed 16-bit big-endian samples. */
    PCM_16BIT_BIG_ENDIAN(2, true),

    /** Signed 24-bit little-endian samples, packed in 3 bytes. */
    PCM_24BIT(3, false),

    /** Signed 24-bit big-endian samples, packed in 3 bytes. */
    PCM_24BIT_BIG_ENDIAN(3, true),

    /** Signed 32-bit little-endian integer samples. */
    PCM_32BIT(4, false),

    /** Signed 32-bit big-endian integer samples. */
    PCM_32BIT_BIG_ENDIAN(4, true),

    /** 32-bit IEEE-754 float samples in little-endian (native) order, nominal range [-1, 1]. */
    PCM_FLOAT(4, false),
}

/**
 * Describes an interleaved linear PCM stream.
 *
 * @property sampleRate frames per second (Hz).
 * @property channelCount interleaved channels per frame (1..[MAX_CHANNELS]).
 * @property encoding sample encoding.
 */
data class PcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: PcmEncoding,
) {
    init {
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "Unsupported sample rate $sampleRate" }
        require(channelCount in 1..MAX_CHANNELS) { "Unsupported channel count $channelCount" }
    }

    /** Bytes occupied by one interleaved frame (one sample per channel). */
    val bytesPerFrame: Int get() = channelCount * encoding.bytesPerSample

    /** Converts a frame count to microseconds (truncating). */
    fun framesToUs(frames: Long): Long = TimeMath.framesToUs(frames, sampleRate)

    /** Converts microseconds to a frame count (truncating). */
    fun usToFrames(us: Long): Long = TimeMath.usToFrames(us, sampleRate)

    companion object {
        const val MIN_SAMPLE_RATE = 4_000
        const val MAX_SAMPLE_RATE = 768_000
        const val MAX_CHANNELS = 8
    }
}

/** Exact integer conversions between sample frames and microseconds. */
object TimeMath {
    const val US_PER_SECOND = 1_000_000L

    /** Frames → µs. Uses 64-bit math so that hours of audio at 768 kHz cannot overflow. */
    fun framesToUs(frames: Long, sampleRate: Int): Long = frames * US_PER_SECOND / sampleRate

    /** µs → frames. */
    fun usToFrames(us: Long, sampleRate: Int): Long = us * sampleRate / US_PER_SECOND

    /** Duration of [frames] frames in seconds. */
    fun framesToSeconds(frames: Int, sampleRate: Int): Double = frames.toDouble() / sampleRate
}
