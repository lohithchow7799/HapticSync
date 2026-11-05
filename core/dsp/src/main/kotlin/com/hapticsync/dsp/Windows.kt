package com.hapticsync.dsp

import kotlin.math.PI
import kotlin.math.cos

/** Analysis window functions. */
object Windows {

    /**
     * Periodic Hann window of [length] samples (the variant suited to overlapping STFT frames).
     */
    fun hann(length: Int): FloatArray {
        require(length > 0)
        return FloatArray(length) { n -> (0.5 - 0.5 * cos(2.0 * PI * n / length)).toFloat() }
    }

    /** Periodic Blackman window, for lower spectral leakage at the cost of wider main lobes. */
    fun blackman(length: Int): FloatArray {
        require(length > 0)
        return FloatArray(length) { n ->
            val x = 2.0 * PI * n / length
            (0.42 - 0.5 * cos(x) + 0.08 * cos(2 * x)).toFloat()
        }
    }

    /** Sum of the window coefficients; `2·|X[k]| / sum` recovers a sinusoid's amplitude. */
    fun coherentGain(window: FloatArray): Float {
        var sum = 0.0
        for (w in window) sum += w
        return sum.toFloat()
    }
}
