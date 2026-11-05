package com.hapticsync.dsp

import com.hapticsync.dsp.synth.Prng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RealFftTest {

    private fun naiveDft(x: FloatArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val re = DoubleArray(n / 2 + 1)
        val im = DoubleArray(n / 2 + 1)
        for (k in 0..n / 2) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val a = -2 * PI * k * t / n
                sr += x[t] * cos(a)
                si += x[t] * sin(a)
            }
            re[k] = sr
            im[k] = si
        }
        return re to im
    }

    @Test
    fun matchesNaiveDftForSeveralSizes() {
        val rng = Prng(1234)
        for (size in intArrayOf(4, 8, 16, 64, 256, 1024)) {
            val x = FloatArray(size) { rng.bipolar() }
            val fft = RealFft(size)
            val re = FloatArray(fft.binCount)
            val im = FloatArray(fft.binCount)
            fft.forward(x, re, im)
            val (er, ei) = naiveDft(x)
            val tolerance = 1e-3 * sqrt(size.toDouble())
            for (k in 0 until fft.binCount) {
                assertEquals("re[$k] size $size", er[k], re[k].toDouble(), tolerance)
                assertEquals("im[$k] size $size", ei[k], im[k].toDouble(), tolerance)
            }
        }
    }

    @Test
    fun sineConcentratesInItsBin() {
        val n = 2048
        val bin = 37
        val x = FloatArray(n) { t -> sin(2 * PI * bin * t / n).toFloat() }
        val fft = RealFft(n)
        val mag = FloatArray(fft.binCount)
        fft.magnitudes(x, mag, FloatArray(fft.binCount), FloatArray(fft.binCount))
        assertEquals(n / 2.0, mag[bin].toDouble(), 1e-1)
        for (k in mag.indices) if (k != bin) assertTrue("leak at $k: ${mag[k]}", mag[k] < 1e-2)
    }

    @Test
    fun parsevalHolds() {
        val n = 512
        val rng = Prng(99)
        val x = FloatArray(n) { rng.bipolar() }
        val fft = RealFft(n)
        val re = FloatArray(fft.binCount)
        val im = FloatArray(fft.binCount)
        fft.forward(x, re, im)
        var timeEnergy = 0.0
        for (v in x) timeEnergy += v * v
        var freqEnergy = re[0].toDouble() * re[0] + re[n / 2].toDouble() * re[n / 2]
        for (k in 1 until n / 2) freqEnergy += 2.0 * (re[k].toDouble() * re[k] + im[k].toDouble() * im[k])
        freqEnergy /= n
        assertEquals(timeEnergy, freqEnergy, timeEnergy * 1e-4)
    }

    @Test
    fun dcAndNyquistAreReal() {
        val n = 64
        val x = FloatArray(n) { t -> 0.5f + if (t % 2 == 0) 0.25f else -0.25f }
        val fft = RealFft(n)
        val re = FloatArray(fft.binCount)
        val im = FloatArray(fft.binCount)
        fft.forward(x, re, im)
        assertEquals(0.5 * n, re[0].toDouble(), 1e-4)
        assertEquals(0.25 * n, re[n / 2].toDouble(), 1e-4)
        assertEquals(0.0, im[0].toDouble(), 1e-6)
        assertEquals(0.0, im[n / 2].toDouble(), 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwo() {
        RealFft(1000)
    }

    @Test
    fun nextPowerOfTwo() {
        assertEquals(1024, RealFft.nextPowerOfTwo(1000))
        assertEquals(2048, RealFft.nextPowerOfTwo(1536))
        assertEquals(4, RealFft.nextPowerOfTwo(1))
        assertTrue(abs(RealFft.binFrequency(10, 1024, 48_000f) - 468.75f) < 1e-3)
    }
}
