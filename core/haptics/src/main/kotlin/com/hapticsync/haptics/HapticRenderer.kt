package com.hapticsync.haptics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Renders device-independent haptics into [RenderedEffect]s the device can play, choosing per
 * device and per effect:
 *
 * 1. **Envelope** (Android 16, frequency control available and preferred): amplitude and
 *    vibration frequency both follow the curve; sharpness selects the frequency (deep → low,
 *    crisp → high within the actuator's usable range).
 * 2. **Primitives** (composition supported and preferred): crisp short transients become a single
 *    primitive (click, thud, tick…), which actuators render most precisely.
 * 3. **Amplitude waveform** (amplitude control): the calibrated curve as timed amplitude steps.
 * 4. **Predefined effect** (no amplitude control, short crisp transients): click/heavy click/tick.
 * 5. **On/off** (no amplitude control): the amplitude is encoded as the duty cycle of a 20 ms
 *    pulse train — the motor can only be on or off, so strength becomes "how much of the time".
 *
 * Audio frequencies are never reproduced literally: a phone actuator cannot do that. Only
 * the perceptual dimensions — strength, timing, envelope and (where available) roughness via
 * vibration frequency — are carried over.
 */
class HapticRenderer(capabilities: DeviceCapabilities) {

    @Volatile
    var capabilities: DeviceCapabilities = capabilities

    /**
     * Renders a transient. [skipMs] media milliseconds are dropped from the start (the effect is
     * late); [speed] is the playback speed.
     */
    fun renderTransient(effect: TransientEffect, config: HapticConfig, speed: Float, skipMs: Float = 0f): RenderedEffect? {
        val caps = capabilities
        if (!caps.hasVibrator) return null
        val curve = effect.curve.skip(skipMs)
        if (curve.amplitudes.isEmpty() || curve.peak <= 0f) return null
        val timeScale = timeScale(config, speed)
        val primitive = effect.primitive
        val short = curve.durationMs * timeScale <= PRIMITIVE_MAX_MS && skipMs <= 0f

        if (caps.supportsFrequencyControl && config.preferEnvelopes) {
            return envelope(curve.amplitudes, FloatArray(curve.amplitudes.size) { curve.sharpness }, curve.amplitudes.size, curve.stepMs * timeScale, config)
        }
        if (caps.supportsComposition && config.preferPrimitives && short && primitive != null && primitive in caps.supportedPrimitives) {
            val scale = config.calibrate(effect.primitiveScale)
            if (scale <= 0f) return null
            val duration = caps.primitiveDurationsMs[primitive]?.toLong() ?: max(1L, (curve.durationMs * timeScale).roundToLong())
            return RenderedEffect.Primitives(listOf(PrimitiveStep(primitive, scale)), duration)
        }
        if (caps.hasAmplitudeControl) return waveform(curve.amplitudes, curve.amplitudes.size, curve.stepMs * timeScale, config)
        if (caps.supportsPredefinedEffects && short && primitive != null) {
            val predefined = when (primitive) {
                HapticPrimitive.THUD, HapticPrimitive.SPIN -> PredefinedEffect.HEAVY_CLICK
                HapticPrimitive.TICK, HapticPrimitive.LOW_TICK -> PredefinedEffect.TICK
                else -> PredefinedEffect.CLICK
            }
            val amplitude = config.calibrate(curve.peak)
            if (amplitude <= 0f) return null
            return RenderedEffect.Predefined(predefined, max(1L, (curve.durationMs * timeScale).roundToLong()), amplitude)
        }
        return onOff(curve.amplitudes, curve.amplitudes.size, curve.stepMs * timeScale, config)
    }

    /**
     * Renders [count] continuous-plan samples, each [mediaStepMs] media milliseconds long
     * (the first lasting [firstStepMediaMs]).
     */
    fun renderContinuous(
        amplitudes: FloatArray,
        sharpness: FloatArray,
        count: Int,
        mediaStepMs: Float,
        firstStepMediaMs: Float,
        config: HapticConfig,
        speed: Float,
    ): RenderedEffect? {
        val caps = capabilities
        if (!caps.hasVibrator || count <= 0) return null
        var peak = 0f
        for (i in 0 until count) peak = max(peak, amplitudes[i])
        if (config.calibrate(peak) <= 0f) return null
        val timeScale = timeScale(config, speed)
        // Expand the (possibly shortened) first step so the waveform starts exactly "now".
        val steps = expandFirstStep(amplitudes, sharpness, count, mediaStepMs, firstStepMediaMs)
        val stepMs = CONTINUOUS_STEP_MS * timeScale
        return when {
            caps.supportsFrequencyControl && config.preferEnvelopes -> envelope(steps.first, steps.second, steps.first.size, stepMs, config)
            caps.hasAmplitudeControl -> waveform(steps.first, steps.first.size, stepMs, config)
            else -> onOff(steps.first, steps.first.size, stepMs, config)
        }
    }

    private fun timeScale(config: HapticConfig, speed: Float): Float =
        if (config.scaleWithSpeed && speed > 0f) 1f / speed else 1f

    /** Resamples plan samples onto a uniform [CONTINUOUS_STEP_MS] grid starting now. */
    private fun expandFirstStep(
        amplitudes: FloatArray,
        sharpness: FloatArray,
        count: Int,
        mediaStepMs: Float,
        firstStepMediaMs: Float,
    ): Pair<FloatArray, FloatArray> {
        val total = firstStepMediaMs + (count - 1) * mediaStepMs
        val n = max(1, (total / CONTINUOUS_STEP_MS).roundToInt())
        val a = FloatArray(n)
        val s = FloatArray(n)
        for (i in 0 until n) {
            val t = (i + 0.5f) * CONTINUOUS_STEP_MS
            val index = if (t < firstStepMediaMs) 0 else min(count - 1, 1 + ((t - firstStepMediaMs) / mediaStepMs).toInt())
            a[i] = amplitudes[index]
            s[i] = sharpness[index]
        }
        return a to s
    }

    /** Amplitude waveform with merged equal steps (0–255 amplitudes). */
    fun waveform(amplitudes: FloatArray, count: Int, stepMs: Float, config: HapticConfig): RenderedEffect? {
        val timings = ArrayList<Long>(count)
        val values = ArrayList<Int>(count)
        var carry = 0f
        var any = false
        for (i in 0 until count) {
            val value = (config.calibrate(amplitudes[i]) * 255f).roundToInt().coerceIn(0, 255)
            if (value > 0) any = true
            carry += stepMs
            val ms = carry.toLong()
            if (ms <= 0) continue
            carry -= ms
            val last = values.size - 1
            if (last >= 0 && abs(values[last] - value) <= MERGE_TOLERANCE) {
                timings[last] = timings[last] + ms
            } else {
                timings += ms
                values += value
            }
        }
        if (!any || timings.isEmpty()) return null
        // Trailing silence is unnecessary: the vibration simply ends.
        while (values.size > 1 && values.last() == 0) { values.removeAt(values.size - 1); timings.removeAt(timings.size - 1) }
        return RenderedEffect.Waveform(timings.toLongArray(), values.toIntArray())
    }

    /**
     * On/off rendering for motors without amplitude control: each [PWM_PERIOD_MS] window is on
     * for a fraction equal to the window's mean amplitude.
     */
    fun onOff(amplitudes: FloatArray, count: Int, stepMs: Float, config: HapticConfig): RenderedEffect? {
        val totalMs = count * stepMs
        if (totalMs <= 0f) return null
        // 1. One on-segment and one off-segment per carrier window.
        val states = ArrayList<Boolean>()
        val lengths = ArrayList<Long>()
        var windowStart = 0f
        while (windowStart < totalMs) {
            val windowEnd = min(totalMs, windowStart + PWM_PERIOD_MS)
            val windowMs = (windowEnd - windowStart).roundToLong()
            val first = (windowStart / stepMs).toInt().coerceIn(0, count - 1)
            val last = ((windowEnd / stepMs).toInt() - 1).coerceIn(first, count - 1)
            var sum = 0f
            for (i in first..last) sum += config.calibrate(amplitudes[i])
            val mean = sum / (last - first + 1)
            var onMs = if (mean > 0.85f) windowMs else (mean * windowMs).roundToLong()
            if (onMs < MIN_ON_MS) onMs = 0
            if (onMs > 0) { states += true; lengths += onMs }
            if (windowMs - onMs > 0) { states += false; lengths += windowMs - onMs }
            windowStart = windowEnd
        }
        if (states.none { it }) return null
        // 2. Merge runs into alternating off/on timings that start with an off delay.
        val timings = ArrayList<Long>()
        var currentOn = false
        var accumulated = 0L
        for (i in states.indices) {
            if (states[i] == currentOn) {
                accumulated += lengths[i]
            } else {
                timings += accumulated
                accumulated = lengths[i]
                currentOn = states[i]
            }
        }
        timings += accumulated
        if (!currentOn && timings.size > 2) timings.removeAt(timings.size - 1)
        return RenderedEffect.OnOff(timings.toLongArray())
    }

    /**
     * Android 16 amplitude/frequency envelope. The curve is simplified to at most the device's
     * control-point limit, every point respects the minimum point duration, and the envelope
     * always ends at zero amplitude.
     */
    fun envelope(amplitudes: FloatArray, sharpness: FloatArray, count: Int, stepMs: Float, config: HapticConfig): RenderedEffect? {
        val env = capabilities.envelope ?: return waveform(amplitudes, count, stepMs, config)
        val range = env.frequencyRangeHz ?: return waveform(amplitudes, count, stepMs, config)
        val calibrated = FloatArray(count) { config.calibrate(amplitudes[it]) }
        if (calibrated.all { it <= 0f }) return null
        val maxPoints = max(2, env.maxControlPoints - 1) // reserve the final zero point
        val keep = simplify(calibrated, count, maxPoints)
        val amps = ArrayList<Float>()
        val freqs = ArrayList<Float>()
        val durations = ArrayList<Long>()
        var previousIndex = -1
        val minMs = max(1L, env.minControlPointDurationMs)
        var carry = 0f
        for (index in keep) {
            val spanSteps = index - previousIndex
            carry += spanSteps * stepMs
            var ms = carry.roundToLong()
            if (ms < minMs) ms = minMs
            ms = min(ms, env.maxControlPointDurationMs)
            carry -= ms
            amps += calibrated[index].coerceIn(0f, 1f)
            freqs += frequencyFor(sharpness[index], range)
            durations += ms
            previousIndex = index
        }
        // Ramp to zero to end the effect cleanly.
        amps += 0f
        freqs += freqs.last()
        durations += max(minMs, min(20L, env.maxControlPointDurationMs))
        var total = durations.sum()
        while (total > env.maxDurationMs && durations.size > 2) {
            total -= durations.removeAt(durations.size - 2)
            amps.removeAt(amps.size - 2)
            freqs.removeAt(freqs.size - 2)
        }
        return RenderedEffect.Envelope(freqs.first(), amps.toFloatArray(), freqs.toFloatArray(), durations.toLongArray())
    }

    /** Sharpness → vibration frequency inside the actuator's usable range. */
    fun frequencyFor(sharpness: Float, range: ClosedFloatingPointRange<Float>): Float {
        val lo = range.start
        val hi = range.endInclusive
        return (lo + (hi - lo) * (0.15f + 0.75f * sharpness.coerceIn(0f, 1f))).coerceIn(lo, hi)
    }

    /**
     * Chooses at most [maxPoints] sample indices that best preserve the curve's shape
     * (Ramer–Douglas–Peucker on the amplitude polyline, always keeping the peak and the end).
     */
    private fun simplify(values: FloatArray, count: Int, maxPoints: Int): IntArray {
        if (count <= maxPoints) return IntArray(count) { it }
        val keep = BooleanArray(count)
        keep[count - 1] = true
        var peak = 0
        for (i in 1 until count) if (values[i] > values[peak]) peak = i
        keep[peak] = true
        var kept = if (peak == count - 1) 1 else 2
        while (kept < maxPoints) {
            var bestIndex = -1
            var bestError = 0f
            var segmentStart = -1
            var startValue = 0f
            for (i in 0 until count) {
                if (!keep[i]) continue
                // Segment (segmentStart, i]
                for (j in segmentStart + 1 until i) {
                    val tFrac = (j - segmentStart).toFloat() / (i - segmentStart)
                    val interpolated = startValue + (values[i] - startValue) * tFrac
                    val error = abs(values[j] - interpolated)
                    if (error > bestError) { bestError = error; bestIndex = j }
                }
                segmentStart = i
                startValue = values[i]
            }
            if (bestIndex < 0 || bestError < 0.01f) break
            keep[bestIndex] = true
            kept++
        }
        val out = IntArray(kept)
        var k = 0
        for (i in 0 until count) if (keep[i]) out[k++] = i
        return out.copyOf(k)
    }

    companion object {
        /** Effects at most this long may be rendered as a single primitive. */
        const val PRIMITIVE_MAX_MS = 80f

        /** Resolution of rendered continuous segments (media ms before speed scaling). */
        const val CONTINUOUS_STEP_MS = 10f

        /** Carrier period of the on/off fallback. */
        const val PWM_PERIOD_MS = 20f

        /** Shorter "on" pulses are not reliably felt on simple motors. */
        private const val MIN_ON_MS = 3L

        /** Amplitude steps closer than this are merged (0–255 scale). */
        private const val MERGE_TOLERANCE = 2
    }
}
