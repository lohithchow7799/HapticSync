package com.hapticsync.analysis

import com.hapticsync.analysis.Fuzzy.ramp
import com.hapticsync.dsp.EnvelopeFollower
import com.hapticsync.dsp.onePoleCoefficient
import com.hapticsync.model.BandId
import com.hapticsync.model.SoundCategory
import kotlin.math.abs
import kotlin.math.max

/**
 * Levels of the sustained (non-engine) textures, each in [0, 1]:
 *
 * - cinematic rumble: sustained sub-bass/bass energy well above its noise floor, outside music,
 * - sustained bass: held low-frequency notes in a musical context,
 * - rain: dense, noisy high-frequency energy,
 * - wind: noisy, unpitched bass/low-mid energy without much sub-bass,
 * - machinery: very stable pitched hum (mains harmonics) or steady periodic clanking,
 * - dialogue: from the speech detector,
 * - general: residual band energy that no other texture explains.
 */
internal class TextureDetector(hopRateHz: Float) {

    private val shareCoef = onePoleCoefficient(250f, hopRateHz)
    private val slowCoef = onePoleCoefficient(900f, hopRateHz)
    private var lowShare = 0f
    private var lowMidShare = 0f
    private var highShare = 0f
    private var subShare = 0f
    private var flatness = 0f
    private var harmonicity = 0f
    private var stableHops = 0
    private var hopRate = hopRateHz

    private val followers = Array(SoundCategory.COUNT) { EnvelopeFollower(120f, 450f, hopRateHz) }

    fun level(category: SoundCategory): Float = followers[category.ordinal].value

    fun update(
        frame: AnalysisFrame,
        engine: EngineTracker,
        speech: Float,
        musicContext: Float,
        onsetRate: Float,
        rhythmicPeriodicity: Float,
    ) {
        val s = frame.bandShare
        lowShare += (s[0] + s[1] - lowShare) * shareCoef
        subShare += (s[0] - subShare) * shareCoef
        lowMidShare += (s[2] - lowMidShare) * shareCoef
        highShare += (s[4] + s[5] - highShare) * slowCoef
        flatness += (frame.flatness - flatness) * slowCoef
        harmonicity += (frame.harmonicity - harmonicity) * slowCoef

        val e = engine.likelihood
        val silent = frame.silent
        val relative = frame.relativeLevelDb
        val lowExcess = max(frame.bandExcessDb[BandId.SUB_BASS.ordinal], frame.bandExcessDb[BandId.BASS.ordinal])
        val lowRelative = max(frame.bandDb[0], frame.bandDb[1]) - frame.loudnessReferenceDb

        if (abs(engine.trendLong) < 0.3f && frame.harmonicity > 0.35f && frame.f0Hz > 0f) stableHops++ else stableHops = 0
        val stable = ramp(stableHops / hopRate, 1f, 2.5f)

        fun set(category: SoundCategory, raw: Float) {
            followers[category.ordinal].process(if (silent) 0f else raw.coerceIn(0f, 1f))
        }

        set(
            SoundCategory.CINEMATIC_RUMBLE,
            ramp(lowShare, 0.35f, 0.65f) * ramp(lowExcess, 4f, 16f) * ramp(lowRelative, -18f, -4f) *
                (1f - e) * (1f - 0.7f * musicContext) * (1f - speech),
        )
        set(
            SoundCategory.SUSTAINED_BASS,
            ramp(lowShare, 0.3f, 0.6f) * ramp(lowRelative, -18f, -6f) * max(musicContext, 0.5f * harmonicity) * (1f - e),
        )
        set(
            SoundCategory.RAIN,
            ramp(highShare, 0.35f, 0.6f) * ramp(flatness, 0.3f, 0.55f) * ramp(relative, -30f, -12f) *
                (1f - speech) * (1f - rhythmicPeriodicity) * (1f - ramp(lowShare, 0.4f, 0.6f)),
        )
        set(
            SoundCategory.WIND,
            ramp(lowShare + lowMidShare, 0.45f, 0.75f) * (1f - ramp(subShare, 0.35f, 0.55f)) *
                (1f - ramp(harmonicity, 0.2f, 0.4f)) * (1f - ramp(onsetRate, 1.5f, 4f)) *
                ramp(relative, -30f, -12f) * (1f - e) * (1f - speech) * (1f - rhythmicPeriodicity),
        )
        val mains = if (frame.f0Hz > 0f && isMainsLike(frame.f0Hz)) 1f else 0f
        set(
            SoundCategory.MACHINERY,
            stable * ramp(frame.harmonicity, 0.3f, 0.6f) * max(mains, 0.4f * rhythmicPeriodicity) *
                (1f - musicContext) * (1f - speech) * ramp(relative, -30f, -12f),
        )
        set(SoundCategory.DIALOGUE, speech * ramp(relative, -25f, -8f))

        var others = e
        for (category in TEXTURES) others = max(others, followers[category.ordinal].value)
        var excess = 0f
        var bands = 0
        for (b in 0 until BandId.COUNT) {
            excess += frame.bandExcessDb[b]
            bands++
        }
        val meanExcess = if (bands > 0) excess / bands else 0f
        set(
            SoundCategory.GENERAL,
            ramp(meanExcess, 8f, 24f) * (1f - others) * (1f - speech) * ramp(relative, -20f, -5f),
        )
    }

    private fun isMainsLike(f0: Float): Boolean {
        for (base in floatArrayOf(50f, 60f)) for (k in 1..3) if (abs(f0 - base * k) < base * k * 0.03f) return true
        return false
    }

    fun reset() {
        lowShare = 0f; lowMidShare = 0f; highShare = 0f; subShare = 0f; flatness = 0f; harmonicity = 0f
        stableHops = 0
        for (f in followers) f.reset()
    }

    companion object {
        /** Textures that explain energy before it is attributed to "general background audio". */
        private val TEXTURES = listOf(
            SoundCategory.CINEMATIC_RUMBLE, SoundCategory.SUSTAINED_BASS, SoundCategory.RAIN,
            SoundCategory.WIND, SoundCategory.MACHINERY, SoundCategory.DIALOGUE,
        )
    }
}
