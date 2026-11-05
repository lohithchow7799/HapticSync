package com.hapticsync.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Forward FFT for real-valued input of power-of-two length [size].
 *
 * The transform packs the `N` real samples into an `N/2`-point complex sequence, runs an
 * iterative in-place radix-2 FFT on it and separates the even/odd spectra in a final
 * post-processing pass. This halves the work of a naive complex FFT.
 *
 * All tables and scratch buffers are allocated once in the constructor, so [forward] and
 * [magnitudes] are allocation-free and safe to call from a real-time audio thread.
 * Instances are not thread-safe; use one per thread.
 *
 * Output convention: `X[k] = Σ x[n]·e^(-2πi·k·n/N)` for `k = 0..N/2` (unnormalised).
 */
class RealFft(val size: Int) {

    init {
        require(size >= 4 && (size and (size - 1)) == 0) { "FFT size must be a power of two >= 4, was $size" }
    }

    private val half = size / 2
    private val bins = half + 1

    private val workRe = FloatArray(half)
    private val workIm = FloatArray(half)
    private val bitReverse = IntArray(half)

    // Twiddles for the N/2-point complex FFT: exp(-2πi·k/(N/2)), k in [0, N/4).
    private val twiddleCos = FloatArray(maxOf(1, half / 2))
    private val twiddleSin = FloatArray(maxOf(1, half / 2))

    // Twiddles for the real-spectrum post-processing: exp(-2πi·k/N), k in [0, N/2].
    private val postCos = FloatArray(bins)
    private val postSin = FloatArray(bins)

    /** Number of output bins (`size / 2 + 1`). */
    val binCount: Int get() = bins

    init {
        val bits = Integer.numberOfTrailingZeros(half)
        for (i in 0 until half) {
            bitReverse[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits)
        }
        for (k in twiddleCos.indices) {
            val angle = -2.0 * PI * k / half
            twiddleCos[k] = cos(angle).toFloat()
            twiddleSin[k] = sin(angle).toFloat()
        }
        for (k in 0 until bins) {
            val angle = -2.0 * PI * k / size
            postCos[k] = cos(angle).toFloat()
            postSin[k] = sin(angle).toFloat()
        }
    }

    /**
     * Transforms [input] (`size` samples starting at [inputOffset]) and writes the
     * `size / 2 + 1` complex bins into [outRe] / [outIm].
     */
    fun forward(input: FloatArray, outRe: FloatArray, outIm: FloatArray, inputOffset: Int = 0) {
        require(input.size - inputOffset >= size) { "Input too short" }
        require(outRe.size >= bins && outIm.size >= bins) { "Output arrays need $bins bins" }

        // Pack even samples into the real part and odd samples into the imaginary part, in
        // bit-reversed order, ready for the iterative butterflies.
        for (i in 0 until half) {
            val j = bitReverse[i]
            workRe[j] = input[inputOffset + 2 * i]
            workIm[j] = input[inputOffset + 2 * i + 1]
        }
        complexFftInPlace()

        // Separate the spectra of the even and odd samples and combine them:
        //   X[k] = E[k] + W^k · O[k],  with E/O recovered from Z[k] and conj(Z[N/2 - k]).
        val z0Re = workRe[0]
        val z0Im = workIm[0]
        outRe[0] = z0Re + z0Im
        outIm[0] = 0f
        outRe[half] = z0Re - z0Im
        outIm[half] = 0f
        for (k in 1 until half) {
            val zkRe = workRe[k]
            val zkIm = workIm[k]
            val znRe = workRe[half - k]
            val znIm = -workIm[half - k] // conjugate
            val eRe = 0.5f * (zkRe + znRe)
            val eIm = 0.5f * (zkIm + znIm)
            // O[k] = (Z[k] - conj(Z[N/2-k])) / (2i)
            val dRe = zkRe - znRe
            val dIm = zkIm - znIm
            val oRe = 0.5f * dIm
            val oIm = -0.5f * dRe
            val wRe = postCos[k]
            val wIm = postSin[k]
            outRe[k] = eRe + (wRe * oRe - wIm * oIm)
            outIm[k] = eIm + (wRe * oIm + wIm * oRe)
        }
    }

    /**
     * Computes the magnitude spectrum `|X[k]|` of [input] into [outMagnitude]
     * (`size / 2 + 1` values). [scratchRe] / [scratchIm] must hold at least `size / 2 + 1` values.
     */
    fun magnitudes(
        input: FloatArray,
        outMagnitude: FloatArray,
        scratchRe: FloatArray,
        scratchIm: FloatArray,
        inputOffset: Int = 0,
    ) {
        forward(input, scratchRe, scratchIm, inputOffset)
        for (k in 0 until bins) {
            val re = scratchRe[k]
            val im = scratchIm[k]
            outMagnitude[k] = sqrt(re * re + im * im)
        }
    }

    private fun complexFftInPlace() {
        val n = half
        var length = 2
        while (length <= n) {
            val halfLength = length / 2
            val tableStride = n / length
            var start = 0
            while (start < n) {
                var t = 0
                for (k in 0 until halfLength) {
                    val wRe = twiddleCos[t]
                    val wIm = twiddleSin[t]
                    val a = start + k
                    val b = a + halfLength
                    val bRe = workRe[b] * wRe - workIm[b] * wIm
                    val bIm = workRe[b] * wIm + workIm[b] * wRe
                    workRe[b] = workRe[a] - bRe
                    workIm[b] = workIm[a] - bIm
                    workRe[a] += bRe
                    workIm[a] += bIm
                    t += tableStride
                }
                start += length
            }
            length = length shl 1
        }
    }

    companion object {
        /** Smallest power of two that is `>= n` (and at least 4). */
        fun nextPowerOfTwo(n: Int): Int {
            var p = 4
            while (p < n) p = p shl 1
            return p
        }

        /** Centre frequency of FFT bin [bin] for an FFT of [fftSize] at [sampleRate] Hz. */
        fun binFrequency(bin: Int, fftSize: Int, sampleRate: Float): Float = bin * sampleRate / fftSize
    }
}
