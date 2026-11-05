package com.hapticsync.analysis

import com.hapticsync.dsp.EnvelopeFollower
import com.hapticsync.dsp.FloatHistory
import com.hapticsync.dsp.PitchTracker
import com.hapticsync.dsp.onePoleCoefficient
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tracks engine-like sounds: sustained, harmonic, gliding tones.
 *
 * **Pitch velocity.** An engine's harmonic comb moves as a whole when its speed changes, which
 * on a semitone axis is a pure translation. The tracker cross-correlates the current semitone
 * spectrum with the one [SHIFT_LAG] hops earlier; the best shift divided by the lag gives the
 * pitch velocity in semitones per second without octave ambiguity. When the comb is not stable
 * enough, the HPS fundamental is used instead.
 *
 * **States** (continuous levels in [0, 1]): idle (stable, low speed), revving (fast pitch
 * changes), acceleration (sustained rise), deceleration (sustained fall) and rumble (heavy or
 * sustained engine energy).
 *
 * **Events**:
 * - *Gear shift*: an abrupt pitch drop that **stops** within ~180 ms (an up-shift during
 *   acceleration) or an abrupt rise during deceleration (a down-shift blip). The fall after a rev
 *   blip is not a gear shift: it keeps falling.
 * - *Vehicle passing*: a fast pitch drop (Doppler) accompanied by a loudness swell or a stereo
 *   sweep.
 *
 * Whether a harmonic glide really is an engine is inferred, not known: music, speech and mains
 * hum are explicitly penalised.
 */
internal class EngineTracker(private val hopRateHz: Float, private val listener: Listener) {

    fun interface Listener {
        fun onEngineEvent(type: EngineEventType, timeUs: Long, strength: Float, confidence: Float, latenessToleranceUs: Long)
    }

    enum class EngineEventType { GEAR_SHIFT, VEHICLE_PASSING }

    private val hopSec = 1f / hopRateHz
    private val spectra = Array(SHIFT_LAG + 1) { FloatArray(PitchTracker.LOG_BINS) }
    private var spectraCount = 0
    private val a = FloatArray(PitchTracker.LOG_BINS)
    private val b = FloatArray(PitchTracker.LOG_BINS)
    private val correlation = FloatArray(2 * MAX_SHIFT + 1)

    private val f0History = FloatHistory(SHIFT_LAG + 3)
    private val voiced = FloatHistory(60)
    private val f0Before = FloatHistory(40)
    private var glideHops = 0
    private val velocities = FloatHistory(HISTORY)
    private val hopTimes = LongArray(HISTORY)
    private var hopCounter = 0L
    private val levels = FloatHistory(LEVEL_HISTORY)
    private val pans = FloatHistory(60)
    private var levelSmoothed = -60f
    private val levelCoef = onePoleCoefficient(80f, hopRateHz)

    private val likelihoodFollower = EnvelopeFollower(150f, 450f, hopRateHz)
    private val idleFollower = EnvelopeFollower(80f, 300f, hopRateHz)
    private val revFollower = EnvelopeFollower(30f, 250f, hopRateHz)
    private val accelFollower = EnvelopeFollower(120f, 350f, hopRateHz)
    private val decelFollower = EnvelopeFollower(120f, 350f, hopRateHz)
    private val rumbleFollower = EnvelopeFollower(100f, 400f, hopRateHz)
    private val mainsFollower = EnvelopeFollower(400f, 800f, hopRateHz)
    private val glideDecay = onePoleCoefficient(4000f, hopRateHz)
    private var glideMemory = 0f
    private var rpmLog = 0f
    private var logMin = log2(60f)
    private var logMax = log2(450f)
    private val rangeCoef = onePoleCoefficient(20_000f, hopRateHz)
    private val rpmCoef = onePoleCoefficient(60f, hopRateHz)

    private var lastShiftHop = -1000L
    private var lastPassHop = -1000L

    /** Engine likelihood in [0, 1]. */
    val likelihood: Float get() = likelihoodFollower.value

    /** Pitch velocity of the latest hop (semitones / s). */
    var velocity = 0f; private set

    /** Fraction of the last 600 ms that was voiced (harmonic). */
    var voicedFraction = 0f; private set

    /** Spectral continuity between successive frames in [0, 1]. */
    var continuity = 0f; private set

    /** Mean velocity over the last 150 ms. */
    var trendShort = 0f; private set

    /** Mean clipped velocity over the last 600 ms. */
    var trendLong = 0f; private set

    /** Normalised engine-speed proxy in [0, 1]. */
    var rpmNormalized = 0f; private set

    val idleLevel: Float get() = idleFollower.value
    val revLevel: Float get() = revFollower.value
    val accelerationLevel: Float get() = accelFollower.value
    val decelerationLevel: Float get() = decelFollower.value
    val rumbleLevel: Float get() = rumbleFollower.value

    /**
     * Processes one hop. [pitchValid] is false for hops where the pitch tracker was not updated
     * (reduced analysis quality); the previous pitch state is then reused.
     */
    fun update(
        timeUs: Long,
        pitch: PitchTracker,
        pitchValid: Boolean,
        rmsDb: Float,
        loudnessReferenceDb: Float,
        lowShare: Float,
        pan: Float,
        speech: Float,
        musicContext: Float,
        silent: Boolean,
    ) {
        hopTimes[(hopCounter % HISTORY).toInt()] = timeUs
        hopCounter++

        levelSmoothed += (rmsDb - levelSmoothed) * levelCoef
        levels.push(levelSmoothed)
        pans.push(pan)

        if (pitchValid) {
            spectra[spectraCount % spectra.size].let { pitch.logSpectrumDb.copyInto(it) }
            spectraCount++
            f0History.push(if (pitch.harmonicity > 0.25f) pitch.f0Hz else 0f)
        }
        val isVoiced = !silent && pitch.harmonicity > 0.3f && pitch.f0Hz > 0f
        voiced.push(if (isVoiced) 1f else 0f)
        f0Before.push(if (isVoiced) pitch.f0Hz else 0f)

        // --- Pitch velocity -----------------------------------------------------------------
        var v = 0f
        if (spectraCount > SHIFT_LAG && !silent) {
            val cur = spectra[(spectraCount - 1) % spectra.size]
            val old = spectra[(spectraCount - 1 - SHIFT_LAG) % spectra.size]
            val shift = estimateShift(cur, old, pitch.usableLogBins)
            v = when {
                continuity >= 0.6f -> shift / (SHIFT_LAG * hopSec)
                pitch.harmonicity > 0.4f -> f0Velocity()
                else -> 0f
            }
        } else {
            continuity = 0f
        }
        velocity = v
        velocities.push(v)
        trendShort = velocities.mean(15)
        var clipped = 0f
        val n = minOf(60, velocities.size)
        for (i in 0 until n) clipped += velocities[i].coerceIn(-12f, 12f)
        trendLong = if (n > 0) clipped / n else 0f

        // --- Likelihood ----------------------------------------------------------------------
        val f0 = medianF0()
        val mains = if (f0 > 0f && isMainsHarmonic(f0) && abs(trendLong) < 0.4f) 1f else 0f
        mainsFollower.process(mains)
        // A glide only counts when it is sustained (a kick drum's 60 ms pitch sweep is not one).
        glideHops = if (abs(trendShort) > 2f && continuity > 0.6f && isVoiced) glideHops + 1 else 0
        if (glideHops >= GLIDE_MIN_HOPS) glideMemory = 1f else glideMemory -= glideMemory * glideDecay
        voicedFraction = voiced.mean()
        val tonalRelative = pitch.regionLevelDb - loudnessReferenceDb
        val raw = if (silent || f0 <= 0f) {
            0f
        } else {
            // Engines are *continuously* voiced: speech, drums and impacts are not.
            Fuzzy.ramp(voicedFraction, 0.75f, 0.95f) *
                Fuzzy.ramp(pitch.harmonicity, 0.2f, 0.45f) *
                Fuzzy.ramp(continuity, 0.45f, 0.8f) *
                Fuzzy.ramp(tonalRelative, -24f, -10f) *
                Fuzzy.band(f0, 25f, 900f, 10f) *
                (1f - 0.85f * speech) *
                (1f - 0.9f * musicContext) *
                (1f - mainsFollower.value) *
                (0.55f + 0.45f * glideMemory)
        }
        val e = likelihoodFollower.process(raw)

        // --- Engine-speed proxy ---------------------------------------------------------------
        if (f0 > 0f && e > 0.2f) {
            val lf = log2(f0)
            rpmLog += (lf - rpmLog) * if (rpmLog == 0f) 1f else rpmCoef
            if (lf < logMin) logMin = lf else logMin += (lf - logMin) * rangeCoef
            if (lf > logMax) logMax = lf else logMax += (lf - logMax) * rangeCoef
            val adaptive = if (logMax - logMin > 0.3f) (rpmLog - logMin) / (logMax - logMin) else 0.5f
            val absolute = Fuzzy.ramp(rpmLog, log2(40f), log2(700f))
            rpmNormalized = (0.5f * adaptive + 0.5f * absolute).coerceIn(0f, 1f)
        }

        // --- Continuous states ------------------------------------------------------------------
        val accel = e * Fuzzy.ramp(trendLong, 0.5f, 2.5f)
        val decel = e * Fuzzy.ramp(-trendLong, 0.5f, 2.5f)
        val rev = e * Fuzzy.ramp(abs(trendShort), 8f, 25f)
        val idle = e * (1f - Fuzzy.ramp(abs(trendLong), 0.5f, 2f)) * (1f - Fuzzy.ramp(rpmNormalized, 0.35f, 0.65f)) * (1f - rev)
        val residual = (1f - accel - rev - decel).coerceAtLeast(0f)
        val rumble = e * max(Fuzzy.ramp(lowShare, 0.25f, 0.5f), 0.6f * Fuzzy.ramp(rpmNormalized, 0.4f, 0.8f) * residual)
        accelFollower.process(accel)
        decelFollower.process(decel)
        revFollower.process(rev)
        idleFollower.process(idle)
        rumbleFollower.process(rumble)

        detectEvents(e)
        detectPassing(e)
    }

    /**
     * Vehicle pass-by: a prominent loudness peak (the source was ≥ 8 dB quieter 0.3–1.5 s
     * earlier) of an engine-like sound, with a falling pitch around the peak (Doppler) or a
     * stereo sweep across it. Confirmed ~100–200 ms after the peak, so the event carries a
     * lateness tolerance.
     */
    private fun detectPassing(e: Float) {
        if (levels.size < 180 || e < 0.35f) return
        var peakAge = 0
        var peak = -1e9f
        for (i in 0 until 30) if (levels[i] > peak) { peak = levels[i]; peakAge = i }
        if (peakAge !in 8..20 || levels[0] > peak - 1.5f) return
        var before = 1e9f
        for (i in peakAge + 30 until minOf(levels.size, peakAge + 150)) before = min(before, levels[i])
        val prominence = peak - before
        if (prominence < 8f) return
        val doppler = meanVelocity(maxOf(0, peakAge - 10), peakAge + 15)
        val panSwing = pans.max() - pans.min()
        if (doppler > -2f && panSwing < 0.4f) return
        val hop = hopCounter - 1
        if (hop - lastPassHop < (1.5f * hopRateHz).toLong()) return
        lastPassHop = hop
        lastShiftHop = hop
        listener.onEngineEvent(
            EngineEventType.VEHICLE_PASSING,
            timeAgo(peakAge) - LEVEL_LAG_US,
            (0.4f + 0.6f * Fuzzy.ramp(prominence, 8f, 25f)).coerceIn(0f, 1f),
            (0.5f + 0.5f * e).coerceAtMost(1f),
            PASS_LATENESS_TOLERANCE_US,
        )
    }

    private fun detectEvents(e: Float) {
        if (velocities.size < 70 || e < 0.45f || voicedFraction < 0.8f) return
        val pitchBefore = typicalF0(12, 40)
        val window = 12
        var drop = 0f
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (i in 0 until window) {
            val vi = velocities[i]
            drop += vi
            minV = min(minV, vi)
            maxV = max(maxV, vi)
        }
        drop *= hopSec
        val hop = hopCounter - 1

        if (drop <= -1.3f && minV < -12f) {
            // A fast drop during a loudness swell or stereo sweep is a Doppler pass-by, not a shift.
            if (swellDb() >= 6f || pans.max() - pans.min() >= 0.4f) return
            // Up-shift: the drop must have stopped (the run of falling hops is short).
            val stopped = velocities[0] > -4f && velocities[1] > -6f && velocities[2] > -8f
            val run = negativeRunLength(-8f)
            val before = meanVelocity(window, 60)
            if (stopped && run in 2..18 && pitchBefore > SHIFT_MIN_F0_HZ && e > 0.5f &&
                (before > 0.3f || rpmNormalized > 0.45f) && hop - lastShiftHop > MIN_SHIFT_SPACING_HOPS
            ) {
                lastShiftHop = hop
                val firstFalling = runOldestAge
                val strength = (Fuzzy.ramp(-drop, 1.3f, 5f) * 0.6f + 0.4f * e).coerceIn(0f, 1f)
                listener.onEngineEvent(
                    EngineEventType.GEAR_SHIFT,
                    timeAgo(min(firstFalling, HISTORY - 1)) - PITCH_LATENCY_US,
                    strength,
                    (0.4f + 0.6f * e).coerceAtMost(1f),
                    0L,
                )
            }
        } else if (drop >= 1.5f && maxV > 15f && meanVelocity(window, 60) < -0.5f && pitchBefore > SHIFT_MIN_F0_HZ &&
            e > 0.5f && hop - lastShiftHop > MIN_SHIFT_SPACING_HOPS
        ) {
            // Down-shift blip during deceleration.
            lastShiftHop = hop
            val steepest = indexOfExtreme(window, negative = false)
            listener.onEngineEvent(
                EngineEventType.GEAR_SHIFT,
                timeAgo(steepest + 2) - PITCH_LATENCY_US,
                (0.4f + 0.4f * e).coerceIn(0f, 1f),
                (0.4f + 0.5f * e).coerceAtMost(1f),
                0L,
            )
        }
    }

    /**
     * Loudness swell of a passing source: the level must have been rising *gradually* for at
     * least ~300 ms (an instantaneous jump such as a drum hit is not a swell).
     */
    private fun swellDb(): Float {
        if (levels.size < 150) return 0f
        val current = levels[0]
        var minBefore = Float.MAX_VALUE
        var minOlder = Float.MAX_VALUE
        var maxRecent = -Float.MAX_VALUE
        for (i in 0 until 150) {
            val l = levels[i]
            if (i >= 30) minBefore = min(minBefore, l)
            if (i >= 60) minOlder = min(minOlder, l)
            maxRecent = max(maxRecent, l)
        }
        val alreadyRising = levels[30] - minOlder >= 3f
        val noJump = current - levels[3] < 6f
        return if (current >= maxRecent - 4f && alreadyRising && noJump) current - minBefore else 0f
    }

    /** Median-free typical f0 (mean of voiced values) between [fromAge] and [toAge] hops ago. */
    private fun typicalF0(fromAge: Int, toAge: Int): Float {
        var sum = 0f
        var n = 0
        for (i in fromAge until minOf(toAge, f0Before.size)) {
            val f = f0Before[i]
            if (f > 0f) { sum += f; n++ }
        }
        return if (n > 0) sum / n else 0f
    }

    /** Age (in hops) of the oldest hop of the most recent falling run, set by [negativeRunLength]. */
    private var runOldestAge = 0

    private fun negativeRunLength(threshold: Float): Int {
        // Skip the recovered hops at the head, then count consecutive falling hops.
        var i = 0
        while (i < velocities.size && velocities[i] >= threshold && i < 6) i++
        var run = 0
        while (i + run < velocities.size && velocities[i + run] < threshold) run++
        runOldestAge = (i + run - 1).coerceAtLeast(0)
        return run
    }

    private fun meanVelocity(fromAge: Int, toAge: Int): Float {
        var s = 0f
        var n = 0
        for (i in fromAge until minOf(toAge, velocities.size)) { s += velocities[i]; n++ }
        return if (n > 0) s / n else 0f
    }

    private fun indexOfExtreme(window: Int, negative: Boolean): Int {
        var idx = 0
        var best = velocities[0]
        for (i in 1 until window) {
            val vi = velocities[i]
            if ((negative && vi < best) || (!negative && vi > best)) { best = vi; idx = i }
        }
        return idx
    }

    private fun timeAgo(hopsAgo: Int): Long {
        val index = ((hopCounter - 1 - hopsAgo) % HISTORY).toInt().let { if (it < 0) it + HISTORY else it }
        return hopTimes[index]
    }

    private fun estimateShift(current: FloatArray, previous: FloatArray, usable: Int): Float {
        val lo = 6
        val hi = minOf(usable, PitchTracker.LOG_BINS)
        if (hi - lo < 16) { continuity = 0f; return 0f }
        var maxA = -200f
        var maxB = -200f
        for (i in lo until hi) { maxA = max(maxA, current[i]); maxB = max(maxB, previous[i]) }
        for (i in lo until hi) {
            a[i] = max(0f, current[i] - (maxA - 45f))
            b[i] = max(0f, previous[i] - (maxB - 45f))
        }
        var bestIndex = MAX_SHIFT
        var best = -1f
        for (s in -MAX_SHIFT..MAX_SHIFT) {
            var num = 0f
            var ea = 0f
            var eb = 0f
            for (i in lo until hi) {
                val j = i - s
                if (j < lo || j >= hi) continue
                num += a[i] * b[j]
                ea += a[i] * a[i]
                eb += b[j] * b[j]
            }
            val c = if (ea > 1e-6f && eb > 1e-6f) num / sqrt(ea * eb) else 0f
            correlation[s + MAX_SHIFT] = c
            if (c > best) { best = c; bestIndex = s + MAX_SHIFT }
        }
        continuity = best.coerceIn(0f, 1f)
        var shift = (bestIndex - MAX_SHIFT).toFloat()
        if (bestIndex in 1 until correlation.size - 1) {
            val l = correlation[bestIndex - 1]
            val c = correlation[bestIndex]
            val r = correlation[bestIndex + 1]
            val denominator = l - 2 * c + r
            if (denominator < 0f) shift += (0.5f * (l - r) / denominator).coerceIn(-0.5f, 0.5f)
        }
        return shift
    }

    private fun f0Velocity(): Float {
        if (f0History.size <= SHIFT_LAG) return 0f
        val now = f0History[0]
        val then = f0History[SHIFT_LAG]
        if (now <= 0f || then <= 0f) return 0f
        val semitones = 12f * log2(now / then)
        return if (abs(semitones) < 7f) semitones / (SHIFT_LAG * hopSec) else 0f
    }

    private fun medianF0(): Float {
        if (f0History.size < 3) return if (f0History.size > 0) f0History[0] else 0f
        val x = f0History[0]
        val y = f0History[1]
        val z = f0History[2]
        return max(min(x, y), min(max(x, y), z))
    }

    private fun isMainsHarmonic(f0: Float): Boolean {
        for (base in MAINS) {
            for (k in 1..3) {
                if (abs(f0 - base * k) < base * k * 0.03f) return true
            }
        }
        return false
    }

    fun reset() {
        spectraCount = 0
        f0History.clear()
        voiced.clear()
        f0Before.clear()
        glideHops = 0
        voicedFraction = 0f
        velocities.clear()
        hopCounter = 0
        levels.clear()
        pans.clear()
        levelSmoothed = -60f
        likelihoodFollower.reset(); idleFollower.reset(); revFollower.reset()
        accelFollower.reset(); decelFollower.reset(); rumbleFollower.reset(); mainsFollower.reset()
        glideMemory = 0f
        rpmLog = 0f
        rpmNormalized = 0f
        velocity = 0f; trendShort = 0f; trendLong = 0f; continuity = 0f
        lastShiftHop = -1000
        lastPassHop = -1000
    }

    companion object {
        const val SHIFT_LAG = 4
        private const val MAX_SHIFT = 6
        private const val HISTORY = 100
        private const val LEVEL_HISTORY = 200
        private const val MIN_SHIFT_SPACING_HOPS = 30
        private const val GLIDE_MIN_HOPS = 15

        /** Gear changes happen at engine speeds whose firing frequency is well above this. */
        private const val SHIFT_MIN_F0_HZ = 90f

        /** The pitch tracker's long window reacts this long after an acoustic pitch change. */
        const val PITCH_LATENCY_US = 30_000L

        /** A pass-by is recognised after its loudness peak; allow rendering it slightly late. */
        const val PASS_LATENESS_TOLERANCE_US = 300_000L

        /** Delay of the smoothed loudness curve relative to the acoustic peak. */
        private const val LEVEL_LAG_US = 60_000L

        private val MAINS = floatArrayOf(50f, 60f)
    }
}
