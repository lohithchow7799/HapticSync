package com.hapticsync.dsp.synth

import com.hapticsync.dsp.Biquad
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Additive model of a combustion engine.
 *
 * The engine's sound is dominated by its firing frequency `rpm / 60 · cylinders / 2` (4-stroke)
 * and that frequency's harmonics. The model renders that harmonic series with:
 * - exhaust/intake formant resonances that colour the harmonics,
 * - throttle-dependent brightness (more upper harmonics under load),
 * - per-combustion-cycle amplitude jitter (the "roughness" of a real engine),
 * - intake/mechanical noise, and
 * - an optional Doppler factor and gain curve for pass-by scenes.
 *
 * Harmonics are generated with the Chebyshev recurrence `sin((h+1)φ) = 2cosφ·sin(hφ) − sin((h−1)φ)`
 * so that only one sin/cos pair is evaluated per sample.
 */
class EngineSynth(
    private val sampleRate: Int,
    private val cylinders: Int = 6,
    seed: Int = 7,
) {
    private val rng = Prng(seed)

    /**
     * Renders [durationSec] seconds. Curves receive time in seconds:
     * [rpmAt] engine speed, [throttleAt] load in [0, 1], [dopplerAt] frequency factor (1 = none),
     * [gainAt] amplitude factor.
     */
    fun render(
        durationSec: Double,
        rpmAt: (Double) -> Double,
        throttleAt: (Double) -> Double,
        dopplerAt: (Double) -> Double = { 1.0 },
        gainAt: (Double) -> Double = { 1.0 },
    ): FloatArray {
        val n = Dsp.samples(sampleRate, durationSec)
        val out = FloatArray(n)
        val amplitudes = FloatArray(MAX_HARMONICS + 1)
        var harmonics = 1
        var phase = 0.0
        var jitter = 1f
        val noiseFilter = Biquad().apply { setBandPass(sampleRate.toDouble(), 900.0, 0.7) }
        var gain = 1.0
        var throttle = 0.0

        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val f0 = max(5.0, rpmAt(t) / 60.0 * cylinders / 2.0 * dopplerAt(t))
            if (i % CONTROL_INTERVAL == 0) {
                throttle = throttleAt(t).coerceIn(0.0, 1.0)
                gain = gainAt(t)
                harmonics = minOf(MAX_HARMONICS, ((0.45 * sampleRate) / f0).toInt()).coerceAtLeast(1)
                for (h in 1..harmonics) {
                    val freq = h * f0
                    val brightness = if (h <= 3) 1.0 else 0.25 + 0.75 * throttle
                    amplitudes[h] = (formantGain(freq) * h.toDouble().pow(-0.65) * brightness).toFloat()
                }
            }
            val s1 = sin(phase)
            val c1 = cos(phase)
            var previous = 0.0
            var current = s1
            var sum = amplitudes[1] * s1
            for (h in 2..harmonics) {
                val next = 2 * c1 * current - previous
                previous = current
                current = next
                sum += amplitudes[h] * next
            }
            phase += 2 * PI * f0 / sampleRate
            if (phase >= 2 * PI) {
                phase -= 2 * PI
                // New combustion cycle: small random amplitude variation.
                jitter = 1f + 0.12f * rng.bipolar()
            }
            val noise = noiseFilter.process(rng.bipolar().toDouble()) * (0.05 + 0.12 * throttle)
            out[i] = ((sum * jitter * (0.55 + 0.45 * throttle) + noise) * gain).toFloat()
        }
        return out
    }

    private fun formantGain(frequencyHz: Double): Double {
        var g = 0.3
        for (k in FORMANT_HZ.indices) {
            val x = (frequencyHz - FORMANT_HZ[k]) / FORMANT_BANDWIDTH_HZ[k]
            g += FORMANT_GAIN[k] / (1 + x * x)
        }
        return g
    }

    companion object {
        private const val MAX_HARMONICS = 28
        private const val CONTROL_INTERVAL = 32
        private val FORMANT_HZ = doubleArrayOf(340.0, 1150.0, 2600.0)
        private val FORMANT_BANDWIDTH_HZ = doubleArrayOf(160.0, 320.0, 600.0)
        private val FORMANT_GAIN = doubleArrayOf(1.0, 0.7, 0.35)

        /**
         * Piecewise-linear curve through `(time, value)` pairs, held constant outside the range.
         */
        fun curve(vararg points: Pair<Double, Double>): (Double) -> Double {
            val sorted = points.sortedBy { it.first }
            return { t ->
                when {
                    t <= sorted.first().first -> sorted.first().second
                    t >= sorted.last().first -> sorted.last().second
                    else -> {
                        var idx = 0
                        while (sorted[idx + 1].first < t) idx++
                        val (t0, v0) = sorted[idx]
                        val (t1, v1) = sorted[idx + 1]
                        v0 + (v1 - v0) * (t - t0) / (t1 - t0)
                    }
                }
            }
        }
    }
}
