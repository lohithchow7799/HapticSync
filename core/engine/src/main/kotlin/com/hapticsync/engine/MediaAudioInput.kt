package com.hapticsync.engine

import com.hapticsync.dsp.PcmFormat
import java.nio.ByteBuffer

/**
 * Contract between a media player's audio pipeline and the haptic engine.
 *
 * The player integration (see `:platform:playback`) calls these methods from the player's
 * internal playback thread, in the order in which the player's audio sink observes the events.
 * Implementations must therefore be fast, non-blocking and allocation-free in the steady state:
 * any stall here directly delays audio output and can cause playback to stutter.
 *
 * Time base: all timestamps are in the audio sink's time base ("renderer time"). Presentation
 * timestamps passed to [onPcm] and positions passed to [onPosition] share that base, so they can
 * be compared directly without knowing the player's internal renderer offsets.
 */
interface MediaAudioInput {
    /**
     * The decoded audio format changed. [format] is `null` when the stream cannot be analysed
     * (for example encoded passthrough or an unknown PCM encoding); [unsupportedReason] then holds
     * a human-readable explanation.
     */
    fun onFormat(format: PcmFormat?, unsupportedReason: String?)

    /**
     * Interleaved PCM in the most recently announced [PcmFormat] was accepted by the sink.
     *
     * The bytes `[startByte, endByte)` of [buffer] (absolute indices) begin at media time
     * [presentationTimeUs]. Implementations must read the bytes using absolute accessors and must
     * not modify the buffer's position, limit or byte order, and must not retain the buffer.
     */
    fun onPcm(buffer: ByteBuffer, startByte: Int, endByte: Int, presentationTimeUs: Long)

    /** The sink was flushed (seek, track switch or other discontinuity). Earlier audio is void. */
    fun onDiscontinuity()

    /** Audio output started (`true`) or stopped (`false`: pause, buffering, end of stream). */
    fun onPlaying(playing: Boolean)

    /**
     * The sink reported its current output position (media time of the audio currently being
     * heard). [systemNanoTime] is `System.nanoTime()` at the moment of the measurement.
     */
    fun onPosition(positionUs: Long, systemNanoTime: Long)

    /** The playback speed applied by the sink changed. */
    fun onSpeed(speed: Float)

    /** The sink was reset or released. All state derived from the current stream is void. */
    fun onReset()
}
