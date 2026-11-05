package com.hapticsync.dsp

import com.hapticsync.dsp.synth.EngineSynth
import com.hapticsync.dsp.synth.Prng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PitchAndSmoothingTest {

    private val sr = 48_000

    private fun harmonicTone(f0: Double, seconds: Double, harmonics: Int = 6): FloatArray =
        FloatArray((sr * seconds).toInt()) { t ->
            var v = 0.0
            for (h in 1..harmonics) v += sin(2 * PI * f0 * h * t / sr) / h
            (0.3 * v).toFloat()
        }

    private fun track(signal: FloatArray, hop: Int = 480, onFrame: (PitchTracker, Int) -> Unit) {
        val tracker = PitchTracker(sr)
        var pos = 0
        var frame = 0
        while (pos + hop <= signal.size) {
            tracker.push(signal, pos, hop)
            tracker.analyze()
            onFrame(tracker, frame++)
            pos += hop
        }
    }

    @Test
    fun estimatesFundamentalOfHarmonicTones() {
        for (f0 in doubleArrayOf(45.0, 110.0, 230.0, 520.0)) {
            var last = 0f
            var harmonicity = 0f
            track(harmonicTone(f0, 0.6)) { t, _ -> last = t.f0Hz; harmonicity = t.harmonicity }
            assertEquals("f0 for $f0 Hz", f0, last.toDouble(), f0 * 0.03)
            assertTrue("harmonicity for $f0 Hz was $harmonicity", harmonicity > 0.6f)
        }
    }

    @Test
    fun noiseHasLowHarmonicity() {
        val rng = Prng(3)
        val noise = FloatArray(sr) { rng.bipolar() * 0.3f }
        var h = 1f
        track(noise) { t, _ -> h = t.harmonicity }
        assertTrue("noise harmonicity $h", h < 0.35f)
    }

    @Test
    fun followsEngineSweepUpwards() {
        val engine = EngineSynth(sr, cylinders = 6, seed = 4)
            .render(2.0, rpmAt = { t -> 5000 + 3000 * t }, throttleAt = { 1.0 })
        val estimates = mutableListOf<Float>()
        track(engine) { t, frame -> if (frame > 20 && t.harmonicity > 0.3f) estimates += t.f0Hz }
        assertTrue("too few voiced frames: ${estimates.size}", estimates.size > 100)
        // Firing frequency goes from 250 Hz to 550 Hz: early estimates must be well below late ones.
        val early = estimates.take(20).average()
        val late = estimates.takeLast(20).average()
        assertTrue("early $early late $late", late > early * 1.6)
    }

    @Test
    fun logSpectrumShiftsWithPitch() {
        val lowTone = harmonicTone(110.0, 0.4)
        val highTone = harmonicTone(220.0, 0.4)
        var low = FloatArray(0)
        var high = FloatArray(0)
        track(lowTone) { t, _ -> low = t.logSpectrumDb.copyOf() }
        track(highTone) { t, _ -> high = t.logSpectrumDb.copyOf() }
        // 110 Hz is semitone bin 12 above 55 Hz; 220 Hz is bin 24.
        assertEquals(12, low.indices.maxBy { low[it] })
        assertEquals(24, high.indices.maxBy { high[it] })
        assertEquals(12f, PitchTracker.semitones(2f), 1e-4f)
    }

    @Test
    fun envelopeFollowerAttackIsFasterThanRelease() {
        val env = EnvelopeFollower(attackMs = 10f, releaseMs = 200f, updateRateHz = 100f)
        repeat(3) { env.process(1f) }
        val afterAttack = env.value
        repeat(3) { env.process(0f) }
        val afterRelease = env.value
        assertTrue(afterAttack > 0.9f)
        assertTrue("release too fast: $afterRelease", afterRelease > 0.8f * afterAttack)
    }

    @Test
    fun runningStatsConverge() {
        val stats = RunningStats(timeConstantMs = 500f, updateRateHz = 100f)
        val rng = Prng(8)
        repeat(5000) { stats.update(3f + rng.gaussian()) }
        assertEquals(3f, stats.mean, 0.25f)
        assertEquals(1f, stats.stdDev, 0.3f)
    }

    @Test
    fun noiseFloorIgnoresTransients() {
        val floor = NoiseFloorTracker(riseMs = 5000f, fallMs = 300f, updateRateHz = 100f)
        repeat(300) { floor.update(-60f) }
        repeat(5) { floor.update(-10f) } // a 50 ms transient
        assertTrue("floor rose to ${floor.floorDb}", floor.floorDb < -58f)
        repeat(300) { floor.update(-70f) }
        assertTrue("floor should follow decreases: ${floor.floorDb}", abs(floor.floorDb + 70f) < 1f)
    }

    @Test
    fun historyKeepsMostRecentValues() {
        val h = FloatHistory(4)
        for (v in 1..6) h.push(v.toFloat())
        assertEquals(4, h.size)
        assertEquals(6f, h[0], 0f)
        assertEquals(3f, h[3], 0f)
        assertEquals(4.5f, h.mean(), 1e-6f)
        assertEquals(6f, h.max(), 0f)
        assertEquals(3f, h.min(), 0f)
    }

    @Test
    fun decibelConversionsRoundTrip() {
        assertEquals(-6.0206f, Db.fromAmplitude(0.5f), 1e-3f)
        assertEquals(0.5f, Db.toAmplitude(-6.0206f), 1e-4f)
        assertEquals(-3.0103f, Db.fromPower(0.5f), 1e-3f)
        assertEquals(Db.FLOOR_DB, Db.fromPower(0f), 0f)
    }
}
