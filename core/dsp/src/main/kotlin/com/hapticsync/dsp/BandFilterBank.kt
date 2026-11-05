package com.hapticsync.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A bank of band-pass filters that splits a mono signal into frequency bands and measures each
 * band's energy and peak per processing block.
 *
 * Each band is a 4th-order Butterworth high-pass at the lower edge followed by a 4th-order
 * Butterworth low-pass at the upper edge (24 dB/octave skirts). Time-domain IIR filtering gives
 * the low bands both accurate frequency selectivity and fine time resolution, which a short FFT
 * cannot: a 2048-point FFT at 48 kHz has only ~23 Hz bins, too coarse for a 20–60 Hz band.
 *
 * Band edges are configurable at runtime through [configure]. Bands whose lower edge lies at or
 * above 45% of the sample rate are reported as unavailable and produce zero energy.
 */
class BandFilterBank(val bandCount: Int) {

    private val highPass = Array(bandCount) { BiquadCascade(2) }
    private val lowPass = Array(bandCount) { BiquadCascade(2) }
    private val available = BooleanArray(bandCount)
    private var sampleRate = 48_000.0

    private val sumSquares = DoubleArray(bandCount)
    private val peaks = FloatArray(bandCount)
    private var blockSamples = 0

    /** Mean-square energy per band of the last completed block (linear power). */
    val bandEnergy = FloatArray(bandCount)

    /** Peak absolute amplitude per band of the last completed block. */
    val bandPeak = FloatArray(bandCount)

    /** RMS amplitude per band of the last completed block. */
    val bandRms = FloatArray(bandCount)

    /**
     * Configures the band edges. [lowHz]/[highHz] hold `bandCount` values each.
     * Allocation-free; may be called while running (filter state is preserved where possible).
     */
    fun configure(sampleRate: Int, lowHz: FloatArray, highHz: FloatArray) {
        require(lowHz.size >= bandCount && highHz.size >= bandCount)
        this.sampleRate = sampleRate.toDouble()
        val nyquistLimit = sampleRate * 0.45
        for (b in 0 until bandCount) {
            val lo = lowHz[b].toDouble().coerceAtLeast(5.0)
            val hi = highHz[b].toDouble()
            available[b] = lo < nyquistLimit && hi > lo
            if (available[b]) {
                val hiClamped = hi.coerceAtMost(nyquistLimit)
                highPass[b].sections[0].setHighPass(this.sampleRate, lo, Biquad.BUTTERWORTH4_Q1)
                highPass[b].sections[1].setHighPass(this.sampleRate, lo, Biquad.BUTTERWORTH4_Q2)
                lowPass[b].sections[0].setLowPass(this.sampleRate, hiClamped, Biquad.BUTTERWORTH4_Q1)
                lowPass[b].sections[1].setLowPass(this.sampleRate, hiClamped, Biquad.BUTTERWORTH4_Q2)
            } else {
                for (s in highPass[b].sections) s.setMute()
                for (s in lowPass[b].sections) s.setMute()
            }
        }
    }

    /** Whether band [band] can be measured at the configured sample rate. */
    fun isAvailable(band: Int): Boolean = available[band]

    /** Filters [count] samples of [input] starting at [offset] and accumulates band statistics. */
    fun process(input: FloatArray, offset: Int, count: Int) {
        for (b in 0 until bandCount) {
            if (!available[b]) continue
            val hp = highPass[b]
            val lp = lowPass[b]
            var ss = sumSquares[b]
            var pk = peaks[b]
            for (i in offset until offset + count) {
                val y = lp.process(hp.process(input[i].toDouble()))
                ss += y * y
                val a = abs(y).toFloat()
                if (a > pk) pk = a
            }
            sumSquares[b] = ss
            peaks[b] = pk
        }
        blockSamples += count
    }

    /**
     * Completes the current block: publishes [bandEnergy], [bandRms] and [bandPeak] and starts
     * a new block.
     */
    fun endBlock() {
        val n = max(1, blockSamples)
        for (b in 0 until bandCount) {
            val e = if (available[b]) (sumSquares[b] / n).toFloat() else 0f
            bandEnergy[b] = e
            bandRms[b] = sqrt(e)
            bandPeak[b] = if (available[b]) peaks[b] else 0f
            sumSquares[b] = 0.0
            peaks[b] = 0f
            highPass[b].sanitize()
            lowPass[b].sanitize()
        }
        blockSamples = 0
    }

    /** Clears filter state and accumulators. */
    fun reset() {
        for (b in 0 until bandCount) {
            highPass[b].reset()
            lowPass[b].reset()
            sumSquares[b] = 0.0
            peaks[b] = 0f
            bandEnergy[b] = 0f
            bandRms[b] = 0f
            bandPeak[b] = 0f
        }
        blockSamples = 0
    }

    /** Combined magnitude response of band [band] at [frequencyHz]. */
    fun magnitudeAt(band: Int, frequencyHz: Double): Double =
        if (!available[band]) 0.0
        else highPass[band].magnitudeAt(frequencyHz, sampleRate) * lowPass[band].magnitudeAt(frequencyHz, sampleRate)
}
