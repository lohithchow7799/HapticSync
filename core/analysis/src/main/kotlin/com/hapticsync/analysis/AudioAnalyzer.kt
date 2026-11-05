package com.hapticsync.analysis

import com.hapticsync.dsp.BandFilterBank
import com.hapticsync.dsp.Db
import com.hapticsync.dsp.EnvelopeFollower
import com.hapticsync.dsp.FloatHistory
import com.hapticsync.dsp.NoiseFloorTracker
import com.hapticsync.dsp.PitchTracker
import com.hapticsync.dsp.onePoleCoefficient
import com.hapticsync.model.AnalysisQuality
import com.hapticsync.model.BandId
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The real-time audio analysis pipeline. Feed it consecutive hops of mono ("mid") and stereo
 * difference ("side") samples together with the media timestamp of each hop's first sample;
 * it returns the hop's [AnalysisFrame] and reports classified [SoundEvent]s to [sink].
 *
 * Per hop it performs:
 * 1. time-domain features (RMS, peak, crest factor, zero-crossing rate, clipping, stereo pan),
 * 2. band energies from an IIR filter bank with per-band envelope tracking and noise floors,
 * 3. adaptive loudness normalisation and gating,
 * 4. FFT features (centroid, flatness, roll-off, flux, visualisation spectrum),
 * 5. pitch analysis (f0, harmonicity, semitone spectrum),
 * 6. onset detection, with sample-accurate onset refinement on the raw waveform,
 * 7. rhythm, speech, engine and texture tracking, and
 * 8. transient classification after a short decision window.
 *
 * The steady-state loop performs no allocation except for emitted events. Not thread-safe: use
 * from a single analysis thread.
 */
class AudioAnalyzer(
    profile: HapticProfile,
    sampleRate: Int,
    private val sink: SoundEventSink,
) {
    var config: AnalysisConfig = AnalysisConfig.from(profile, sampleRate)
        private set

    /** Output of the latest hop (overwritten by every [process] call). */
    val frame = AnalysisFrame()

    /**
     * Load level set by the engine in [AnalysisQuality.AUTO] mode: 0 = full analysis,
     * 1 = spectral/pitch analysis every second hop, 2 = every fourth hop.
     */
    var loadLevel: Int = 0
        set(value) { field = value.coerceIn(0, 2) }

    /**
     * Upper bound for the classification window, derived by the engine from how far ahead of
     * playback the analysis currently runs.
     */
    var maxDecisionHops: Int = Int.MAX_VALUE

    private val bank = BandFilterBank(BandId.COUNT)
    private var spectral = SpectralAnalyzer(config)
    private var pitch = PitchTracker(sampleRate)
    private var onset = OnsetDetector(config)
    private var rhythm = RhythmTracker(config.hopRateHz)
    private var speech = SpeechDetector(config.hopRateHz)
    private val lastEvent = LastEventRecorder(sink)
    private var engine = EngineTracker(config.hopRateHz, ::onEngineEvent)
    private var textures = TextureDetector(config.hopRateHz)
    private var classifier = EventClassifier(config, lastEvent)

    private var ring = FloatArray(config.windowSize)
    private var linear = FloatArray(config.windowSize)
    private var ringWrite = 0
    private var ringFilled = 0

    private val smoothedEnergy = FloatArray(BandId.COUNT)
    private val fastEnergy = FloatArray(BandId.COUNT)
    private val attackCoef = FloatArray(BandId.COUNT)
    private val releaseCoef = FloatArray(BandId.COUNT)
    private val fastReleaseCoef = FloatArray(BandId.COUNT)

    /** Fast-release band levels used for onset detection and classification (dBFS). */
    private val fastDb = FloatArray(BandId.COUNT) { Db.FLOOR_DB }
    private var floors = Array(BandId.COUNT) { NoiseFloorTracker(6000f, 400f, config.hopRateHz) }
    private val active = BooleanArray(BandId.COUNT)
    private val bandHistory = Array(HISTORY) { FloatArray(BandId.COUNT) { Db.FLOOR_DB } }
    private val levelHistory = FloatArray(HISTORY) { Db.FLOOR_DB }
    private val crestHistory = FloatArray(HISTORY)
    private val flatnessHistory = FloatArray(HISTORY)
    private val centroidHistory = FloatArray(HISTORY)
    private val lowLevels = FloatHistory(24)

    private var loudnessReference = Float.NaN
    private var riseCoef = onePoleCoefficient(4000f, config.hopRateHz)
    private var fallCoef = onePoleCoefficient(2500f, config.hopRateHz)
    private var musicFollower = EnvelopeFollower(1000f, 2000f, config.hopRateHz)
    private val context = EventClassifier.Context()
    private var hopIndex = 0L
    private var lowQuietHops = 0
    private var tonality = 0f
    private var tonalityCoef = onePoleCoefficient(1500f, config.hopRateHz)
    private var thunder = ThunderDetector(config.hopRateHz)
    private var recent = FloatArray(RECENT_HOPS * config.hopSize)
    private var recentWrite = 0
    private var recentFilled = 0
    private var envelopeBlocks = FloatArray(recent.size / ENVELOPE_BLOCK + 1)

    init {
        configureFilters()
    }

    /** Applies a changed profile. Structural changes (sample rate, hop, window) rebuild state. */
    fun updateProfile(profile: HapticProfile, sampleRate: Int = config.sampleRate) {
        val next = AnalysisConfig.from(profile, sampleRate)
        val structural = !next.sameStructure(config)
        val bands = !next.sameBands(config)
        config = next
        if (structural) {
            rebuild()
        } else {
            onset.setConfig(next)
            classifier.setConfig(next)
            if (bands) configureFilters() else updateBandTimes()
        }
    }

    private fun rebuild() {
        spectral = SpectralAnalyzer(config)
        pitch = PitchTracker(config.sampleRate)
        onset = OnsetDetector(config)
        rhythm = RhythmTracker(config.hopRateHz)
        speech = SpeechDetector(config.hopRateHz)
        engine = EngineTracker(config.hopRateHz, ::onEngineEvent)
        textures = TextureDetector(config.hopRateHz)
        classifier = EventClassifier(config, lastEvent)
        ring = FloatArray(config.windowSize)
        linear = FloatArray(config.windowSize)
        recent = FloatArray(RECENT_HOPS * config.hopSize)
        envelopeBlocks = FloatArray(recent.size / ENVELOPE_BLOCK + 1)
        floors = Array(BandId.COUNT) { NoiseFloorTracker(6000f, 400f, config.hopRateHz) }
        riseCoef = onePoleCoefficient(4000f, config.hopRateHz)
        fallCoef = onePoleCoefficient(2500f, config.hopRateHz)
        musicFollower = EnvelopeFollower(1000f, 2000f, config.hopRateHz)
        tonalityCoef = onePoleCoefficient(1500f, config.hopRateHz)
        thunder = ThunderDetector(config.hopRateHz)
        configureFilters()
        reset()
    }

    private fun configureFilters() {
        bank.configure(config.sampleRate, config.bandLowHz, config.bandHighHz)
        updateBandTimes()
    }

    private fun updateBandTimes() {
        for (b in 0 until BandId.COUNT) {
            // Integrate low bands over at least ~0.6 of a period of their lower edge.
            val integrationMs = max(4f, 600f / max(config.bandLowHz[b], 1f))
            attackCoef[b] = onePoleCoefficient(integrationMs, config.hopRateHz)
            releaseCoef[b] = onePoleCoefficient(max(config.bandReleaseMs[b], integrationMs), config.hopRateHz)
            // Onsets and decay measurements need envelopes that fall quickly, independent of the
            // user's smoothing setting (which shapes visualisation and continuous haptics).
            fastReleaseCoef[b] = onePoleCoefficient(max(FAST_RELEASE_MS, integrationMs), config.hopRateHz)
        }
    }

    /** Optional semantic evidence: per-category multipliers in [0.5, 1.5] (1 = neutral). */
    fun setSemanticPriors(priors: FloatArray?) {
        for (i in 0 until SoundCategory.COUNT) {
            classifier.semanticPriors[i] = priors?.getOrNull(i)?.coerceIn(0.5f, 1.5f) ?: 1f
        }
    }

    /** Clears all signal state, e.g. after a seek. The configuration is kept. */
    fun reset() {
        classifier.reset()
        bank.reset()
        spectral.reset()
        pitch.reset()
        onset.reset()
        rhythm.reset()
        speech.reset()
        engine.reset()
        textures.reset()
        ring.fill(0f)
        ringWrite = 0
        ringFilled = 0
        recent.fill(0f)
        recentWrite = 0
        recentFilled = 0
        smoothedEnergy.fill(0f)
        fastEnergy.fill(0f)
        fastDb.fill(Db.FLOOR_DB)
        for (f in floors) f.reset()
        for (h in bandHistory) h.fill(Db.FLOOR_DB)
        levelHistory.fill(Db.FLOOR_DB)
        crestHistory.fill(0f)
        flatnessHistory.fill(0f)
        centroidHistory.fill(0f)
        lowLevels.clear()
        musicFollower.reset()
        loudnessReference = Float.NaN
        hopIndex = 0
        lowQuietHops = 0
        tonality = 0f
        thunder.reset()
    }

    /** Evidence scores of the most recently classified onset (diagnostics and tests). */
    internal val lastScores: FloatArray get() = classifier.lastScores

    /** Internal trackers, exposed to tests for diagnostics. */
    internal val speechDetector: SpeechDetector get() = speech
    internal val engineTracker: EngineTracker get() = engine

    /**
     * Analyses one hop of exactly `config.hopSize` samples starting at [offset].
     *
     * @param timeUs media time of the first sample of the hop.
     */
    fun process(mid: FloatArray, side: FloatArray, offset: Int, timeUs: Long): AnalysisFrame {
        val c = config
        val hop = c.hopSize
        val f = frame
        f.timeUs = timeUs
        f.durationUs = c.hopDurationUs.toLong()

        // 1. Time-domain features of the hop.
        var peak = 0f
        var sumMid = 0.0
        var sumSide = 0.0
        var cross = 0.0
        var clipped = 0
        for (i in offset until offset + hop) {
            val m = mid[i]
            val s = side[i]
            val a = abs(m)
            if (a > peak) peak = a
            if (a >= 0.985f) clipped++
            sumMid += m * m
            sumSide += s * s
            cross += m * s
            ring[ringWrite] = m
            ringWrite++
            if (ringWrite == ring.size) ringWrite = 0
            recent[recentWrite] = m
            recentWrite++
            if (recentWrite == recent.size) recentWrite = 0
        }
        recentFilled = minOf(recent.size, recentFilled + hop)
        ringFilled = minOf(ring.size, ringFilled + hop)
        val w = ring.size
        var sumWindow = 0.0
        var crossings = 0
        var previous = ring[ringWrite]
        for (i in 0 until w) {
            val x = ring[(ringWrite + i) % w]
            linear[i] = x
            sumWindow += x * x
            if ((x >= 0f) != (previous >= 0f)) crossings++
            previous = x
        }
        val rms = sqrt(sumWindow / w).toFloat()
        val hopRms = sqrt(sumMid / hop).toFloat()
        f.rms = rms
        f.rmsDb = Db.fromAmplitude(rms)
        f.peak = peak
        f.crestDb = if (hopRms > 1e-6f) 20f * log10(max(peak, 1e-6f) / hopRms) else 0f
        f.zeroCrossingRate = crossings.toFloat() / w
        f.clipped = clipped > 2
        f.silent = f.rmsDb < c.minAmplitudeDb
        val stereoEnergy = sumMid + sumSide
        f.pan = if (stereoEnergy > 1e-9) (2.0 * cross / stereoEnergy).toFloat().coerceIn(-1f, 1f) else 0f

        // 2. Band energies with envelope tracking.
        bank.process(mid, offset, hop)
        bank.endBlock()
        var hopEnergy = 0f
        var smoothedTotal = 0f
        for (b in 0 until BandId.COUNT) {
            val e = bank.bandEnergy[b]
            val prev = smoothedEnergy[b]
            smoothedEnergy[b] = prev + (e - prev) * if (e > prev) attackCoef[b] else releaseCoef[b]
            val prevFast = fastEnergy[b]
            fastEnergy[b] = prevFast + (e - prevFast) * if (e > prevFast) attackCoef[b] else fastReleaseCoef[b]
            f.bandDb[b] = if (bank.isAvailable(b)) Db.fromPower(smoothedEnergy[b]) else Db.FLOOR_DB
            fastDb[b] = if (bank.isAvailable(b)) Db.fromPower(fastEnergy[b]) else Db.FLOOR_DB
            if (c.bandEnabled[b]) {
                hopEnergy += e
                smoothedTotal += smoothedEnergy[b]
            }
        }
        val hopLevelDb = Db.fromPower(hopEnergy)

        // 3. Loudness reference (adaptive normalisation).
        if (!f.silent) {
            val level = f.rmsDb.coerceIn(-60f, 0f)
            loudnessReference = if (loudnessReference.isNaN()) {
                level.coerceIn(-45f, -12f)
            } else {
                loudnessReference + (level - loudnessReference) * if (level > loudnessReference) riseCoef else fallCoef
            }
        }
        val reference = if (c.adaptiveNormalization && !loudnessReference.isNaN()) loudnessReference else FIXED_REFERENCE_DB
        f.loudnessReferenceDb = reference
        f.relativeLevelDb = f.rmsDb - reference

        // Band floors, gating, shares and normalised levels.
        var dominant = 0
        for (b in 0 until BandId.COUNT) {
            val level = f.bandDb[b]
            val floor = floors[b].update(level)
            val excess = (level - floor).coerceAtLeast(0f)
            f.bandExcessDb[b] = excess
            active[b] = c.bandEnabled[b] && bank.isAvailable(b) && !f.silent &&
                fastDb[b] >= c.bandThresholdDb[b] && (!c.noiseGate || fastDb[b] - floor >= NOISE_GATE_DB)
            f.bandShare[b] = if (c.bandEnabled[b] && smoothedTotal > 0f) smoothedEnergy[b] / smoothedTotal else 0f
            if (f.bandShare[b] > f.bandShare[dominant]) dominant = b
            f.bandLevel[b] = if (active[b]) Fuzzy.ramp(level, reference - 40f, reference + 6f) else 0f
        }
        f.dominantBand = dominant
        val lowShare = f.bandShare[0] + f.bandShare[1]
        val lowDb = Db.fromPower(smoothedEnergy[0] + smoothedEnergy[1])
        lowLevels.push(lowDb)
        // The low end counts as quiet while it stays well below the programme loudness. Measured
        // two hops back so that an onset's own energy (confirmed one hop late) cannot end the lull.
        if (lowLevels.size > 2) lowQuietHops = if (lowLevels[2] < reference - 15f) lowQuietHops + 1 else 0
        f.lowFrequencyTrendDbPerSecond =
            if (lowLevels.size >= 20) (lowLevels[0] - lowLevels[19]) / (19 * c.hopDurationUs.toFloat() / 1e6f) else 0f

        // 4–5. Spectral and pitch analysis (interval depends on quality / load).
        val interval = analysisInterval()
        val spectralNow = hopIndex % interval == 0L && ringFilled >= w
        if (spectralNow) spectral.analyze(linear)
        f.centroidHz = spectral.centroidHz
        f.flatness = spectral.flatness
        f.rolloffHz = spectral.rolloffHz
        f.flux = if (spectralNow) spectral.flux * interval else 0f
        pitch.push(mid, offset, hop)
        val pitchNow = hopIndex % interval == 0L
        if (pitchNow) pitch.analyze()
        f.f0Hz = pitch.f0Hz
        f.harmonicity = pitch.harmonicity
        for (i in f.spectrum.indices) f.spectrum[i] = Fuzzy.ramp(spectral.barsDb[i], reference - 50f, reference + 6f)

        // 6. Onsets.
        val onsetFound = onset.update(fastDb, active, f.flux, f.silent)
        f.onsetStrength = onset.value
        f.onsetThreshold = onset.threshold
        f.transientIntensity = Fuzzy.ramp(onset.value / max(onset.threshold, 1e-3f), 0.8f, 3f)
        f.onsetDetected = onsetFound

        // 7. Context trackers.
        rhythm.onHop(if (onsetFound) onset.onsetValue else 0f)
        // Music: a regular pulse, ideally with tonal content (footsteps are regular but unpitched).
        tonality += (f.harmonicity - tonality) * tonalityCoef
        val music = musicFollower.process(
            Fuzzy.ramp(rhythm.confidence, 0.3f, 0.6f) * Fuzzy.ramp(rhythm.onsetRate, 0.8f, 2.5f) *
                (0.4f + 0.6f * Fuzzy.ramp(tonality, 0.15f, 0.4f)),
        )
        f.rhythmConfidence = rhythm.confidence
        f.beatPeriodSec = rhythm.periodHops * c.hopDurationUs.toFloat() / 1e6f
        val speechBandDb = Db.fromPower(0.5f * fastEnergy[1] + fastEnergy[2] + fastEnergy[3] + 0.5f * fastEnergy[4])
        val speechShare = 0.5f * f.bandShare[1] + f.bandShare[2] + f.bandShare[3] + 0.7f * f.bandShare[4]
        speech.update(speechBandDb, speechShare, f.bandShare[0], f.harmonicity, f.f0Hz, engine.likelihood, rhythm.confidence, f.silent)
        engine.update(timeUs, pitch, pitchNow, f.rmsDb, reference, lowShare, f.pan, speech.likelihood, music, f.silent)

        // 8. Transient classification.
        classifier.maxDecisionHops = maxDecisionHops
        classifier.onHop(fastDb, f, hopLevelDb)
        if (onsetFound) {
            context.loudnessReferenceDb = reference
            context.onBeat = rhythm.isOnBeat(hopIndex - 1)
            context.rhythm = rhythm.confidence
            context.engine = engine.likelihood
            context.speech = speech.likelihood
            context.musicContext = music
            context.stepRegularity = rhythm.intervalRegularity(0.25f, 0.9f)
            context.lowQuietSec = lowQuietHops / c.hopRateHz
            context.machinery = textures.level(SoundCategory.MACHINERY)
            context.tonality = tonality
            classifier.onOnset(
                timeUs = refineOnsetTime(timeUs),
                baselineDb = bandHistory[(hopIndex - 3).mod(HISTORY)],
                onsetHopDb = bandHistory[(hopIndex - 1).mod(HISTORY)],
                onsetHopLevelDb = levelHistory[(hopIndex - 1).mod(HISTORY)],
                onsetCrestDb = crestHistory[(hopIndex - 1).mod(HISTORY)],
                onsetFlatness = flatnessHistory[(hopIndex - 1).mod(HISTORY)],
                onsetCentroidHz = centroidHistory[(hopIndex - 1).mod(HISTORY)],
                context = context,
            )
            classifier.onHop(fastDb, f, hopLevelDb)
        }
        f.lastAttackMs = classifier.lastAttackMs
        f.lastDecayDbPerSecond = classifier.lastDecayDbPerSecond

        textures.update(f, engine, speech.likelihood, music, rhythm.onsetRate, Fuzzy.ramp(rhythm.confidence, 0.3f, 0.6f))
        val recentLowTransient = timeUs - lastEvent.timeUs < 600_000L && lastEvent.lowShare > 0.3f
        if (onsetFound) thunder.onOnset(timeUs)
        val thunderStart = thunder.update(
            timeUs = timeUs,
            subDb = fastDb[0],
            lowDb = Db.fromPower(fastEnergy[0] + fastEnergy[1]),
            lowMidDb = fastDb[2],
            highDb = Db.fromPower(fastEnergy[4] + fastEnergy[5]),
            loudnessReferenceDb = reference,
            silent = f.silent,
            blocked = engine.likelihood > 0.3f || music > 0.3f || speech.likelihood > 0.3f ||
                classifier.isMasked(timeUs) || recentLowTransient,
        )
        if (thunderStart != Long.MIN_VALUE && (config.bandEnabled[0] || config.bandEnabled[1])) {
            emitThunder(thunderStart, f)
        }
        fillContinuous(f)
        f.speechLikelihood = speech.likelihood
        f.engineLikelihood = engine.likelihood
        f.rpmNormalized = engine.rpmNormalized
        f.pitchVelocity = engine.velocity
        f.dominantCategory = dominantCategory(f, timeUs)

        val slot = (hopIndex % HISTORY).toInt()
        fastDb.copyInto(bandHistory[slot])
        levelHistory[slot] = hopLevelDb
        crestHistory[slot] = f.crestDb
        flatnessHistory[slot] = f.flatness
        centroidHistory[slot] = f.centroidHz
        hopIndex++
        return f
    }

    private fun analysisInterval(): Int = when (config.quality) {
        AnalysisQuality.HIGH -> 1
        AnalysisQuality.BALANCED -> 2
        AnalysisQuality.LOW -> 4
        AnalysisQuality.AUTO -> when (loadLevel) { 0 -> 1; 1 -> 2; else -> 4 }
    }

    /**
     * Finds the start of the transient on the raw waveform: locates the envelope peak within the
     * last three hops before the current one, then scans backwards (up to [RECENT_HOPS] hops)
     * to the earliest point where the envelope was still above a quarter of that peak. This
     * removes the filter-bank group delay and the hop quantisation from onset timestamps.
     */
    private fun refineOnsetTime(currentHopTimeUs: Long): Long {
        val c = config
        val hop = c.hopSize
        val total = recent.size
        if (recentFilled < total) return currentHopTimeUs - c.hopDurationUs.toLong()
        // Unrolled index i (0 = oldest) maps to recent[(recentWrite + i) % total].
        val blocks = total / ENVELOPE_BLOCK
        for (blk in 0 until blocks) {
            var m = 0f
            val base = blk * ENVELOPE_BLOCK
            for (k in base until base + ENVELOPE_BLOCK) m = max(m, abs(recent[(recentWrite + k) % total]))
            envelopeBlocks[blk] = m
        }
        // Search up to and including the block that holds the last sample of the onset hop (a
        // transient may start in the final samples of that hop, inside a straddling block).
        val searchEndBlock = ((total - hop - 1) / ENVELOPE_BLOCK).coerceAtMost(blocks - 1)
        val searchStartBlock = ((total - 4 * hop) / ENVELOPE_BLOCK).coerceAtLeast(0)
        var peakBlock = searchStartBlock
        for (blk in searchStartBlock..searchEndBlock) if (envelopeBlocks[blk] > envelopeBlocks[peakBlock]) peakBlock = blk
        val peakValue = envelopeBlocks[peakBlock]
        if (peakValue <= 1e-6f) return currentHopTimeUs - c.hopDurationUs.toLong()
        var start = peakBlock
        while (start > 0 && envelopeBlocks[start - 1] >= 0.25f * peakValue) start--
        val sampleIndex = start * ENVELOPE_BLOCK
        val firstSampleOfCurrentHop = total - hop
        return currentHopTimeUs + ((sampleIndex - firstSampleOfCurrentHop) * 1_000_000L) / c.sampleRate
    }

    private fun fillContinuous(f: AnalysisFrame) {
        f.continuous.fill(0f)
        f.continuous[SoundCategory.ENGINE_IDLE.ordinal] = engine.idleLevel
        f.continuous[SoundCategory.ENGINE_REV.ordinal] = engine.revLevel
        f.continuous[SoundCategory.ACCELERATION.ordinal] = engine.accelerationLevel
        f.continuous[SoundCategory.DECELERATION.ordinal] = engine.decelerationLevel
        f.continuous[SoundCategory.ENGINE_RUMBLE.ordinal] = engine.rumbleLevel
        for (category in TEXTURE_CATEGORIES) f.continuous[category.ordinal] = textures.level(category)
    }

    private fun dominantCategory(f: AnalysisFrame, timeUs: Long): Int {
        var best = -1
        var bestValue = 0.15f
        for (category in SoundCategory.entries) {
            val v = f.continuous[category.ordinal] * (0.6f + 0.04f * category.defaultPriority)
            if (v > bestValue) { bestValue = v; best = category.ordinal }
        }
        val event = lastEvent.category
        if (event != null) {
            val ageUs = timeUs - lastEvent.timeUs
            if (ageUs in 0..RECENT_EVENT_US) {
                val v = 1.1f - ageUs.toFloat() / RECENT_EVENT_US
                if (v > bestValue) best = event.ordinal
            }
        }
        return best
    }

    private fun onEngineEvent(
        type: EngineTracker.EngineEventType,
        timeUs: Long,
        strength: Float,
        confidence: Float,
        latenessToleranceUs: Long,
    ) {
        val category = when (type) {
            EngineTracker.EngineEventType.GEAR_SHIFT -> SoundCategory.GEAR_SHIFT
            EngineTracker.EngineEventType.VEHICLE_PASSING -> SoundCategory.VEHICLE_PASSING
        }
        if (confidence < config.acceptanceThreshold(category)) return
        val shares = frame.bandShare.copyOf()
        var dominant = 0
        for (b in 1 until BandId.COUNT) if (shares[b] > shares[dominant]) dominant = b
        lastEvent.onSoundEvent(
            SoundEvent(
                category = category,
                timeUs = timeUs,
                strength = strength,
                confidence = confidence,
                sharpness = if (category == SoundCategory.GEAR_SHIFT) 0.85f else 0.3f,
                attackMs = if (category == SoundCategory.GEAR_SHIFT) 5f else 120f,
                decayDbPerSecond = if (category == SoundCategory.GEAR_SHIFT) 300f else 40f,
                durationHintMs = if (category == SoundCategory.GEAR_SHIFT) 40f else 450f,
                dominantBand = BandId.entries[dominant],
                bandShares = shares,
                latenessToleranceUs = latenessToleranceUs,
            ),
        )
    }

    private fun emitThunder(startUs: Long, f: AnalysisFrame) {
        val shares = f.bandShare.copyOf()
        lastEvent.onSoundEvent(
            SoundEvent(
                category = SoundCategory.THUNDER,
                timeUs = startUs,
                strength = (0.4f + 0.6f * Fuzzy.ramp(f.relativeLevelDb, -10f, 6f)).coerceIn(0f, 1f),
                confidence = 0.7f,
                sharpness = 0.15f,
                attackMs = 250f,
                decayDbPerSecond = 15f,
                durationHintMs = 1800f,
                dominantBand = if (shares[0] >= shares[1]) BandId.SUB_BASS else BandId.BASS,
                bandShares = shares,
                latenessToleranceUs = ThunderDetector.LATENESS_TOLERANCE_US,
            ),
        )
    }

    /** Remembers the most recent event (for the "current sound" indicator) and forwards it. */
    private class LastEventRecorder(private val downstream: SoundEventSink) : SoundEventSink {
        @Volatile var category: SoundCategory? = null
        @Volatile var timeUs: Long = Long.MIN_VALUE
        @Volatile var lowShare: Float = 0f

        override fun onSoundEvent(event: SoundEvent) {
            if (event.category != SoundCategory.GENERAL && event.category != SoundCategory.DIALOGUE) {
                category = event.category
                timeUs = event.timeUs
                lowShare = event.lowFrequencyShare
            }
            downstream.onSoundEvent(event)
        }
    }

    companion object {
        private const val HISTORY = 4
        private const val ENVELOPE_BLOCK = 32
        private const val RECENT_HOPS = 6
        private const val FIXED_REFERENCE_DB = -20f
        private const val NOISE_GATE_DB = 3f
        private const val FAST_RELEASE_MS = 20f
        private const val RECENT_EVENT_US = 400_000L

        private val TEXTURE_CATEGORIES = listOf(
            SoundCategory.CINEMATIC_RUMBLE, SoundCategory.SUSTAINED_BASS, SoundCategory.RAIN,
            SoundCategory.WIND, SoundCategory.MACHINERY, SoundCategory.DIALOGUE, SoundCategory.GENERAL,
        )
    }
}
