package com.hapticsync.haptics

/**
 * A device-ready vibration command. Each variant maps one-to-one onto an Android
 * `VibrationEffect` factory; the Android backend only translates, it makes no decisions.
 * All durations are wall-clock milliseconds.
 */
sealed interface RenderedEffect {
    /** Total duration in milliseconds. */
    val durationMs: Long

    /** Peak normalised amplitude in [0, 1] (for visualisation and statistics). */
    val peakAmplitude: Float

    /**
     * `VibrationEffect.createWaveform(timings, amplitudes, -1)`: segment `i` lasts
     * [timingsMs]`[i]` ms at [amplitudes]`[i]` (0–255, 0 = off). Requires amplitude control.
     */
    class Waveform(val timingsMs: LongArray, val amplitudes: IntArray) : RenderedEffect {
        init { require(timingsMs.size == amplitudes.size && timingsMs.isNotEmpty()) }
        override val durationMs: Long = timingsMs.sum()
        override val peakAmplitude: Float = (amplitudes.maxOrNull() ?: 0) / 255f
    }

    /**
     * `VibrationEffect.createWaveform(timings, -1)`: alternating off/on durations, starting
     * with an *off* delay. Works on motors without amplitude control.
     */
    class OnOff(val timingsMs: LongArray) : RenderedEffect {
        init { require(timingsMs.isNotEmpty()) }
        override val durationMs: Long = timingsMs.sum()
        override val peakAmplitude: Float = if (timingsMs.size > 1) 1f else 0f

        /** Fraction of time the motor is on. */
        val dutyCycle: Float get() {
            var on = 0L
            for (i in 1 until timingsMs.size step 2) on += timingsMs[i]
            return if (durationMs > 0) on.toFloat() / durationMs else 0f
        }
    }

    /** `VibrationEffect.Composition` of primitives with relative scales and delays. */
    class Primitives(val steps: List<PrimitiveStep>, estimatedDurationMs: Long) : RenderedEffect {
        init { require(steps.isNotEmpty()) }
        override val durationMs: Long = estimatedDurationMs
        override val peakAmplitude: Float = steps.maxOf { it.scale }
    }

    /** `VibrationEffect.createPredefined(effect)`. */
    class Predefined(val effect: PredefinedEffect, override val durationMs: Long, override val peakAmplitude: Float) : RenderedEffect

    /**
     * Android 16 `VibrationEffect.WaveformEnvelopeBuilder`: the vibration ramps from zero at
     * [initialFrequencyHz] through the control points; point `i` is reached [durationsMs]`[i]`
     * ms after the previous one.
     */
    class Envelope(
        val initialFrequencyHz: Float,
        val amplitudes: FloatArray,
        val frequenciesHz: FloatArray,
        val durationsMs: LongArray,
    ) : RenderedEffect {
        init { require(amplitudes.size == frequenciesHz.size && amplitudes.size == durationsMs.size && amplitudes.isNotEmpty()) }
        override val durationMs: Long = durationsMs.sum()
        override val peakAmplitude: Float = amplitudes.maxOrNull() ?: 0f
    }
}

/** One primitive in a composition: [scale] in [0, 1], [delayMs] before it starts. */
data class PrimitiveStep(val primitive: HapticPrimitive, val scale: Float, val delayMs: Int = 0)
