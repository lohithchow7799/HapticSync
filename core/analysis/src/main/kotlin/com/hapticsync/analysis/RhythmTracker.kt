package com.hapticsync.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Detects a regular rhythmic pulse (music beats, footsteps) from the onset stream.
 *
 * Onset strengths are written into a 4-second pulse train at hop resolution. Every
 * [EVALUATE_INTERVAL] hops the tracker computes a jitter-tolerant autocorrelation over periods of
 * 0.27–1.5 s (40–220 BPM), weighted by a mild tempo prior centred near 110 BPM. The best period's
 * normalised correlation is the rhythm confidence; the most recent onset fixes the beat phase.
 */
internal class RhythmTracker(hopRateHz: Float) {

    private var hopSec = 1f / hopRateHz
    private val length = (4f * hopRateHz).roundToInt().coerceAtLeast(64)
    private val pulses = FloatArray(length)
    private var hop = 0L
    private val minLag = (0.27f * hopRateHz).roundToInt().coerceAtLeast(2)
    private val maxLag = (1.5f * hopRateHz).roundToInt().coerceAtMost(length / 2)
    private var onsetCount = 0
    private val onsetHops = LongArray(32)

    /** Normalised strength of the periodicity in [0, 1]. */
    var confidence = 0f; private set

    /** Estimated period in hops (0 when unknown). */
    var periodHops = 0; private set

    /** Hop index of the latest onset that defines the beat phase. */
    private var lastBeatHop = -1L

    /** Recent onset rate in onsets per second. */
    val onsetRate: Float
        get() {
            val horizon = (2f / hopSec).toLong()
            var n = 0
            for (i in 0 until minOf(onsetCount, onsetHops.size)) if (hop - onsetHops[i] <= horizon) n++
            return n / 2f
        }

    /** Advances one hop; [onsetStrength] is 0 when no onset occurred in this hop. */
    fun onHop(onsetStrength: Float) {
        pulses[(hop % length).toInt()] = onsetStrength
        if (onsetStrength > 0f) {
            onsetHops[onsetCount % onsetHops.size] = hop
            onsetCount++
            if (periodHops > 0 && isOnBeat(hop)) lastBeatHop = hop
            if (lastBeatHop < 0 || hop - lastBeatHop > 2 * maxOf(periodHops, minLag)) lastBeatHop = hop
        }
        if (hop % EVALUATE_INTERVAL == 0L) evaluate()
        hop++
    }

    /** Whether hop [atHop] falls on the predicted beat grid (±40 ms). */
    fun isOnBeat(atHop: Long): Boolean {
        if (periodHops <= 0 || lastBeatHop < 0 || confidence < 0.3f) return false
        val tolerance = max(1, (0.04f / hopSec).roundToInt())
        val offset = ((atHop - lastBeatHop) % periodHops + periodHops) % periodHops
        return offset <= tolerance || periodHops - offset <= tolerance
    }

    /** Regularity of the last few inter-onset intervals in [0, 1], for intervals within [minSec, maxSec]. */
    fun intervalRegularity(minSec: Float, maxSec: Float): Float {
        val n = minOf(onsetCount, onsetHops.size)
        if (n < 3) return 0f
        var sum = 0f
        var sumSq = 0f
        var count = 0
        for (i in 1 until minOf(n, 4)) {
            val a = onsetHops[(onsetCount - i) % onsetHops.size]
            val b = onsetHops[(onsetCount - i - 1) % onsetHops.size]
            val interval = (a - b) * hopSec
            if (interval < minSec || interval > maxSec) return 0f
            sum += interval
            sumSq += interval * interval
            count++
        }
        if (count < 2) return 0f
        val mean = sum / count
        val variance = max(0f, sumSq / count - mean * mean)
        val cv = kotlin.math.sqrt(variance) / mean
        return Fuzzy.ramp(cv, 0.25f, 0.05f)
    }

    private fun evaluate() {
        var energy = 0f
        for (p in pulses) energy += p * p
        if (energy <= 1e-6f || onsetRate < 0.8f) {
            confidence *= 0.8f
            if (confidence < 0.05f) periodHops = 0
            return
        }
        var bestLag = 0
        var best = 0f
        for (lag in minLag..maxLag) {
            var ac = 0f
            for (i in 0 until length) {
                val p = pulses[i]
                if (p == 0f) continue
                val j0 = (i - lag + length) % length
                val jm = (j0 - 1 + length) % length
                val jp = (j0 + 1) % length
                ac += p * max(pulses[j0], max(0.7f * pulses[jm], 0.7f * pulses[jp]))
            }
            val periodSec = lag * hopSec
            val prior = exp(-0.5f * (ln(periodSec / 0.55f) / 0.7f).let { it * it })
            val score = (ac / energy) * (0.65f + 0.35f * prior)
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        // Smooth the confidence so that single irregular fills do not toggle music context.
        confidence = 0.6f * confidence + 0.4f * best.coerceIn(0f, 1f)
        if (bestLag > 0 && (periodHops == 0 || abs(bestLag - periodHops) > 1 || best > 0.4f)) periodHops = bestLag
    }

    fun reset() {
        pulses.fill(0f)
        hop = 0
        onsetCount = 0
        confidence = 0f
        periodHops = 0
        lastBeatHop = -1
    }

    companion object {
        private const val EVALUATE_INTERVAL = 10L
    }
}
