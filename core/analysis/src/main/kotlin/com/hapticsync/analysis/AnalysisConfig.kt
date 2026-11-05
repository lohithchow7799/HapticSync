package com.hapticsync.analysis

import com.hapticsync.dsp.RealFft
import com.hapticsync.model.AnalysisQuality
import com.hapticsync.model.BandId
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import kotlin.math.roundToInt

/**
 * The analysis-relevant part of a [HapticProfile], compiled for one sample rate into flat arrays
 * that the real-time loop can read without allocation or map lookups.
 */
class AnalysisConfig private constructor(
    val sampleRate: Int,
    val hopSize: Int,
    val windowSize: Int,
    val fftSize: Int,
    val bandLowHz: FloatArray,
    val bandHighHz: FloatArray,
    val bandEnabled: BooleanArray,
    val bandSensitivity: FloatArray,
    val bandThresholdDb: FloatArray,
    val bandReleaseMs: FloatArray,
    val rangeLowHz: Float,
    val rangeHighHz: Float,
    val globalSensitivity: Float,
    val minAmplitudeDb: Float,
    val categorySensitivity: FloatArray,
    val decisionDelayHops: Int,
    val refractoryHops: Int,
    val adaptiveNormalization: Boolean,
    val noiseGate: Boolean,
    val quality: AnalysisQuality,
) {
    /** Duration of one hop in microseconds (exact for the configured sample rate). */
    val hopDurationUs: Double = hopSize * 1_000_000.0 / sampleRate

    /** Analysis frames per second. */
    val hopRateHz: Float = sampleRate.toFloat() / hopSize

    /** Whether structural parameters (that require re-allocating buffers) equal [other]'s. */
    fun sameStructure(other: AnalysisConfig): Boolean =
        sampleRate == other.sampleRate && hopSize == other.hopSize && windowSize == other.windowSize

    /** Whether the band edges equal [other]'s. */
    fun sameBands(other: AnalysisConfig): Boolean =
        bandLowHz.contentEquals(other.bandLowHz) && bandHighHz.contentEquals(other.bandHighHz)

    /**
     * Acceptance threshold for a category's evidence score: higher sensitivity (category and
     * global) accepts weaker evidence.
     */
    fun acceptanceThreshold(category: SoundCategory): Float {
        val e = 0.5f * categorySensitivity[category.ordinal] + 0.5f * globalSensitivity
        return 0.62f - 0.42f * e
    }

    companion object {
        const val BANDS = BandId.COUNT

        fun from(profile: HapticProfile, sampleRate: Int): AnalysisConfig {
            val p = profile.validated()
            val a = p.analysis
            val hop = (sampleRate * a.hopMs / 1000f).roundToInt().coerceAtLeast(16)
            val window = (sampleRate * a.windowMs / 1000f).roundToInt().coerceAtLeast(hop)
            val fft = RealFft.nextPowerOfTwo(window)
            val bands = BandId.entries
            val nyquist = sampleRate / 2f
            return AnalysisConfig(
                sampleRate = sampleRate,
                hopSize = hop,
                windowSize = window,
                fftSize = fft,
                bandLowHz = FloatArray(BANDS) { maxOf(p.band(bands[it]).lowHz, a.rangeLowHz) },
                bandHighHz = FloatArray(BANDS) { minOf(p.band(bands[it]).highHz, a.rangeHighHz, nyquist * 0.95f) },
                bandEnabled = BooleanArray(BANDS) {
                    val b = p.band(bands[it])
                    b.enabled && b.highHz > a.rangeLowHz && b.lowHz < a.rangeHighHz
                },
                bandSensitivity = FloatArray(BANDS) { p.band(bands[it]).sensitivity },
                bandThresholdDb = FloatArray(BANDS) { p.band(bands[it]).thresholdDb },
                bandReleaseMs = FloatArray(BANDS) { 25f + 400f * p.band(bands[it]).smoothing },
                rangeLowHz = a.rangeLowHz,
                rangeHighHz = minOf(a.rangeHighHz, nyquist),
                globalSensitivity = p.haptics.eventSensitivity,
                minAmplitudeDb = p.haptics.minAmplitudeDb,
                categorySensitivity = FloatArray(SoundCategory.COUNT) { p.category(SoundCategory.entries[it]).sensitivity },
                decisionDelayHops = (a.decisionDelayMs / a.hopMs).roundToInt().coerceIn(0, 16),
                refractoryHops = (p.haptics.pulseSpacingMs / a.hopMs).roundToInt().coerceIn(1, 40),
                adaptiveNormalization = a.adaptiveNormalization,
                noiseGate = a.noiseGate,
                quality = a.quality,
            )
        }
    }
}
