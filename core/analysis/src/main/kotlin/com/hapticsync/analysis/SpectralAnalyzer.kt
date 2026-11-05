package com.hapticsync.analysis

import com.hapticsync.dsp.Db
import com.hapticsync.dsp.RealFft
import com.hapticsync.dsp.Windows
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FFT-based spectral features of the analysis window: centroid, flatness, roll-off, spectral
 * flux (overall, low and high), and a log-spaced spectrum for visualisation.
 *
 * Allocation-free after construction.
 */
internal class SpectralAnalyzer(private val config: AnalysisConfig) {

    private val fft = RealFft(config.fftSize)
    private val window = Windows.hann(config.windowSize)
    private val amplitudeGain = 2f / Windows.coherentGain(window)
    private val frame = FloatArray(config.fftSize)
    private val re = FloatArray(fft.binCount)
    private val im = FloatArray(fft.binCount)
    private val magnitude = FloatArray(fft.binCount)
    private var logMagnitude = FloatArray(fft.binCount)
    private var previousLogMagnitude = FloatArray(fft.binCount)
    private var primed = false

    private val binHz = config.sampleRate.toFloat() / config.fftSize
    private val nyquistBin = fft.binCount - 1
    private val rangeLowBin = binOf(config.rangeLowHz).coerceAtLeast(1)
    private val rangeHighBin = binOf(config.rangeHighHz).coerceIn(rangeLowBin + 1, nyquistBin)
    private val flatLowBin = binOf(100f).coerceAtLeast(1)
    private val flatHighBin = binOf(min(8000f, config.rangeHighHz)).coerceIn(flatLowBin + 1, nyquistBin)
    private val lowFluxHighBin = binOf(250f).coerceIn(rangeLowBin, rangeHighBin)
    private val highFluxLowBin = binOf(2000f).coerceIn(rangeLowBin, rangeHighBin)

    private val barLow = IntArray(AnalysisFrame.SPECTRUM_BARS)
    private val barHigh = IntArray(AnalysisFrame.SPECTRUM_BARS)
    private val barCentre = FloatArray(AnalysisFrame.SPECTRUM_BARS)

    var centroidHz = 0f; private set
    var flatness = 0f; private set
    var rolloffHz = 0f; private set
    var flux = 0f; private set
    var lowFlux = 0f; private set
    var highFlux = 0f; private set

    /** Visualisation spectrum in dBFS. */
    val barsDb = FloatArray(AnalysisFrame.SPECTRUM_BARS) { Db.FLOOR_DB }

    init {
        val top = min(16_000f, config.sampleRate * 0.45f)
        val bottom = 20f
        val bars = AnalysisFrame.SPECTRUM_BARS
        for (b in 0 until bars) {
            val f0 = bottom * (top / bottom).pow(b.toFloat() / bars)
            val f1 = bottom * (top / bottom).pow((b + 1).toFloat() / bars)
            barLow[b] = binOf(f0).coerceIn(1, nyquistBin)
            barHigh[b] = binOf(f1).coerceIn(barLow[b], nyquistBin)
            barCentre[b] = (f0 * f1).let { kotlin.math.sqrt(it) } / binHz
        }
    }

    private fun binOf(hz: Float): Int = (hz / binHz).toInt()

    /**
     * Analyses [samples] (the analysis window, oldest sample first, `config.windowSize` long).
     */
    fun analyze(samples: FloatArray) {
        val w = config.windowSize
        for (i in 0 until w) frame[i] = samples[i] * window[i]
        for (i in w until frame.size) frame[i] = 0f
        fft.magnitudes(frame, magnitude, re, im)
        for (k in magnitude.indices) magnitude[k] *= amplitudeGain

        // Centroid (magnitude weighted) and roll-off (power) over the analysis range.
        var sumMag = 0.0
        var sumFreqMag = 0.0
        var sumPower = 0.0
        for (k in rangeLowBin..rangeHighBin) {
            val m = magnitude[k].toDouble()
            sumMag += m
            sumFreqMag += m * k * binHz
            sumPower += m * m
        }
        centroidHz = if (sumMag > 1e-9) (sumFreqMag / sumMag).toFloat() else 0f
        rolloffHz = 0f
        if (sumPower > 1e-12) {
            val target = 0.85 * sumPower
            var acc = 0.0
            for (k in rangeLowBin..rangeHighBin) {
                acc += magnitude[k].toDouble() * magnitude[k]
                if (acc >= target) {
                    rolloffHz = k * binHz
                    break
                }
            }
        }

        // Flatness: geometric mean / arithmetic mean of the power spectrum.
        var logSum = 0.0
        var linSum = 0.0
        val n = flatHighBin - flatLowBin + 1
        for (k in flatLowBin..flatHighBin) {
            val p = magnitude[k].toDouble() * magnitude[k] + 1e-14
            logSum += ln(p)
            linSum += p
        }
        flatness = if (linSum > 1e-12 * n) (exp(logSum / n) / (linSum / n)).toFloat().coerceIn(0f, 1f) else 0f

        // Spectral flux on log-compressed magnitudes.
        for (k in magnitude.indices) logMagnitude[k] = ln(1f + 100f * magnitude[k])
        if (primed) {
            var f = 0f
            var fl = 0f
            var fh = 0f
            for (k in rangeLowBin..rangeHighBin) {
                val d = logMagnitude[k] - previousLogMagnitude[k]
                if (d > 0f) {
                    f += d
                    if (k <= lowFluxHighBin) fl += d
                    if (k >= highFluxLowBin) fh += d
                }
            }
            flux = f / (rangeHighBin - rangeLowBin + 1)
            lowFlux = fl / max(1, lowFluxHighBin - rangeLowBin + 1)
            highFlux = fh / max(1, rangeHighBin - highFluxLowBin + 1)
        } else {
            flux = 0f; lowFlux = 0f; highFlux = 0f
        }
        val t = previousLogMagnitude
        previousLogMagnitude = logMagnitude
        logMagnitude = t
        primed = true

        // Visualisation bars: peak magnitude per bar, interpolated for bars narrower than a bin.
        for (b in barsDb.indices) {
            var m = 0f
            if (barHigh[b] <= barLow[b]) {
                val pos = barCentre[b]
                val k = pos.toInt().coerceIn(0, nyquistBin - 1)
                val frac = pos - k
                m = magnitude[k] * (1 - frac) + magnitude[k + 1] * frac
            } else {
                for (k in barLow[b]..barHigh[b]) m = max(m, magnitude[k])
            }
            barsDb[b] = Db.fromAmplitude(m)
        }
    }

    fun reset() {
        primed = false
        centroidHz = 0f; flatness = 0f; rolloffHz = 0f; flux = 0f; lowFlux = 0f; highFlux = 0f
        barsDb.fill(Db.FLOOR_DB)
    }
}
