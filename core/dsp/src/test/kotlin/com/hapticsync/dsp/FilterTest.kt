package com.hapticsync.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class FilterTest {

    private val sr = 48_000

    private fun sine(freq: Double, seconds: Double, amp: Float = 1f, rate: Int = sr) =
        FloatArray((rate * seconds).toInt()) { t -> (amp * sin(2 * PI * freq * t / rate)).toFloat() }

    @Test
    fun butterworthSecondOrderIsMinus3dBAtCutoff() {
        val lp = Biquad().apply { setLowPass(sr.toDouble(), 1000.0, Biquad.BUTTERWORTH2_Q) }
        val hp = Biquad().apply { setHighPass(sr.toDouble(), 1000.0, Biquad.BUTTERWORTH2_Q) }
        assertEquals(1 / sqrt(2.0), lp.magnitudeAt(1000.0, sr.toDouble()), 1e-3)
        assertEquals(1 / sqrt(2.0), hp.magnitudeAt(1000.0, sr.toDouble()), 1e-3)
        assertEquals(1.0, lp.magnitudeAt(10.0, sr.toDouble()), 1e-3)
        assertTrue(lp.magnitudeAt(10_000.0, sr.toDouble()) < 0.02)
    }

    @Test
    fun fourthOrderButterworthRollsOff24dBPerOctave() {
        val lp = BiquadCascade.lowPass4(sr.toDouble(), 500.0)
        assertEquals(1 / sqrt(2.0), lp.magnitudeAt(500.0, sr.toDouble()), 5e-3)
        val oneOctaveUp = lp.magnitudeAt(1000.0, sr.toDouble())
        val twoOctavesUp = lp.magnitudeAt(2000.0, sr.toDouble())
        assertEquals(24.0, 20 * kotlin.math.log10(oneOctaveUp / twoOctavesUp), 1.5)
    }

    @Test
    fun subBassFilterIsAccurateAtHighSampleRates() {
        // 20 Hz at 48 kHz is the numerically hardest case; the measured gain must match theory.
        val hp = BiquadCascade.highPass4(sr.toDouble(), 20.0)
        val x = sine(40.0, 2.0)
        var ss = 0.0
        for (i in x.indices) {
            val y = hp.process(x[i].toDouble())
            if (i > sr) ss += y * y
        }
        val measured = sqrt(ss / (x.size - sr)) * sqrt(2.0)
        assertEquals(hp.magnitudeAt(40.0, sr.toDouble()), measured, 0.01)
    }

    @Test
    fun filterBankRoutesSinesToTheirBands() {
        val bank = BandFilterBank(6)
        val lows = floatArrayOf(20f, 60f, 150f, 400f, 2000f, 6000f)
        val highs = floatArrayOf(60f, 150f, 400f, 2000f, 6000f, 12000f)
        bank.configure(sr, lows, highs)
        val tests = mapOf(35.0 to 0, 95.0 to 1, 250.0 to 2, 900.0 to 3, 3500.0 to 4, 9000.0 to 5)
        for ((freq, expectedBand) in tests) {
            bank.reset()
            val x = sine(freq, 0.5)
            // Settle, then measure the last 100 ms.
            bank.process(x, 0, x.size - 4800)
            bank.endBlock()
            bank.process(x, x.size - 4800, 4800)
            bank.endBlock()
            val loudest = bank.bandEnergy.indices.maxBy { bank.bandEnergy[it] }
            assertEquals("sine at $freq Hz", expectedBand, loudest)
            // A full-scale sine has mean square 0.5; the in-band filter passes most of it.
            assertTrue("in-band energy at $freq: ${bank.bandEnergy[expectedBand]}", bank.bandEnergy[expectedBand] > 0.2f)
        }
    }

    @Test
    fun bandsAboveNyquistAreUnavailable() {
        val bank = BandFilterBank(6)
        bank.configure(16_000, floatArrayOf(20f, 60f, 150f, 400f, 2000f, 7900f), floatArrayOf(60f, 150f, 400f, 2000f, 6000f, 12000f))
        assertTrue(bank.isAvailable(4))
        assertFalse(bank.isAvailable(5))
        val x = sine(5000.0, 0.2, rate = 16_000)
        bank.process(x, 0, x.size)
        bank.endBlock()
        assertEquals(0f, bank.bandEnergy[5], 0f)
    }

    @Test
    fun decimatorRejectsAliases() {
        val d = Decimator()
        d.configure(48_000, 8_000)
        assertEquals(6, d.decimationFactor)
        assertEquals(8000.0, d.outputRate, 1e-9)
        // 7 kHz would alias to 1 kHz at 8 kHz output; it must be strongly attenuated.
        val alias = sine(7000.0, 0.5)
        val pass = sine(500.0, 0.5)
        val out = FloatArray(d.maxOutput(alias.size))
        val n = d.process(alias, 0, alias.size, out, 0)
        val aliasRms = rms(out, n / 2, n)
        d.reset()
        val n2 = d.process(pass, 0, pass.size, out, 0)
        val passRms = rms(out, n2 / 2, n2)
        assertTrue("pass $passRms", passRms > 0.6f)
        assertTrue("alias $aliasRms", aliasRms < 0.05f)
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Float {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from)).toFloat()
    }
}
