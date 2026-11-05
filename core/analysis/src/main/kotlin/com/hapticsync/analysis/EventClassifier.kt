package com.hapticsync.analysis

import com.hapticsync.analysis.Fuzzy.band
import com.hapticsync.analysis.Fuzzy.ramp
import com.hapticsync.dsp.Db
import com.hapticsync.model.BandId
import com.hapticsync.model.SoundCategory
import kotlin.math.max

/**
 * Classifies onsets into transient sound categories.
 *
 * Each onset opens a *candidate* that is observed for a short decision window (default ≈90 ms):
 * the attack (time to peak), the early decay per band, the spectral balance of the energy the
 * event *added* (so background sound does not dominate), noisiness, crest factor and the rise in
 * every band. Because the audio sink buffers the stream ahead of the speaker, this observation
 * happens *before* the sound is heard, so the resulting event keeps its exact onset timestamp.
 *
 * The candidate stores per-hop measurements. When the next onset arrives before the window has
 * elapsed (rapid fire, drum rolls), the candidate is decided early using only the hops that
 * precede the new onset, so the next sound never contaminates the decay measurement.
 *
 * Each category has an evidence score in [0, 1] built from fuzzy memberships of those
 * features; the best category whose score passes its sensitivity-dependent acceptance threshold
 * wins. Unrecognised but significant onsets become `BEAT` (on a rhythmic grid), `DIALOGUE`
 * (during speech) or `GENERAL`.
 *
 * The rules encode acoustic characteristics, not semantics: a sound that *behaves* like a kick
 * drum is reported as a bass drum. Optional [semanticPriors] from an on-device model can tip the
 * balance between acoustically similar categories.
 */
internal class EventClassifier(
    private var config: AnalysisConfig,
    private val sink: SoundEventSink,
) {
    private var active = false
    private var onsetTimeUs = 0L
    private var hops = 0
    private val baseDb = FloatArray(BANDS)
    private val hopBandDb = Array(MAX_HOPS) { FloatArray(BANDS) }
    private val hopLevelDb = FloatArray(MAX_HOPS)
    private val hopFlatness = FloatArray(MAX_HOPS)
    private val hopCentroid = FloatArray(MAX_HOPS)
    private var onsetCrestDb = 0f
    private var nextCrestDb = 0f
    private var loudnessReferenceDb = -30f
    private var onBeat = false
    private var rhythm = 0f
    private var engine = 0f
    private var speech = 0f
    private var musicContext = 0f
    private var stepRegularity = 0f
    private var lowQuietSec = 0f
    private var machinery = 0f
    private var tonality = 0f

    private var maskStartUs = Long.MIN_VALUE
    private var maskUntilUs = Long.MIN_VALUE
    private var maskPeakDb = Db.FLOOR_DB
    private var maskDecayDbPerSecond = 20f

    private val peakDb = FloatArray(BANDS)
    private val peakHop = IntArray(BANDS)
    private val endDb = FloatArray(BANDS)
    private val shares = FloatArray(BANDS)
    private val bandRise = FloatArray(BANDS)
    private val scores = FloatArray(SoundCategory.COUNT)

    /** Multipliers in [0.5, 1.5] per category from an optional semantic model (1 = neutral). */
    val semanticPriors = FloatArray(SoundCategory.COUNT) { 1f }

    /** Upper bound on the decision window imposed by the available look-ahead. */
    var maxDecisionHops: Int = Int.MAX_VALUE

    var lastAttackMs = 0f; private set
    var lastDecayDbPerSecond = 0f; private set
    var lastCategory: SoundCategory? = null; private set
    var lastEventTimeUs = Long.MIN_VALUE; private set

    /** Evidence scores of the most recently decided candidate, indexed by category (diagnostics). */
    val lastScores: FloatArray get() = scores

    fun setConfig(newConfig: AnalysisConfig) {
        config = newConfig
    }

    /**
     * Starts a candidate for an onset at [timeUs]. If a candidate is still open it is decided
     * first, using only the hops recorded before this onset's hop.
     *
     * @param baselineDb band levels shortly before the onset.
     * @param onsetHopDb band levels of the onset hop.
     * @param onsetHopLevelDb broadband level of the onset hop.
     * @param onsetCrestDb crest factor of the onset hop.
     */
    fun onOnset(
        timeUs: Long,
        baselineDb: FloatArray,
        onsetHopDb: FloatArray,
        onsetHopLevelDb: Float,
        onsetCrestDb: Float,
        onsetFlatness: Float,
        onsetCentroidHz: Float,
        context: Context,
    ) {
        // The open candidate has already absorbed the new onset's hop and the confirmation hop.
        if (active) decide(validHops = hops - 2)
        active = true
        onsetTimeUs = timeUs
        baselineDb.copyInto(baseDb)
        onsetHopDb.copyInto(hopBandDb[0])
        hopLevelDb[0] = onsetHopLevelDb
        hopFlatness[0] = onsetFlatness
        hopCentroid[0] = onsetCentroidHz
        hops = 1
        this.onsetCrestDb = onsetCrestDb
        nextCrestDb = 0f
        loudnessReferenceDb = context.loudnessReferenceDb
        onBeat = context.onBeat
        rhythm = context.rhythm
        engine = context.engine
        speech = context.speech
        musicContext = context.musicContext
        stepRegularity = context.stepRegularity
        lowQuietSec = context.lowQuietSec
        machinery = context.machinery
        tonality = context.tonality
    }

    /**
     * Records the hop following the onset hop(s) and decides once the window is complete.
     * [bandDb] are fast-release band levels (decay must not be masked by display smoothing).
     */
    fun onHop(bandDb: FloatArray, frame: AnalysisFrame, levelDb: Float) {
        if (!active) return
        if (hops < MAX_HOPS) {
            bandDb.copyInto(hopBandDb[hops])
            hopLevelDb[hops] = levelDb
            hopFlatness[hops] = frame.flatness
            hopCentroid[hops] = frame.centroidHz
            if (hops == 1) nextCrestDb = frame.crestDb
        }
        hops++
        val decision = minOf(config.decisionDelayHops, maxDecisionHops).coerceIn(MIN_DECISION_HOPS, MAX_HOPS - 1)
        if (hops > decision) decide(validHops = hops)
    }

    /** Decides the current candidate immediately (e.g. on a discontinuity). */
    fun flush() {
        if (active) decide(validHops = hops)
    }

    private fun decide(validHops: Int) {
        active = false
        var n = validHops.coerceIn(1, minOf(hops, MAX_HOPS))
        // A new transient that starts in the last hops (before it was confirmed as an onset) must
        // not count as this event's tail: cut the window at the last local minimum before a rise.
        // Only an abrupt jump counts; a held note building up gradually is part of this event.
        if (n >= 5) {
            var minIndex = 2
            for (h in 2 until n) if (hopLevelDb[h] <= hopLevelDb[minIndex]) minIndex = h
            val abrupt = hopLevelDb[n - 1] - hopLevelDb[n - 2] > JUMP_DB || hopLevelDb[n - 2] - hopLevelDb[n - 3] > JUMP_DB
            if (abrupt && minIndex < n - 1 && hopLevelDb[n - 1] - hopLevelDb[minIndex] > TAIL_RISE_DB) n = minIndex + 1
        }
        val hopMs = (config.hopDurationUs / 1000.0).toFloat()
        val hopSec = hopMs / 1000f

        // Per-band peaks and end levels within the valid hops.
        var peakLevel = Db.FLOOR_DB
        var peakLevelHop = 0
        for (b in 0 until BANDS) { peakDb[b] = Db.FLOOR_DB; peakHop[b] = 0 }
        var flatSum = 0f
        var centroidSum = 0f
        for (h in 0 until n) {
            for (b in 0 until BANDS) {
                val l = hopBandDb[h][b]
                if (l > peakDb[b]) { peakDb[b] = l; peakHop[b] = h }
            }
            if (hopLevelDb[h] > peakLevel) { peakLevel = hopLevelDb[h]; peakLevelHop = h }
            flatSum += hopFlatness[h]
            centroidSum += hopCentroid[h]
        }
        hopBandDb[n - 1].copyInto(endDb)
        val endLevel = hopLevelDb[n - 1]

        // Spectral balance of what *changed*: energy the event added in each band, which keeps
        // background sound (rain under thunder, music under an explosion) out of the decision.
        var riseTotal = 0f
        var absoluteTotal = 0f
        for (b in 0 until BANDS) {
            if (!config.bandEnabled[b]) { shares[b] = 0f; bandRise[b] = 0f; continue }
            val peak = Db.toPower(peakDb[b])
            absoluteTotal += peak
            shares[b] = (peak - Db.toPower(baseDb[b])).coerceAtLeast(0f)
            riseTotal += shares[b]
            bandRise[b] = peakDb[b] - baseDb[b]
        }
        if (absoluteTotal <= 0f) return
        if (riseTotal > absoluteTotal * 0.05f) {
            for (b in 0 until BANDS) shares[b] /= riseTotal
        } else {
            for (b in 0 until BANDS) shares[b] = if (config.bandEnabled[b]) Db.toPower(peakDb[b]) / absoluteTotal else 0f
        }
        val low = shares[0] + shares[1]
        val lowMid = shares[2]
        val mid = shares[3]
        val highMid = shares[4]
        val high = shares[5]
        val highAll = highMid + high

        var riseCount = 0
        var maxRise = 0f
        for (b in 0 until BANDS) {
            if (!config.bandEnabled[b]) continue
            if (bandRise[b] > 10f) riseCount++
            maxRise = max(maxRise, bandRise[b])
        }
        val lowBand = if (peakDb[0] >= peakDb[1]) 0 else 1
        val lowRise = max(bandRise[0], bandRise[1])
        val subRise = bandRise[0]
        val lowMidRise = max(bandRise[2], bandRise[3])
        val highRise = max(bandRise[4], bandRise[5])

        val attackMs = peakLevelHop * hopMs
        val lowAttackMs = peakHop[lowBand] * hopMs
        val lowStillRising = peakHop[lowBand] >= n - 2
        // Decay is only measurable when at least a few hops follow the peak.
        val decayHops = n - 1 - peakLevelHop
        val decayKnown = decayHops >= 3
        val decayBroad = (peakLevel - endLevel).coerceAtLeast(0f)
        val decayRate = if (decayKnown) decayBroad / (decayHops * hopSec) else 0f
        val lowDecayHops = n - 1 - peakHop[lowBand]
        val decayLow = (peakDb[lowBand] - endDb[lowBand]).coerceAtLeast(0f)
        val decayLowRate = if (lowDecayHops >= 3) decayLow / (lowDecayHops * hopSec) else 0f
        // Fraction of the low-band rise that has already decayed (kick over a held bass note).
        val lowDecayFraction = if (lowRise > 1f) decayLow / lowRise else 0f
        val flat = flatSum / n
        val centroid = centroidSum / n
        val crest = max(onsetCrestDb, nextCrestDb)
        val strengthRelative = peakLevel - loudnessReferenceDb
        val notMusic = 1f - 0.6f * musicContext
        // Very loud events (explosions over a score) are not explained away by the music.
        val notMusicUnlessLoud = 1f - 0.6f * musicContext * (1f - ramp(strengthRelative, 6f, 14f))
        val musical = max(musicContext, rhythm)
        val rhythmBoost = 0.8f + 0.4f * if (onBeat) 1f else 0.5f * rhythm
        val notSteps = 1f - 0.7f * stepRegularity
        val notSpeech = 1f - 0.8f * ramp(speech, 0.2f, 0.5f)

        scores.fill(0f)
        // Kick: sharp low-frequency thump that decays, at least partly (it may sit on a bass note).
        scores[SoundCategory.BASS_DRUM.ordinal] = ramp(low, 0.4f, 0.65f) * ramp(lowRise, 6f, 14f) *
            (1f - ramp(attackMs, 25f, 50f)) * max(ramp(decayLowRate, 20f, 60f), ramp(lowDecayFraction, 0.08f, 0.25f)) *
            (1f - 0.8f * ramp(highAll, 0.25f, 0.45f)) * (1f - 0.6f * ramp(flat, 0.5f, 0.75f)) * rhythmBoost * notSpeech
        // Snare: noisy mid/high crack with a body, decaying moderately.
        scores[SoundCategory.SNARE.ordinal] = ramp(highAll, 0.12f, 0.25f) * (1f - ramp(low, 0.35f, 0.6f)) *
            ramp(lowMid + mid + highAll, 0.6f, 0.85f) * (1f - ramp(attackMs, 15f, 40f)) * ramp(decayRate, 30f, 90f) *
            (0.7f + 0.3f * ramp(flat, 0.05f, 0.25f)) * rhythmBoost * (0.4f + 0.6f * musical) * notSpeech
        // Cymbal: bright, high-frequency dominated, in a musical context.
        scores[SoundCategory.CYMBAL.ordinal] = ramp(highAll, 0.55f, 0.8f) * ramp(high, 0.15f, 0.35f) *
            ramp(centroid, 3000f, 6000f) * (1f - ramp(low + lowMid, 0.15f, 0.35f)) * ramp(highRise - lowRise, 0f, 8f) *
            (0.4f + 0.6f * musical) * notSpeech
        // Explosion: loud, broadband, low-heavy and sustained (slow decay or a late peak).
        val sustained = max(1f - ramp(decayRate, 50f, 120f), ramp(attackMs, 30f, 60f))
        scores[SoundCategory.EXPLOSION.ordinal] = ramp(strengthRelative, 3f, 10f) * ramp(riseCount.toFloat(), 2.5f, 4.5f) *
            ramp(low, 0.2f, 0.4f) * ramp(highAll, 0.02f, 0.08f) * sustained * (1f - ramp(decayLowRate, 45f, 110f)) *
            (0.3f + 0.7f * ramp(flat, 0.05f, 0.2f)) * notMusicUnlessLoud * notSpeech
        // Gunshot: extreme crest, instant attack, broadband and very fast decay.
        scores[SoundCategory.GUNSHOT.ordinal] = ramp(crest, 5f, 10f) * (1f - ramp(attackMs, 6f, 16f)) *
            ramp(riseCount.toFloat(), 2.5f, 4.5f) * ramp(mid + highAll, 0.35f, 0.6f) *
            (if (decayKnown) ramp(decayRate, 80f, 180f) else 0.5f) * (1f - ramp(low, 0.4f, 0.6f)) * notMusic
        // Punch: short, dull, very fast-decaying hit.
        scores[SoundCategory.PUNCH.ordinal] = ramp(low + lowMid + mid, 0.7f, 0.9f) * (1f - ramp(highAll, 0.15f, 0.35f)) *
            ramp(decayRate, 120f, 220f) * (1f - ramp(attackMs, 15f, 35f)) * ramp(max(lowRise, lowMidRise), 8f, 16f) *
            notMusic * (1f - 0.5f * rhythm) * notSteps * notSpeech
        // Impact / collision: violent and broadband like an explosion, but decaying faster.
        scores[SoundCategory.IMPACT.ordinal] = ramp(strengthRelative, 0f, 8f) * ramp(riseCount.toFloat(), 2.5f, 4.5f) *
            ramp(low + lowMid, 0.3f, 0.55f) * ramp(highAll, 0.03f, 0.12f) *
            (if (decayKnown) band(decayRate, 70f, 220f, 40f) else 0.4f) * (1f - ramp(flat, 0.6f, 0.85f)) *
            notMusicUnlessLoud * notSteps * notSpeech
        // Thunder: deep frequencies rise far more than anything else, slowly, and keep rumbling.
        scores[SoundCategory.THUNDER.ordinal] = ramp(low, 0.5f, 0.7f) * ramp(subRise, 6f, 14f) *
            ramp(lowRise - highRise, 4f, 12f) * max(ramp(lowAttackMs, 30f, 70f), if (lowStillRising) 1f else 0f) *
            (1f - ramp(decayLowRate, 25f, 70f)) * notMusic * notSpeech
        // Door slam: dull low thud with a rattle and room tail, decaying moderately.
        scores[SoundCategory.DOOR_SLAM.ordinal] = ramp(low + lowMid, 0.45f, 0.75f) * (1f - ramp(attackMs, 10f, 30f)) *
            (if (decayKnown) band(decayRate, 50f, 140f, 40f) else 0.5f) * (1f - ramp(highAll, 0.12f, 0.25f)) *
            notMusic * (1f - rhythm) * notSteps * notSpeech
        // Footsteps: soft dull thuds at a regular walking/running pace.
        scores[SoundCategory.FOOTSTEPS.ordinal] = ramp(low + lowMid, 0.35f, 0.65f) * (1f - ramp(shares[0], 0.25f, 0.45f)) *
            (if (decayKnown) ramp(decayRate, 60f, 160f) else 0.5f) * (1f - ramp(highAll, 0.3f, 0.5f)) *
            ramp(stepRegularity, 0.3f, 0.7f) * (1f - 0.8f * musicContext) * (1f - ramp(tonality, 0.2f, 0.4f)) * notSpeech
        // Bass drop: big, sustained low-frequency surge after a lull in the low end.
        scores[SoundCategory.BASS_DROP.ordinal] = ramp(lowRise, 10f, 20f) * ramp(low, 0.5f, 0.75f) *
            (1f - ramp(lowDecayFraction, 0.08f, 0.2f)) * ramp(lowQuietSec, 0.4f, 1f)
        scores[SoundCategory.PERCUSSION.ordinal] = 0.55f * ramp(maxRise, 8f, 16f) * (1f - ramp(attackMs, 15f, 40f)) *
            (if (decayKnown) ramp(decayRate, 50f, 150f) else 0.5f) * (0.6f + 0.4f * rhythm) * notSpeech

        var best: SoundCategory? = null
        var bestScore = 0f
        for (category in TRANSIENT_CATEGORIES) {
            val s = scores[category.ordinal] * semanticPriors[category.ordinal]
            if (s >= config.acceptanceThreshold(category) && s > bestScore) {
                bestScore = s
                best = category
            }
        }

        // During strong engine sound, moderate onsets are combustion/shift noise: leave them to
        // the engine tracker unless clearly much louder than the programme.
        if (engine > 0.5f && strengthRelative < 6f && best != SoundCategory.EXPLOSION && best != SoundCategory.IMPACT) return
        // Unrecognised onsets inside sustained engine sound are engine dynamics (blips, launch).
        if (engine > 0.5f && best == null) return

        if (best == null) {
            best = when {
                speech > 0.35f && low < 0.6f -> SoundCategory.DIALOGUE
                onBeat && rhythm > 0.45f && musicContext > 0.35f && machinery < 0.3f && strengthRelative > -8f -> SoundCategory.BEAT
                else -> SoundCategory.GENERAL
            }
            bestScore = when (best) {
                SoundCategory.BEAT -> 0.4f + 0.4f * rhythm
                SoundCategory.DIALOGUE -> speech
                else -> 0.3f
            }
        }

        // Masking: minor onsets inside the tail of a big event (explosion debris, rumble
        // fluctuations) are part of that event, not new ones.
        if (onsetTimeUs < maskUntilUs && best != SoundCategory.EXPLOSION) {
            var basePower = 0f
            for (b in 0 until BANDS) if (config.bandEnabled[b]) basePower += Db.toPower(baseDb[b])
            val broadbandRise = peakLevel - Db.fromPower(basePower)
            val elapsedSec = (onsetTimeUs - maskStartUs) / 1_000_000f
            val maskLevel = maskPeakDb - maskDecayDbPerSecond * elapsedSec
            if (broadbandRise < MASK_RISE_DB || peakLevel < maskLevel + MASK_MARGIN_DB) return
        }

        val absoluteFactor = ramp(peakLevel, config.minAmplitudeDb, config.minAmplitudeDb + 24f)
        var strength = Fuzzy.smoothstep(strengthRelative, -18f, 12f)
        strength = max(strength, 0.6f * ramp(maxRise, 10f, 30f))
        strength = (strength * (0.35f + 0.65f * absoluteFactor)).coerceIn(0f, 1f)
        if (best == SoundCategory.GENERAL && strength < 0.2f) return

        val sharpness = (0.6f * (1f - ramp(attackMs, 2f, 40f)) + 0.4f * ramp(centroid, 500f, 5000f)).coerceIn(0f, 1f)
        val durationMs = if (decayKnown) (35f / max(decayRate, 10f) * 1000f).coerceIn(30f, 3000f) else 600f
        var dominant = 0
        for (b in 1 until BANDS) if (shares[b] > shares[dominant]) dominant = b

        lastAttackMs = attackMs
        lastDecayDbPerSecond = decayRate
        lastCategory = best
        lastEventTimeUs = onsetTimeUs
        if (best in MASKING_CATEGORIES) {
            maskStartUs = onsetTimeUs
            maskPeakDb = peakLevel
            maskDecayDbPerSecond = max(if (decayKnown) decayRate else 0f, 15f)
            val maskMs = if (best == SoundCategory.EXPLOSION || best == SoundCategory.THUNDER) max(durationMs, 2000f) else max(durationMs, 600f)
            maskUntilUs = onsetTimeUs + (maskMs * 1000f).toLong()
        }
        sink.onSoundEvent(
            SoundEvent(
                category = best,
                timeUs = onsetTimeUs,
                strength = strength,
                confidence = bestScore.coerceIn(0f, 1f),
                sharpness = sharpness,
                attackMs = attackMs,
                decayDbPerSecond = decayRate,
                durationHintMs = durationMs,
                dominantBand = BandId.entries[dominant],
                bandShares = shares.copyOf(),
            ),
        )
    }

    fun reset() {
        active = false
        hops = 0
        lastCategory = null
        lastEventTimeUs = Long.MIN_VALUE
        maskUntilUs = Long.MIN_VALUE
    }

    /** Whether [timeUs] lies inside the tail of a recent big event. */
    fun isMasked(timeUs: Long): Boolean = timeUs < maskUntilUs

    /** Context snapshot taken at the onset. */
    class Context {
        var loudnessReferenceDb = -30f
        var onBeat = false
        var rhythm = 0f
        var engine = 0f
        var speech = 0f
        var musicContext = 0f
        var stepRegularity = 0f

        /** How long the low end (sub-bass + bass) was quiet before the onset, in seconds. */
        var lowQuietSec = 0f

        /** Current machinery texture level. */
        var machinery = 0f

        /** Smoothed harmonicity of the programme (tonal music vs. unpitched noise). */
        var tonality = 0f
    }

    companion object {
        private const val BANDS = BandId.COUNT
        private const val MIN_DECISION_HOPS = 3
        private const val MAX_HOPS = 24
        private const val TAIL_RISE_DB = 4f
        private const val JUMP_DB = 3f
        private const val MASK_MARGIN_DB = 3f
        private const val MASK_RISE_DB = 6f

        /** Events with long tails that mask minor onsets inside them. */
        private val MASKING_CATEGORIES = setOf(
            SoundCategory.EXPLOSION, SoundCategory.THUNDER, SoundCategory.IMPACT, SoundCategory.BASS_DROP,
        )

        /** Categories decided by this classifier (engine events come from [EngineTracker]). */
        val TRANSIENT_CATEGORIES = listOf(
            SoundCategory.EXPLOSION, SoundCategory.GUNSHOT, SoundCategory.IMPACT, SoundCategory.PUNCH,
            SoundCategory.THUNDER, SoundCategory.DOOR_SLAM, SoundCategory.BASS_DROP, SoundCategory.BASS_DRUM,
            SoundCategory.SNARE, SoundCategory.CYMBAL, SoundCategory.FOOTSTEPS, SoundCategory.PERCUSSION,
        )
    }
}
