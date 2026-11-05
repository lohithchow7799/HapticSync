package com.hapticsync.analysis

import com.hapticsync.dsp.Biquad
import com.hapticsync.dsp.EnvelopeFollower
import com.hapticsync.dsp.FloatHistory
import com.hapticsync.dsp.onePoleCoefficient
import kotlin.math.sqrt

/**
 * Estimates how likely the current audio is dialogue, so that speech does not produce vibration.
 *
 * Evidence used (all acoustic, no speech recognition):
 * - energy concentrated in the speech band (~60 Hz–6 kHz: the voice fundamental of male
 *   speakers lies in the bass band, its formants in the mid bands),
 * - syllabic amplitude modulation at 2–8 Hz in that band (measured with a 4.5 Hz band-pass on
 *   the band level),
 * - intermittent voicing (harmonic frames with a pitch in the human range, 80–400 Hz),
 * - little sub-bass energy, and no engine-like or strongly rhythmic content.
 */
internal class SpeechDetector(hopRateHz: Float) {

    private val modulationFilter = Biquad().apply { setBandPass(hopRateHz.toDouble(), 4.5, 0.8) }
    private val modulationCoef = onePoleCoefficient(500f, hopRateHz)
    private var modulationPower = 0f
    private val voiced = FloatHistory((0.6f * hopRateHz).toInt().coerceAtLeast(8))
    // Long release: dialogue context persists across the short pauses between phrases.
    private val follower = EnvelopeFollower(attackMs = 180f, releaseMs = 1500f, updateRateHz = hopRateHz)
    private var primed = 0

    /** Smoothed speech likelihood in [0, 1]. */
    val likelihood: Float get() = follower.value

    /** Syllabic modulation depth of the speech band, in dB. */
    var modulationDepthDb = 0f; private set

    fun update(
        speechBandDb: Float,
        speechShare: Float,
        subBassShare: Float,
        harmonicity: Float,
        f0Hz: Float,
        engineLikelihood: Float,
        rhythmConfidence: Float,
        silent: Boolean,
    ) {
        val filtered = modulationFilter.process(speechBandDb.toDouble().coerceAtLeast(-100.0)).toFloat()
        modulationPower += (filtered * filtered - modulationPower) * modulationCoef
        modulationDepthDb = sqrt(modulationPower) * 1.414f
        voiced.push(if (harmonicity > 0.22f && f0Hz in 80f..400f) 1f else 0f)
        primed++

        val raw = if (silent || primed < 20) {
            0f
        } else {
            val voicedFraction = voiced.mean()
            Fuzzy.ramp(speechShare, 0.55f, 0.8f) *
                Fuzzy.ramp(modulationDepthDb, 2.5f, 6f) *
                Fuzzy.band(voicedFraction, 0.3f, 0.85f, 0.2f) *
                (1f - Fuzzy.ramp(subBassShare, 0.15f, 0.35f)) *
                (1f - 0.5f * engineLikelihood) *
                (1f - 0.6f * Fuzzy.ramp(rhythmConfidence, 0.45f, 0.75f))
        }
        follower.process(raw)
    }

    fun reset() {
        modulationFilter.reset()
        modulationPower = 0f
        voiced.clear()
        follower.reset()
        primed = 0
        modulationDepthDb = 0f
    }
}
