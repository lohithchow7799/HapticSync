package com.hapticsync.haptics

import com.hapticsync.model.SoundCategory
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Test patterns for the calibration screen. Each produces a device-independent curve that is
 * rendered through the same [HapticRenderer] and calibration as real content, so what the user
 * feels while calibrating is what playback will use.
 */
enum class CalibrationPattern(val label: String, val description: String) {
    SOFT("Soft", "Gentle 300 ms vibration at 25 % strength"),
    MEDIUM("Medium", "300 ms vibration at 55 % strength"),
    STRONG("Strong", "300 ms vibration at full strength"),
    SHORT_IMPACT("Short impact", "A single crisp 25 ms hit"),
    LONG_RUMBLE("Long rumble", "A deep 1.5 s rumble that slowly fades"),
    RAPID_PULSES("Rapid pulses", "Eight quick pulses, like a drum roll"),
    RISING("Smooth increase", "Builds smoothly from nothing to full strength over 1.2 s"),
    ;

    /** Curve and primitive for this pattern. */
    fun build(): TransientEffect {
        val step = HapticMapper.CURVE_STEP_MS
        fun curve(durationMs: Float, sharpness: Float, f: (Float) -> Float): HapticCurve {
            val n = (durationMs / step).toInt().coerceAtLeast(1)
            return HapticCurve(step, FloatArray(n) { f((it + 0.5f) * step / durationMs).coerceIn(0f, 1f) }, sharpness)
        }
        val (shape, primitive) = when (this) {
            SOFT -> curve(300f, 0.4f) { 0.25f } to null
            MEDIUM -> curve(300f, 0.4f) { 0.55f } to null
            STRONG -> curve(300f, 0.5f) { 1f } to null
            SHORT_IMPACT -> curve(25f, 0.9f) { if (it < 0.7f) 1f else 0.3f } to HapticPrimitive.CLICK
            LONG_RUMBLE -> curve(1500f, 0.1f) { x -> (0.9f * exp(-2.2f * x)) * (0.85f + 0.15f * sin(2 * PI.toFloat() * 9f * x * 1.5f)) } to null
            RAPID_PULSES -> curve(480f, 0.7f) { x -> if ((x * 8f) % 1f < 0.4f) 0.85f else 0f } to null
            RISING -> curve(1200f, 0.3f) { x -> x * x * (3 - 2 * x) } to HapticPrimitive.SLOW_RISE
        }
        return TransientEffect(
            timeUs = 0L,
            category = SoundCategory.GENERAL,
            curve = shape,
            primitive = primitive,
            primitiveScale = 1f,
            priority = 10,
            latenessToleranceUs = Long.MAX_VALUE / 4,
            epoch = 0,
        )
    }
}
