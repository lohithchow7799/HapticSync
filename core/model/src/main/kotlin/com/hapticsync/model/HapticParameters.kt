package com.hapticsync.model

import kotlinx.serialization.Serializable

/** Shape of the decay part of a haptic envelope. */
@Serializable
enum class EnvelopeShape(val label: String) {
    /** Straight-line fade. */
    LINEAR("Linear"),

    /** Natural exponential decay (default; resembles acoustic decay). */
    EXPONENTIAL("Exponential"),

    /** Holds longer, then falls quickly (punchy). */
    SHARP("Sharp"),

    /** Raised-cosine fade (soft, rounded). */
    SMOOTH("Smooth"),
}

/**
 * Global haptic generation parameters: the ten independently adjustable controls of the
 * audio-to-haptic translation engine, plus layer switches.
 *
 * @property masterIntensity (1) overall vibration intensity in [0, 1].
 * @property pulseDurationScale (2) multiplier for pulse durations ([0.25, 3]).
 * @property pulseSpacingMs (3) minimum gap between consecutive transient pulses (ms).
 * @property pulseRepetition (4) repetition amount in [0, 2]: 0 renders a single pulse per event,
 *   1 the natural pattern (e.g. an explosion's aftershocks), 2 doubles the repeats.
 * @property envelope (5) envelope shape of decaying effects.
 * @property attackMs (6) attack (rise) time of effects, in ms.
 * @property releaseMs (7) release (fall) time of continuous textures, in ms.
 * @property eventSensitivity (8) global event-detection sensitivity in [0, 1].
 * @property minAmplitudeDb (9) minimum detectable audio amplitude (dBFS); quieter audio is ignored.
 * @property maxIntensity (10) ceiling for any vibration amplitude in [0, 1].
 * @property continuousLayer whether sustained sounds (engines, rumble) produce continuous texture.
 * @property transientLayer whether discrete events produce pulses.
 * @property continuousGain level of the continuous layer relative to transients, in [0, 1].
 * @property fatigueProtection gently reduces long, strong continuous vibration.
 * @property scaleWithSpeed shortens/lengthens pattern durations with the playback speed.
 */
@Serializable
data class HapticParameters(
    val masterIntensity: Float = 0.85f,
    val pulseDurationScale: Float = 1f,
    val pulseSpacingMs: Float = 45f,
    val pulseRepetition: Float = 1f,
    val envelope: EnvelopeShape = EnvelopeShape.EXPONENTIAL,
    val attackMs: Float = 4f,
    val releaseMs: Float = 140f,
    val eventSensitivity: Float = 0.55f,
    val minAmplitudeDb: Float = -54f,
    val maxIntensity: Float = 1f,
    val continuousLayer: Boolean = true,
    val transientLayer: Boolean = true,
    val continuousGain: Float = 0.6f,
    val fatigueProtection: Boolean = true,
    val scaleWithSpeed: Boolean = true,
) {
    fun validated(): HapticParameters = copy(
        masterIntensity = masterIntensity.unit(),
        pulseDurationScale = pulseDurationScale.finiteOr(1f).coerceIn(0.25f, 3f),
        pulseSpacingMs = pulseSpacingMs.finiteOr(45f).coerceIn(MIN_SPACING_MS, MAX_SPACING_MS),
        pulseRepetition = pulseRepetition.finiteOr(1f).coerceIn(0f, 2f),
        attackMs = attackMs.finiteOr(4f).coerceIn(0f, MAX_ATTACK_MS),
        releaseMs = releaseMs.finiteOr(140f).coerceIn(MIN_RELEASE_MS, MAX_RELEASE_MS),
        eventSensitivity = eventSensitivity.unit(),
        minAmplitudeDb = minAmplitudeDb.finiteOr(-54f).coerceIn(-90f, -10f),
        maxIntensity = maxIntensity.finiteOr(1f).coerceIn(0.05f, 1f),
        continuousGain = continuousGain.unit(),
    )

    companion object {
        const val MIN_SPACING_MS = 10f
        const val MAX_SPACING_MS = 400f
        const val MAX_ATTACK_MS = 200f
        const val MIN_RELEASE_MS = 20f
        const val MAX_RELEASE_MS = 1500f
    }
}
