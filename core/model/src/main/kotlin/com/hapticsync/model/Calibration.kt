package com.hapticsync.model

import kotlinx.serialization.Serializable

/** Which Android vibration usage class HapticSync's vibrations are attributed to. */
@Serializable
enum class VibrationUsage(val label: String, val description: String) {
    MEDIA(
        "Media",
        "Recommended. Follows the system \"media vibration\" intensity setting.",
    ),
    PHYSICAL_EMULATION(
        "Physical emulation",
        "Intended for effects that emulate physical sensations (Android 13+ treats it like touch feedback).",
    ),
    TOUCH(
        "Touch feedback",
        "Follows the system touch-feedback setting. Use if media vibration is unavailable or weak.",
    ),
}

/**
 * Device-specific calibration (Calibration screen), saved per device model.
 *
 * @property deviceKey manufacturer and model the profile was created for.
 * @property outputGain device output gain applied after the preset's master intensity ([0.2, 1.5]).
 * @property amplitudeGamma perceptual curve: `out = in^gamma`. Values < 1 lift weak vibrations.
 * @property minPerceptibleAmplitude amplitudes in (0, value) are raised to this level so that
 *   faint effects remain noticeable; amplitudes below half of it are dropped.
 * @property actuatorLatencyMs time from the vibrate() call until the motor's output is felt.
 *   The scheduler issues commands this much earlier.
 * @property syncOffsetMs user synchronisation offset: negative fires haptics earlier, positive later.
 * @property preferPrimitives use composed haptic primitives for crisp transients when supported.
 * @property preferEnvelopeEffects use Android 16 amplitude/frequency envelopes when supported.
 * @property usage vibration usage class.
 */
@Serializable
data class CalibrationProfile(
    val deviceKey: String = "",
    val outputGain: Float = 1f,
    val amplitudeGamma: Float = 0.85f,
    val minPerceptibleAmplitude: Float = 0.08f,
    val actuatorLatencyMs: Float = 18f,
    val syncOffsetMs: Float = 0f,
    val preferPrimitives: Boolean = true,
    val preferEnvelopeEffects: Boolean = true,
    val usage: VibrationUsage = VibrationUsage.MEDIA,
    val savedAtMs: Long = 0L,
) {
    fun validated(): CalibrationProfile = copy(
        outputGain = outputGain.finiteOr(1f).coerceIn(0.2f, 1.5f),
        amplitudeGamma = amplitudeGamma.finiteOr(0.85f).coerceIn(0.4f, 2f),
        minPerceptibleAmplitude = minPerceptibleAmplitude.finiteOr(0.08f).coerceIn(0f, 0.5f),
        actuatorLatencyMs = actuatorLatencyMs.finiteOr(18f).coerceIn(0f, MAX_LATENCY_MS),
        syncOffsetMs = syncOffsetMs.finiteOr(0f).coerceIn(-MAX_SYNC_OFFSET_MS, MAX_SYNC_OFFSET_MS),
    )

    companion object {
        const val MAX_SYNC_OFFSET_MS = 300f
        const val MAX_LATENCY_MS = 120f
    }
}
