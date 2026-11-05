package com.hapticsync.dsp.synth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SynthTest {

    @Test
    fun everyScenarioRendersFiniteNormalisedAudioWithGroundTruth() {
        for (scenario in DemoScenario.entries) {
            val clip = DemoClips.render(scenario, 22_050)
            assertTrue("$scenario has no ground truth", clip.truth.isNotEmpty())
            assertTrue("$scenario too short", clip.durationSec > 10.0)
            var peak = 0f
            for (i in clip.left.indices) {
                assertTrue("$scenario non-finite sample", clip.left[i].isFinite() && clip.right[i].isFinite())
                peak = maxOf(peak, kotlin.math.abs(clip.left[i]), kotlin.math.abs(clip.right[i]))
            }
            assertTrue("$scenario peak $peak", peak in 0.3f..1.0f)
            for (gt in clip.truth) assertTrue("$scenario label beyond end: $gt", gt.startSec <= clip.durationSec)
        }
    }

    @Test
    fun renderingIsDeterministic() {
        val a = DemoClips.render(DemoScenario.DRUM_GROOVE, 22_050)
        val b = DemoClips.render(DemoScenario.DRUM_GROOVE, 22_050)
        assertArrayEquals(a.left, b.left, 0f)
    }

    @Test
    fun wavHeaderIsValid() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f)
        val out = ByteArrayOutputStream()
        WavWriter.write16(out, samples, 44_100, 2)
        val bytes = out.toByteArray()
        assertEquals(44 + samples.size * 2, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, bb.getShort(20).toInt())        // PCM
        assertEquals(2, bb.getShort(22).toInt())        // channels
        assertEquals(44_100, bb.getInt(24))
        assertEquals(16, bb.getShort(34).toInt())
        assertEquals(samples.size * 2, bb.getInt(40))
        assertEquals(16384, bb.getShort(46).toInt())    // 0.5 → 16383.5 rounds to 16384
        assertEquals(32767, bb.getShort(50).toInt())
    }

    @Test
    fun prngIsUniformAndBounded() {
        val rng = Prng(42)
        var sum = 0.0
        repeat(100_000) {
            val v = rng.nextFloat()
            assertTrue(v >= 0f && v < 1f)
            sum += v
        }
        assertEquals(0.5, sum / 100_000, 0.01)
    }

    @Test
    fun engineCurveInterpolates() {
        val c = EngineSynth.curve(0.0 to 0.0, 1.0 to 10.0, 2.0 to 0.0)
        assertEquals(5.0, c(0.5), 1e-9)
        assertEquals(10.0, c(1.0), 1e-9)
        assertEquals(0.0, c(5.0), 1e-9)
        assertEquals(0.0, c(-1.0), 1e-9)
    }
}
