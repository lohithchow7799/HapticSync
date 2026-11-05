package com.hapticsync.engine

import com.hapticsync.dsp.PcmEncoding
import com.hapticsync.dsp.PcmFormat
import com.hapticsync.haptics.DeviceCapabilities
import com.hapticsync.haptics.HapticOutput
import com.hapticsync.haptics.RenderedEffect
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Records what the engine sends to the vibrator, with the media time at which it was sent. */
class RecordingOutput(override val capabilities: DeviceCapabilities = DeviceCapabilities.AMPLITUDE_ONLY) : HapticOutput {
    class Played(val effect: RenderedEffect, val mediaTimeUs: Long, val playing: Boolean)

    val played = mutableListOf<Played>()
    var cancels = 0
    var mediaTimeProvider: () -> Long = { 0L }
    var playingProvider: () -> Boolean = { true }

    override fun play(effect: RenderedEffect): Boolean {
        played += Played(effect, mediaTimeProvider(), playingProvider())
        return true
    }

    override fun cancel() {
        cancels++
    }

    /** Transient-like commands (not continuous segments): heuristically, short or primitive effects. */
    fun transients(fromIndex: Int = 0): List<Played> = played.drop(fromIndex).filter {
        it.effect is RenderedEffect.Primitives || it.effect is RenderedEffect.Predefined ||
            (it.effect is RenderedEffect.Waveform && (it.effect as RenderedEffect.Waveform).timingsMs.size > 1 && it.effect.durationMs < 140)
    }
}

/**
 * Emulates the player side of the integration, the way Media3's audio sink drives the engine:
 * PCM is delivered up to [bufferAheadUs] ahead of the playback position (the AudioTrack buffer),
 * the output position is reported every [positionIntervalUs], and pause/seek/speed/format
 * changes produce the same callbacks the real sink produces.
 */
class SimulatedPlayer(
    private val interleaved: FloatArray,
    private val sampleRate: Int,
    private val channels: Int,
    profile: HapticProfile = HapticProfile(),
    calibration: CalibrationProfile = CalibrationProfile(actuatorLatencyMs = 0f),
    capabilities: DeviceCapabilities = DeviceCapabilities.AMPLITUDE_ONLY,
    private val encoding: PcmEncoding = PcmEncoding.PCM_16BIT,
    private val bufferAheadUs: Long = 250_000L,
    private val positionIntervalUs: Long = 10_000L,
) {
    var nanos = 1_000_000_000L
        private set
    val output = RecordingOutput(capabilities)
    val engine = HapticSyncEngine(output, profile, calibration, nanoTime = { nanos })

    /** Media position currently being heard. */
    var positionUs = 0L
        private set
    private var writtenFrame = 0L
    private var writtenUs = 0L
    var playing = false
        private set
    var speed = 1f
        private set
    private var lastPositionReport = Long.MIN_VALUE
    private var nextTick = 0L

    init {
        output.mediaTimeProvider = { engine.clock.mediaTimeUs(nanos) }
        output.playingProvider = { playing }
        engine.onFormat(PcmFormat(sampleRate, channels, encoding), null)
    }

    val totalFrames: Long get() = (interleaved.size / channels).toLong()
    val durationUs: Long get() = totalFrames * 1_000_000L / sampleRate

    fun play() {
        playing = true
        engine.onPlaying(true)
    }

    fun pause() {
        playing = false
        engine.onPlaying(false)
    }

    fun setSpeed(newSpeed: Float) {
        speed = newSpeed
        engine.onSpeed(newSpeed)
    }

    /** Seek: flush the sink and continue from [toUs]. */
    fun seek(toUs: Long) {
        engine.onDiscontinuity()
        positionUs = toUs
        writtenFrame = toUs * sampleRate / 1_000_000L
        writtenUs = toUs
        lastPositionReport = Long.MIN_VALUE
        feed()
        reportPosition()
    }

    /** Advances simulated time by [ms] milliseconds in 0.5 ms steps. */
    fun advance(ms: Int) {
        val steps = ms * 2
        for (i in 0 until steps) {
            nanos += 500_000L
            if (playing) {
                positionUs += (500 * speed).roundToInt()
                if (positionUs >= durationUs) {
                    positionUs = durationUs
                    playing = false
                    engine.onPlaying(false) // end of stream
                }
            }
            feed()
            if (positionUs - lastPositionReport >= positionIntervalUs || lastPositionReport == Long.MIN_VALUE) reportPosition()
            engine.processPending()
            if (nanos >= nextTick) nextTick = nanos + engine.tickScheduler(nanos)
        }
    }

    private fun reportPosition() {
        lastPositionReport = positionUs
        engine.onPosition(positionUs, nanos)
    }

    private fun feed() {
        while (writtenFrame < totalFrames && writtenUs < positionUs + (bufferAheadUs * speed).toLong()) {
            val frames = minOf(CHUNK_FRAMES.toLong(), totalFrames - writtenFrame).toInt()
            val buffer = encode(writtenFrame.toInt(), frames)
            engine.onPcm(buffer, 0, buffer.limit(), writtenUs)
            writtenFrame += frames
            writtenUs = writtenFrame * 1_000_000L / sampleRate
        }
    }

    private fun encode(startFrame: Int, frames: Int): ByteBuffer {
        val bytesPerSample = encoding.bytesPerSample
        val buffer = ByteBuffer.allocate(frames * channels * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames * channels) {
            val s = interleaved[startFrame * channels + i].coerceIn(-1f, 1f)
            when (encoding) {
                PcmEncoding.PCM_FLOAT -> buffer.putFloat(s)
                else -> buffer.putShort((s * 32767f).roundToInt().toShort())
            }
        }
        buffer.flip()
        return buffer
    }

    companion object {
        const val CHUNK_FRAMES = 1024
    }
}
