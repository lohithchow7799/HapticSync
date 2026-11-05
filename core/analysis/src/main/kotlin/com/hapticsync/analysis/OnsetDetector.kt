package com.hapticsync.analysis

import com.hapticsync.dsp.RunningStats
import com.hapticsync.model.BandId
import kotlin.math.max

/**
 * Onset detection.
 *
 * Detection function: the sum over bands of each band's level *rise* versus the maximum of its
 * two previous values (a band-wise variant of "SuperFlux" that ignores vibrato and slow swells),
 * weighted towards the low bands that matter most for haptics, plus spectral flux.
 *
 * Threshold: adaptive — running mean plus a multiple of the running standard deviation of the
 * detection function, offset by a minimum delta. Higher sensitivity lowers both terms.
 *
 * Peak picking: a frame is an onset when its detection value exceeds the threshold, is a local
 * maximum, and at least the configured pulse spacing has elapsed since the previous onset.
 * Confirmation therefore lags one hop; the onset is reported for the previous hop.
 */
internal class OnsetDetector(private var config: AnalysisConfig) {

    private val previous1 = FloatArray(BandId.COUNT) { -120f }
    private val previous2 = FloatArray(BandId.COUNT) { -120f }
    private val stats = RunningStats(timeConstantMs = 1200f, updateRateHz = config.hopRateHz)

    private var odfPrev2 = 0f
    private var odfPrev1 = 0f
    private var thresholdPrev1 = Float.MAX_VALUE
    private var hopsSinceOnset = 10_000
    private var primedHops = 0

    /** Detection-function value of the latest hop. */
    var value = 0f; private set

    /** Threshold for the latest hop. */
    var threshold = 0f; private set

    /** Detection value of the onset hop when [update] returns true. */
    var onsetValue = 0f; private set

    /** Ratio of the onset's detection value to its threshold. */
    var onsetRatio = 0f; private set

    fun setConfig(newConfig: AnalysisConfig) {
        config = newConfig
        stats.setTimeConstant(1200f, newConfig.hopRateHz)
    }

    /**
     * Processes one hop. Returns `true` when the *previous* hop is confirmed as an onset.
     *
     * @param bandDb smoothed band levels.
     * @param bandActive whether each band is enabled and above its thresholds.
     * @param flux spectral flux of this hop.
     * @param silent whether the hop is below the minimum detectable amplitude.
     */
    fun update(bandDb: FloatArray, bandActive: BooleanArray, flux: Float, silent: Boolean): Boolean {
        var odf = 0f
        for (b in 0 until BandId.COUNT) {
            val reference = max(previous1[b], previous2[b])
            if (bandActive[b]) {
                val rise = bandDb[b] - reference - RISE_TOLERANCE_DB[b]
                if (rise > 0f) odf += BAND_WEIGHT[b] * (0.5f + config.bandSensitivity[b]) * rise
            }
            previous2[b] = previous1[b]
            previous1[b] = bandDb[b]
        }
        odf += FLUX_WEIGHT * flux
        if (silent) odf = 0f
        value = odf

        val s = sensitivity()
        val c = Fuzzy.lerp(2.6f, 0.9f, s)
        val delta = Fuzzy.lerp(10f, 2.5f, s)
        val thr = stats.mean + c * stats.stdDev + delta
        threshold = thr
        // Clip outliers so that a single huge onset does not desensitise the detector for seconds.
        stats.update(minOf(odf, stats.mean + 4f * stats.stdDev + delta))
        primedHops++

        hopsSinceOnset++
        val candidate = odfPrev1
        val isOnset = primedHops > 3 &&
            candidate > thresholdPrev1 &&
            candidate >= odf &&
            candidate > odfPrev2 &&
            hopsSinceOnset > config.refractoryHops
        if (isOnset) {
            hopsSinceOnset = 1 // the onset happened one hop ago
            onsetValue = candidate
            onsetRatio = candidate / max(1e-3f, thresholdPrev1)
        }
        odfPrev2 = odfPrev1
        odfPrev1 = odf
        thresholdPrev1 = thr
        return isOnset
    }

    private fun sensitivity(): Float {
        var sum = 0f
        var n = 0
        for (b in 0 until BandId.COUNT) if (config.bandEnabled[b]) { sum += config.bandSensitivity[b]; n++ }
        val bands = if (n > 0) sum / n else 0.5f
        return (0.5f * config.globalSensitivity + 0.5f * bands).coerceIn(0f, 1f)
    }

    fun reset() {
        previous1.fill(-120f)
        previous2.fill(-120f)
        stats.reset()
        odfPrev1 = 0f
        odfPrev2 = 0f
        thresholdPrev1 = Float.MAX_VALUE
        hopsSinceOnset = 10_000
        primedHops = 0
        value = 0f
        threshold = 0f
    }

    companion object {
        /**
         * Rises smaller than this are ignored per band. Narrow low bands fluctuate by several dB
         * even for steady noise (wind, rumble), so they need a larger tolerance.
         */
        private val RISE_TOLERANCE_DB = floatArrayOf(3f, 2.5f, 2f, 1.5f, 1f, 1f)

        /** Low bands carry the energy that is felt; weight them up. */
        private val BAND_WEIGHT = floatArrayOf(1.2f, 1.2f, 1.0f, 0.9f, 0.8f, 0.6f)
        private const val FLUX_WEIGHT = 12f
    }
}
