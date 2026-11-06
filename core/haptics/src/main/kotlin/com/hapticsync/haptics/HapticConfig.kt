package com.hapticsync.haptics

import com.hapticsync.model.BandId
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.EnvelopeShape
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import kotlin.math.pow

/**
 * The haptic-relevant part of a [HapticProfile] plus the device [CalibrationProfile], compiled
 * into flat arrays for the real-time mapper and scheduler.
 */
class HapticConfig(profile: HapticProfile, calibration: CalibrationProfile) {
    private val p = profile.validated()
    private val c = calibration.validated()

    val categoryEnabled = BooleanArray(SoundCategory.COUNT) { p.category(SoundCategory.entries[it]).enabled }
    val categoryIntensity = FloatArray(SoundCategory.COUNT) { p.category(SoundCategory.entries[it]).intensity }
    val categoryDuration = FloatArray(SoundCategory.COUNT) { p.category(SoundCategory.entries[it]).duration }
    val categoryPriority = IntArray(SoundCategory.COUNT) { p.category(SoundCategory.entries[it]).priority }

    val bandEnabled = BooleanArray(BandId.COUNT) { p.band(BandId.entries[it]).enabled }
    val bandIntensity = FloatArray(BandId.COUNT) { p.band(BandId.entries[it]).intensity }
    val bandPulseStrength = FloatArray(BandId.COUNT) { p.band(BandId.entries[it]).pulseStrength }

    val masterIntensity = p.haptics.masterIntensity
    val maxIntensity = p.haptics.maxIntensity
    val pulseDurationScale = p.haptics.pulseDurationScale
    val pulseSpacingUs = (p.haptics.pulseSpacingMs * 1000f).toLong()
    val pulseRepetition = p.haptics.pulseRepetition
    val envelope: EnvelopeShape = p.haptics.envelope
    val attackMs = p.haptics.attackMs
    val releaseMs = p.haptics.releaseMs
    val continuousLayer = p.haptics.continuousLayer
    val transientLayer = p.haptics.transientLayer
    val continuousGain = p.haptics.continuousGain
    val fatigueProtection = p.haptics.fatigueProtection
    val scaleWithSpeed = p.haptics.scaleWithSpeed

    /** Isolation is on with at least one selected sound, category, band or group. */
    val isolationEnabled = p.isolation.isActive
    private val allowedCategories = p.isolation.allowedCategories
    private val allowedBands = p.isolation.allowedBands
    val isolationCategories = BooleanArray(SoundCategory.COUNT) { SoundCategory.entries[it] in allowedCategories }
    val isolationBands = BooleanArray(BandId.COUNT) { BandId.entries[it] in allowedBands }

    /** Isolation lets the energy of selected frequency bands through (alone or with categories). */
    val bandIsolation = isolationEnabled && allowedBands.isNotEmpty()

    val outputGain = c.outputGain
    val amplitudeGamma = c.amplitudeGamma
    val minPerceptibleAmplitude = c.minPerceptibleAmplitude
    val actuatorLatencyUs = (c.actuatorLatencyMs * 1000f).toLong()
    val syncOffsetUs = (c.syncOffsetMs * 1000f).toLong()
    val preferPrimitives = c.preferPrimitives
    val preferEnvelopes = c.preferEnvelopeEffects

    /**
     * Offset added to an effect's media time to obtain the media time at which its command is
     * issued: earlier by the actuator latency, shifted by the user's sync offset.
     */
    val fireOffsetUs: Long get() = syncOffsetUs - actuatorLatencyUs

    /**
     * Whether [category] may produce output under the enabled flags and isolation mode (as a
     * category; selected bands are handled separately by the mapper).
     */
    fun allows(category: SoundCategory): Boolean {
        if (!categoryEnabled[category.ordinal]) return false
        if (!isolationEnabled) return true
        return isolationCategories[category.ordinal]
    }

    /**
     * Applies the device calibration to a normalised amplitude: perceptual curve, output gain,
     * minimum perceptible level and the user's maximum intensity.
     */
    fun calibrate(amplitude: Float): Float {
        if (amplitude <= 0f) return 0f
        val curved = amplitude.coerceIn(0f, 1f).pow(amplitudeGamma) * outputGain
        val floor = minPerceptibleAmplitude
        val lifted = when {
            curved < floor * 0.5f -> 0f
            curved < floor -> floor
            else -> curved
        }
        return lifted.coerceAtMost(maxIntensity)
    }
}
