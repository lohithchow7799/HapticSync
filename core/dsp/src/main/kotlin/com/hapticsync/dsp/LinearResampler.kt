package com.hapticsync.dsp

/**
 * Streaming sample-rate converter (anti-aliasing low-pass followed by linear interpolation).
 * Adequate for feeding a classifier model; not intended for listening.
 */
class LinearResampler(inputRate: Int, val outputRate: Int) {
    private val step = inputRate.toDouble() / outputRate
    private val filter = BiquadCascade.lowPass4(inputRate.toDouble(), 0.45 * minOf(inputRate, outputRate))
    private var position = 0.0 // fractional input index of the next output sample, relative to the previous sample
    private var previous = 0f

    /** Upper bound of output samples for [inputCount] input samples. */
    fun maxOutput(inputCount: Int): Int = (inputCount / step).toInt() + 2

    /** Resamples [count] samples of [input] into [output]; returns the number written. */
    fun process(input: FloatArray, offset: Int, count: Int, output: FloatArray, outOffset: Int): Int {
        var written = 0
        for (i in 0 until count) {
            val current = filter.process(input[offset + i].toDouble()).toFloat()
            // Emit all output samples that fall between the previous and current input sample.
            while (position <= 1.0) {
                val frac = position.toFloat()
                if (outOffset + written < output.size) {
                    output[outOffset + written] = previous + (current - previous) * frac
                    written++
                }
                position += step
            }
            position -= 1.0
            previous = current
        }
        filter.sanitize()
        return written
    }

    fun reset() {
        filter.reset()
        position = 0.0
        previous = 0f
    }
}
