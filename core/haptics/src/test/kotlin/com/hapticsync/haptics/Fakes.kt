package com.hapticsync.haptics

import com.hapticsync.analysis.AnalysisFrame
import com.hapticsync.analysis.SoundEvent
import com.hapticsync.model.BandId
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory

/** Media clock driven by the test. */
class FakeClock : MediaClock {
    var positionUs = 0L
    var anchorNanos = 0L
    override var isPlaying = true
    override var speed = 1f
    override var isValid = true

    override fun mediaTimeUs(systemNanoTime: Long): Long =
        if (isPlaying) positionUs + ((systemNanoTime - anchorNanos) / 1000L * speed).toLong() else positionUs
}

/** Records everything sent to the "vibrator". */
class RecordingOutput(override val capabilities: DeviceCapabilities = DeviceCapabilities.AMPLITUDE_ONLY) : HapticOutput {
    class Played(val effect: RenderedEffect, val atNanos: Long)

    val played = mutableListOf<Played>()
    var cancels = 0
    var nowNanos = 0L

    override fun play(effect: RenderedEffect): Boolean {
        played += Played(effect, nowNanos)
        return true
    }

    override fun cancel() {
        cancels++
    }
}

fun event(
    category: SoundCategory,
    timeUs: Long = 0L,
    strength: Float = 0.9f,
    shares: FloatArray = floatArrayOf(0.3f, 0.4f, 0.15f, 0.1f, 0.04f, 0.01f),
    durationHintMs: Float = 600f,
    latenessToleranceUs: Long = 0L,
) = SoundEvent(
    category = category,
    timeUs = timeUs,
    strength = strength,
    confidence = 0.9f,
    sharpness = 0.5f,
    attackMs = 5f,
    decayDbPerSecond = 80f,
    durationHintMs = durationHintMs,
    dominantBand = BandId.entries[shares.indices.maxBy { shares[it] }],
    bandShares = shares,
    latenessToleranceUs = latenessToleranceUs,
)

val LOW_SHARES = floatArrayOf(0.45f, 0.45f, 0.06f, 0.03f, 0.01f, 0f)
val HIGH_SHARES = floatArrayOf(0f, 0.01f, 0.04f, 0.1f, 0.35f, 0.5f)
val SUB_SHARES = floatArrayOf(0.7f, 0.2f, 0.06f, 0.03f, 0.01f, 0f)

fun config(
    profile: HapticProfile = HapticProfile(),
    calibration: CalibrationProfile = CalibrationProfile(amplitudeGamma = 1f, minPerceptibleAmplitude = 0f, actuatorLatencyMs = 0f),
) = HapticConfig(profile, calibration)

fun frameWith(category: SoundCategory, level: Float, rpm: Float = 0.5f, speech: Float = 0f): AnalysisFrame {
    val f = AnalysisFrame()
    f.continuous[category.ordinal] = level
    f.rpmNormalized = rpm
    f.speechLikelihood = speech
    for (b in 0 until BandId.COUNT) f.bandShare[b] = 1f / BandId.COUNT
    return f
}
