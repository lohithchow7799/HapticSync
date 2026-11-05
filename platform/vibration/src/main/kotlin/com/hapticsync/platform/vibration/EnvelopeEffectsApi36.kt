package com.hapticsync.platform.vibration

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.RequiresApi

/**
 * Android 16 (API 36) envelope effects: the first public API that lets apps shape *both*
 * amplitude and vibration frequency over time.
 *
 * All API-36 references are isolated in this object so that the rest of the module compiles and
 * runs unchanged on API 29–35, where frequency control is not available to apps.
 */
@RequiresApi(36)
internal object EnvelopeEffectsApi36 {

    /** Limits the device imposes on envelope effects. */
    data class Limits(
        val maxControlPoints: Int,
        val minControlPointDurationMs: Long,
        val maxControlPointDurationMs: Long,
        val maxDurationMs: Long,
    )

    fun isSupported(vibrator: Vibrator): Boolean = vibrator.areEnvelopeEffectsSupported()

    fun limits(vibrator: Vibrator): Limits {
        val info = vibrator.envelopeEffectInfo
        return Limits(
            maxControlPoints = info.maxSize,
            minControlPointDurationMs = info.minControlPointDurationMillis,
            maxControlPointDurationMs = info.maxControlPointDurationMillis,
            maxDurationMs = info.maxDurationMillis,
        )
    }

    /**
     * The frequency range (Hz) the actuator can render, or `null` if the device does not expose a
     * frequency profile.
     */
    fun frequencyRangeHz(vibrator: Vibrator): ClosedFloatingPointRange<Float>? {
        val profile = vibrator.frequencyProfile ?: return null
        val min = profile.minFrequencyHz
        val max = profile.maxFrequencyHz
        if (!min.isFinite() || !max.isFinite() || max <= min) return null
        return min..max
    }

    /** Output acceleration (g) that the actuator produces at [frequencyHz] at full amplitude. */
    fun outputAccelerationGs(vibrator: Vibrator, frequencyHz: Float): Float {
        val profile = vibrator.frequencyProfile ?: return Float.NaN
        return profile.getOutputAccelerationGs(frequencyHz)
    }

    /**
     * Builds an amplitude/frequency envelope. Arrays are parallel; point `i` is reached
     * [durationsMs]`[i]` milliseconds after point `i - 1` (or after the start).
     */
    fun waveformEnvelope(
        initialFrequencyHz: Float,
        amplitudes: FloatArray,
        frequenciesHz: FloatArray,
        durationsMs: LongArray,
        count: Int,
    ): VibrationEffect {
        val builder = VibrationEffect.WaveformEnvelopeBuilder()
        builder.setInitialFrequencyHz(initialFrequencyHz)
        for (i in 0 until count) {
            builder.addControlPoint(amplitudes[i], frequenciesHz[i], durationsMs[i])
        }
        return builder.build()
    }

    /**
     * Builds a device-independent envelope from perceptual intensity and sharpness values in
     * [0, 1]; the framework maps them to the actuator's capabilities.
     */
    fun basicEnvelope(
        initialSharpness: Float,
        intensities: FloatArray,
        sharpnesses: FloatArray,
        durationsMs: LongArray,
        count: Int,
    ): VibrationEffect {
        val builder = VibrationEffect.BasicEnvelopeBuilder()
        builder.setInitialSharpness(initialSharpness)
        for (i in 0 until count) {
            builder.addControlPoint(intensities[i], sharpnesses[i], durationsMs[i])
        }
        return builder.build()
    }
}
