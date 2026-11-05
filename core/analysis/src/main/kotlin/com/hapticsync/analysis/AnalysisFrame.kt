package com.hapticsync.analysis

import com.hapticsync.dsp.Db
import com.hapticsync.model.BandId
import com.hapticsync.model.SoundCategory

/**
 * Features of one analysis hop. Instances are pre-allocated and overwritten in place by the
 * analyser; copy values out if they must outlive the next hop.
 */
class AnalysisFrame {
    /** Media time of the first sample of the hop (sink time base). */
    var timeUs: Long = 0L

    /** Hop duration in microseconds. */
    var durationUs: Long = 0L

    // --- Level & time-domain features -------------------------------------------------------

    /** RMS amplitude over the analysis window. */
    var rms: Float = 0f

    /** RMS in dBFS. */
    var rmsDb: Float = Db.FLOOR_DB

    /** Peak absolute amplitude within the hop. */
    var peak: Float = 0f

    /** Crest factor of the hop (peak / RMS) in dB. */
    var crestDb: Float = 0f

    /** Zero-crossing rate (crossings per sample) over the window. */
    var zeroCrossingRate: Float = 0f

    /** Level relative to the adaptive programme loudness reference, in dB. */
    var relativeLevelDb: Float = 0f

    /** Current programme loudness reference in dBFS. */
    var loudnessReferenceDb: Float = -30f

    /** Whether the hop contains near-full-scale (clipped) samples. */
    var clipped: Boolean = false

    /** Whether the hop is below the minimum detectable amplitude. */
    var silent: Boolean = true

    // --- Spectral features --------------------------------------------------------------------

    /** Spectral centroid in Hz. */
    var centroidHz: Float = 0f

    /** Spectral flatness in [0, 1] (0 = tonal, 1 = noise-like). */
    var flatness: Float = 0f

    /** Half-wave rectified spectral flux (log-magnitude domain). */
    var flux: Float = 0f

    /** 85 % spectral roll-off frequency in Hz. */
    var rolloffHz: Float = 0f

    /** Log-spaced spectrum for visualisation, each value in [0, 1]. */
    val spectrum = FloatArray(SPECTRUM_BARS)

    // --- Bands ----------------------------------------------------------------------------------

    /** Smoothed band levels in dBFS. */
    val bandDb = FloatArray(BandId.COUNT) { Db.FLOOR_DB }

    /** Band level above its adaptive noise floor, in dB (≥ 0). */
    val bandExcessDb = FloatArray(BandId.COUNT)

    /** Band level normalised to [0, 1] for visualisation and band-driven haptics. */
    val bandLevel = FloatArray(BandId.COUNT)

    /** Energy share of each band (sums to ≈1 when there is signal). */
    val bandShare = FloatArray(BandId.COUNT)

    /** Index of the band with the largest energy share. */
    var dominantBand: Int = 0

    /** Change of sub-bass + bass level, dB per second (positive = rising). */
    var lowFrequencyTrendDbPerSecond: Float = 0f

    // --- Onsets & transients ------------------------------------------------------------------

    /** Onset strength (detection function value). */
    var onsetStrength: Float = 0f

    /** Adaptive onset threshold at this hop. */
    var onsetThreshold: Float = 0f

    /** Transient intensity in [0, 1] (onset strength relative to threshold). */
    var transientIntensity: Float = 0f

    /** Whether an onset was confirmed during this hop. */
    var onsetDetected: Boolean = false

    /** Attack time of the most recently classified event (ms). */
    var lastAttackMs: Float = 0f

    /** Decay rate of the most recently classified event (dB/s). */
    var lastDecayDbPerSecond: Float = 0f

    // --- Pitch / engine ------------------------------------------------------------------------

    /** Fundamental frequency estimate (Hz), 0 when unpitched. */
    var f0Hz: Float = 0f

    /** Harmonicity in [0, 1]. */
    var harmonicity: Float = 0f

    /** Pitch velocity in semitones per second (positive = rising). */
    var pitchVelocity: Float = 0f

    /** Engine-likeness of the current sound in [0, 1]. */
    var engineLikelihood: Float = 0f

    /** Normalised engine speed proxy in [0, 1]. */
    var rpmNormalized: Float = 0f

    // --- Context -----------------------------------------------------------------------------------

    /** Likelihood that the audio is dialogue, in [0, 1]. */
    var speechLikelihood: Float = 0f

    /** Strength of a regular rhythmic pulse (music) in [0, 1]. */
    var rhythmConfidence: Float = 0f

    /** Estimated beat period in seconds (0 when none). */
    var beatPeriodSec: Float = 0f

    /** Stereo balance in [-1, 1] (negative = left). */
    var pan: Float = 0f

    /** Level of each continuous category in [0, 1], indexed by [SoundCategory.ordinal]. */
    val continuous = FloatArray(SoundCategory.COUNT)

    /** Most prominent category right now (event or texture), or -1. */
    var dominantCategory: Int = -1

    companion object {
        const val SPECTRUM_BARS = 48
    }
}
