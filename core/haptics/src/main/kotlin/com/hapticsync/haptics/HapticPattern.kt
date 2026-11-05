package com.hapticsync.haptics

import com.hapticsync.model.SoundCategory

/**
 * A device-independent haptic shape: an amplitude curve sampled every [stepMs] milliseconds of
 * *media* time, a perceptual sharpness (0 = deep, smooth; 1 = crisp, bright), and an optional
 * primitive that renders the same sensation crisply on devices that support composition.
 */
class HapticCurve(
    val stepMs: Float,
    val amplitudes: FloatArray,
    val sharpness: Float,
) {
    val durationMs: Float get() = stepMs * amplitudes.size
    val peak: Float get() = amplitudes.maxOrNull() ?: 0f

    /** Returns a copy without the first [skipMs] milliseconds (used when an effect starts late). */
    fun skip(skipMs: Float): HapticCurve {
        val steps = (skipMs / stepMs).toInt()
        if (steps <= 0) return this
        if (steps >= amplitudes.size) return HapticCurve(stepMs, FloatArray(0), sharpness)
        return HapticCurve(stepMs, amplitudes.copyOfRange(steps, amplitudes.size), sharpness)
    }
}

/**
 * A transient haptic effect scheduled for a media time.
 *
 * @property timeUs media time (sink time base) at which the effect's start must be felt.
 * @property curve the shape to render.
 * @property primitive optional crisp rendering for composition-capable devices.
 * @property primitiveScale primitive intensity in [0, 1].
 * @property priority mixing priority (0–10); a higher priority interrupts a lower one.
 * @property latenessToleranceUs how late the effect may still start (its beginning is then
 *   skipped so that the rest stays in sync).
 * @property epoch playback epoch the effect belongs to (it is discarded after a seek).
 */
class TransientEffect(
    val timeUs: Long,
    val category: SoundCategory,
    val curve: HapticCurve,
    val primitive: HapticPrimitive?,
    val primitiveScale: Float,
    val priority: Int,
    val latenessToleranceUs: Long,
    val epoch: Int,
) {
    val durationUs: Long get() = (curve.durationMs * 1000f).toLong()
}

/** One sample of the continuous haptic plan (one analysis hop). */
class PlanSample(
    var timeUs: Long = 0L,
    var amplitude: Float = 0f,
    var sharpness: Float = 0.3f,
    var category: Int = -1,
    var epoch: Int = 0,
)
