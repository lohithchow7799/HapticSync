package com.hapticsync.dsp

/**
 * Integer-factor decimator: 4th-order Butterworth anti-aliasing low-pass followed by
 * down-sampling. Used to run the pitch tracker on a low-rate copy of the signal, where a short
 * FFT gives fine frequency resolution at low cost.
 */
class Decimator {
    private var factor = 1
    private var phase = 0
    private var filter = BiquadCascade(2)

    /** Output sample rate (Hz). */
    var outputRate: Double = 0.0
        private set

    /**
     * Chooses the largest integer factor that keeps the output rate at or above
     * [minOutputRate] and configures the anti-aliasing filter.
     */
    fun configure(inputRate: Int, minOutputRate: Int) {
        factor = maxOf(1, inputRate / minOutputRate)
        outputRate = inputRate.toDouble() / factor
        val cutoff = 0.40 * outputRate
        filter = BiquadCascade.lowPass4(inputRate.toDouble(), cutoff)
        phase = 0
    }

    val decimationFactor: Int get() = factor

    /**
     * Processes [count] samples from [input] and writes decimated samples into [output]
     * starting at [outOffset]. Returns the number of samples written.
     */
    fun process(input: FloatArray, offset: Int, count: Int, output: FloatArray, outOffset: Int): Int {
        var written = 0
        for (i in offset until offset + count) {
            val y = filter.process(input[i].toDouble())
            if (phase == 0) {
                output[outOffset + written] = y.toFloat()
                written++
            }
            phase++
            if (phase >= factor) phase = 0
        }
        filter.sanitize()
        return written
    }

    /** Upper bound of output samples produced from [inputCount] input samples. */
    fun maxOutput(inputCount: Int): Int = inputCount / factor + 1

    fun reset() {
        filter.reset()
        phase = 0
    }
}
