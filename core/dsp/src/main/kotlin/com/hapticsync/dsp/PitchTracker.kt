package com.hapticsync.dsp

import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * Tracks the fundamental frequency, harmonicity and spectral shape of harmonic sounds such as
 * engines, motors and sustained musical tones.
 *
 * The input is decimated to roughly 8–9 kHz, where a 1024-sample (≈120 ms) window, zero-padded
 * to 2048 points, gives ≈4 Hz bins at negligible cost. On every [analyze] call the tracker computes:
 *
 * - [f0Hz]: fundamental estimate from a harmonic product spectrum (HPS) over
 *   [minF0Hz]..[maxF0Hz], refined by parabolic interpolation (0 when no clear pitch exists),
 * - [harmonicity]: fraction of the 50 Hz–3 kHz energy that lies on the harmonics of [f0Hz]
 *   (0 = noise, → 1 = strongly harmonic),
 * - [tonalCentroidHz]: spectral centroid of the same region,
 * - [logSpectrumDb]: magnitude on a semitone grid starting at [LOG_SPECTRUM_BASE_HZ]. A pitch
 *   change shifts the whole harmonic comb along this axis, which lets the analysis estimate
 *   *how fast* the pitch moves (rev up, rev down, gear-shift drop) without octave errors.
 *
 * Note: this measures acoustic periodicity only. Whether a periodic sound is an engine is
 * decided by the classifier from the pitch dynamics together with other features.
 */
class PitchTracker(
    inputSampleRate: Int,
    val minF0Hz: Float = 25f,
    val maxF0Hz: Float = 900f,
) {
    private val decimator = Decimator().apply { configure(inputSampleRate, MIN_DECIMATED_RATE) }
    private val rate = decimator.outputRate.toFloat()

    private val ring = FloatArray(FRAME_SIZE)
    private var ringWrite = 0
    private var filled = 0
    private val decimated = FloatArray(decimator.maxOutput(MAX_INPUT_CHUNK) + 1)

    private val window = Windows.hann(FRAME_SIZE)
    private val fft = RealFft(FFT_SIZE)
    private val frame = FloatArray(FFT_SIZE)
    private val re = FloatArray(fft.binCount)
    private val im = FloatArray(fft.binCount)
    private val magnitude = FloatArray(fft.binCount)
    private val logMagnitude = FloatArray(fft.binCount)
    private val binHz = rate / FFT_SIZE

    private val regionLowBin = max(1, (50f / binHz).toInt())
    private val regionHighBin = minOf(fft.binCount - 2, (minOf(REGION_HIGH_HZ, 0.40f * rate) / binHz).toInt())
    private val minBin = max(2, (minF0Hz / binHz).toInt())
    private val maxBin = minOf((maxF0Hz / binHz).toInt(), (regionHighBin - 1) / 3)

    /** Fundamental frequency estimate in Hz, or 0 when the signal has no clear pitch. */
    var f0Hz: Float = 0f
        private set

    /** Fraction of energy on the harmonics of [f0Hz], in [0, 1]. */
    var harmonicity: Float = 0f
        private set

    /** Spectral centroid of the 50 Hz–3 kHz region, in Hz. */
    var tonalCentroidHz: Float = 0f
        private set

    /** Mean power of the analysed region in dB (relative to full scale, approximate). */
    var regionLevelDb: Float = Db.FLOOR_DB
        private set

    /** Magnitude in dB on a semitone grid ([LOG_BINS] values from [LOG_SPECTRUM_BASE_HZ]). */
    val logSpectrumDb = FloatArray(LOG_BINS) { Db.FLOOR_DB }

    /** Number of usable semitone bins at this sample rate (bins above the analysis band stay at the floor). */
    val usableLogBins: Int = run {
        var n = 0
        while (n < LOG_BINS && logBinFrequency(n) < regionHighBin * binHz) n++
        n
    }

    /** Whether the analysis window is filled with enough audio to be meaningful. */
    val isPrimed: Boolean get() = filled >= FRAME_SIZE

    /** Feeds full-rate samples. Chunks larger than [MAX_INPUT_CHUNK] are split internally. */
    fun push(input: FloatArray, offset: Int, count: Int) {
        var remaining = count
        var pos = offset
        while (remaining > 0) {
            val n = minOf(remaining, MAX_INPUT_CHUNK)
            val produced = decimator.process(input, pos, n, decimated, 0)
            for (i in 0 until produced) {
                ring[ringWrite] = decimated[i]
                ringWrite = (ringWrite + 1) % FRAME_SIZE
            }
            filled = minOf(FRAME_SIZE, filled + produced)
            remaining -= n
            pos += n
        }
    }

    /** Analyses the most recent window. Allocation-free. */
    fun analyze() {
        if (!isPrimed) {
            f0Hz = 0f
            harmonicity = 0f
            return
        }
        // Unroll the ring buffer (oldest sample first) and apply the window; zero-pad the rest.
        for (i in 0 until FRAME_SIZE) {
            frame[i] = ring[(ringWrite + i) % FRAME_SIZE] * window[i]
        }
        for (i in FRAME_SIZE until FFT_SIZE) frame[i] = 0f
        fft.magnitudes(frame, magnitude, re, im)

        var total = 0.0
        var weighted = 0.0
        for (k in regionLowBin..regionHighBin) {
            val p = magnitude[k] * magnitude[k]
            total += p
            weighted += p * k * binHz
        }
        for (k in 0 until fft.binCount) logMagnitude[k] = ln(magnitude[k] + 1e-9f)

        val normalisation = (FRAME_SIZE * 0.25f).let { it * it } // Hann coherent gain² for a full-scale sine
        regionLevelDb = Db.fromPower((total / normalisation).toFloat())
        tonalCentroidHz = if (total > 1e-12) (weighted / total).toFloat() else 0f

        updateLogSpectrum()

        if (total < 1e-9 || maxBin <= minBin) {
            f0Hz = 0f
            harmonicity = 0f
            return
        }

        // Harmonic product spectrum in the log domain, tolerant to ±1 bin harmonic mistuning.
        var bestBin = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (k in minBin..maxBin) {
            val s = logMagnitude[k] + peakLog(2 * k) + peakLog(3 * k) + 0.5f * peakLog(4 * k)
            if (s > bestScore) {
                bestScore = s
                bestBin = k
            }
        }
        if (bestBin < 0) {
            f0Hz = 0f
            harmonicity = 0f
            return
        }
        // Prefer the octave above when the chosen fundamental itself is very weak (a classic
        // HPS sub-octave error).
        val up = bestBin * 2
        if (up <= maxBin && magnitude[bestBin] < 0.1f * magnitude[up]) bestBin = up

        val refined = parabolicPeak(bestBin)
        val f0 = refined * binHz
        f0Hz = f0

        // Harmonicity: energy within ±1 bin of the first harmonics versus the region total.
        var harmonicEnergy = 0.0
        var h = 1
        while (h <= MAX_HARMONICS) {
            val centre = (refined * h).toInt()
            if (centre + 1 > regionHighBin) break
            for (k in centre - 1..centre + 1) {
                if (k >= regionLowBin) harmonicEnergy += magnitude[k] * magnitude[k]
            }
            h++
        }
        harmonicity = (harmonicEnergy / total).toFloat().coerceIn(0f, 1f)
    }

    private fun peakLog(bin: Int): Float {
        if (bin + 1 >= fft.binCount) return logMagnitude[fft.binCount - 1]
        return max(logMagnitude[bin], max(logMagnitude[bin - 1], logMagnitude[bin + 1]))
    }

    private fun parabolicPeak(bin: Int): Float {
        if (bin <= 0 || bin >= fft.binCount - 1) return bin.toFloat()
        val a = logMagnitude[bin - 1]
        val b = logMagnitude[bin]
        val c = logMagnitude[bin + 1]
        val denominator = a - 2 * b + c
        if (denominator >= 0f) return bin.toFloat()
        val offset = 0.5f * (a - c) / denominator
        return bin + offset.coerceIn(-0.5f, 0.5f)
    }

    private fun updateLogSpectrum() {
        for (i in 0 until LOG_BINS) {
            if (i >= usableLogBins) {
                logSpectrumDb[i] = Db.FLOOR_DB
                continue
            }
            val position = logBinFrequency(i) / binHz
            val k = position.toInt().coerceIn(0, fft.binCount - 2)
            val frac = position - k
            val m = magnitude[k] * (1 - frac) + magnitude[k + 1] * frac
            logSpectrumDb[i] = Db.fromAmplitude(m / (FRAME_SIZE * 0.25f))
        }
    }

    /** Clears all state (call after a discontinuity in the input). */
    fun reset() {
        decimator.reset()
        ring.fill(0f)
        ringWrite = 0
        filled = 0
        f0Hz = 0f
        harmonicity = 0f
        tonalCentroidHz = 0f
        regionLevelDb = Db.FLOOR_DB
        logSpectrumDb.fill(Db.FLOOR_DB)
    }

    companion object {
        const val FRAME_SIZE = 1024
        const val FFT_SIZE = 2048
        const val MIN_DECIMATED_RATE = 8_000
        const val MAX_INPUT_CHUNK = 4096
        const val MAX_HARMONICS = 10

        /** Upper edge of the harmonic analysis region. */
        const val REGION_HIGH_HZ = 3000f

        /** Number of semitone bins in [logSpectrumDb]. */
        const val LOG_BINS = 64

        /** Frequency of semitone bin 0 (A1). */
        const val LOG_SPECTRUM_BASE_HZ = 55f

        fun logBinFrequency(bin: Int): Float = LOG_SPECTRUM_BASE_HZ * 2f.pow(bin / 12f)

        /** Converts a frequency ratio to semitones. */
        fun semitones(ratio: Float): Float = 12f * log2(ratio)
    }
}
