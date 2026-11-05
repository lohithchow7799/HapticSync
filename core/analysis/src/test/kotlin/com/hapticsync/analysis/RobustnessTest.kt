package com.hapticsync.analysis

import com.hapticsync.dsp.synth.DemoClips
import com.hapticsync.dsp.synth.DemoScenario
import com.hapticsync.dsp.synth.SynthLabel
import com.hapticsync.model.BandId
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Edge cases: silence, very quiet and clipped audio, mono input, settings that change analysis. */
class RobustnessTest {

    private val groove = DemoClips.render(DemoScenario.DRUM_GROOVE)

    private fun scaled(gain: Float, clip: Boolean = false): FloatArray {
        val x = groove.interleaved()
        for (i in x.indices) {
            x[i] *= gain
            if (clip) x[i] = x[i].coerceIn(-1f, 1f)
        }
        return x
    }

    private fun kickRecall(result: ClipRunner.Result): Double {
        val kicks = groove.times(SynthLabel.KICK)
        return kicks.count { t -> result.events.any { abs(it.timeUs / 1e6 - t) < 0.03 } }.toDouble() / kicks.size
    }

    @Test
    fun silenceProducesNothing() {
        val silent = FloatArray(44_100 * 2 * 5)
        val result = ClipRunner.run(groove, interleaved = silent)
        assertTrue(result.events.isEmpty())
        assertTrue(result.frames.all { f -> f.continuous.all { it == 0f } })
    }

    @Test
    fun audioBelowTheMinimumAmplitudeIsIgnored() {
        // −66 dB below the clip's normalised level: under the default −54 dBFS floor.
        val result = ClipRunner.run(groove, interleaved = scaled(0.0005f))
        assertTrue("events in near-silence: ${result.events.size}", result.events.isEmpty())
    }

    @Test
    fun quietAudioStillDrivesDetectionThroughNormalisation() {
        // −24 dB: a quiet mix. Adaptive normalisation keeps detection working.
        val result = ClipRunner.run(groove, interleaved = scaled(0.063f))
        assertTrue("quiet kick recall ${kickRecall(result)}", kickRecall(result) >= 0.8)
        val loud = ClipRunner.run(groove)
        val quietStrength = result.events.map { it.strength }.average()
        val loudStrength = loud.events.map { it.strength }.average()
        assertTrue("quiet events are not stronger than loud ones", quietStrength <= loudStrength + 0.05)
    }

    @Test
    fun clippedAudioIsHandled() {
        val result = ClipRunner.run(groove, interleaved = scaled(6f, clip = true))
        assertTrue(result.frames.all { it.rmsDb.isFinite() })
        assertTrue("clipped kick recall ${kickRecall(result)}", kickRecall(result) >= 0.7)
    }

    @Test
    fun monoInputWorks() {
        val stereo = groove.interleaved()
        val mono = FloatArray(stereo.size / 2) { 0.5f * (stereo[2 * it] + stereo[2 * it + 1]) }
        val result = ClipRunner.run(groove, interleaved = mono, channels = 1)
        assertTrue(kickRecall(result) >= 0.85)
    }

    @Test
    fun disablingLowBandsRemovesTheirContribution() {
        val profile = HapticProfile()
            .updateBand(BandId.SUB_BASS) { it.copy(enabled = false) }
            .updateBand(BandId.BASS) { it.copy(enabled = false) }
        val result = ClipRunner.run(groove, profile = profile)
        assertTrue(
            "no bass drums without low bands",
            result.eventsOf(SoundCategory.BASS_DRUM, SoundCategory.BASS_DROP).isEmpty(),
        )
        assertTrue(result.frames.all { it.continuous[SoundCategory.CINEMATIC_RUMBLE.ordinal] < 0.05f })
    }

    @Test
    fun narrowingTheAnalysisRangeExcludesHighFrequencies() {
        val profile = HapticProfile().copy(analysis = HapticProfile().analysis.copy(rangeHighHz = 1500f))
        val result = ClipRunner.run(groove, profile = profile)
        assertTrue(result.eventsOf(SoundCategory.CYMBAL).isEmpty())
    }

    @Test
    fun lowerSensitivityDetectsFewerEvents() {
        fun withSensitivity(s: Float): HapticProfile {
            var p = HapticProfile().copy(haptics = HapticProfile().haptics.copy(eventSensitivity = s))
            for (b in BandId.entries) p = p.updateBand(b) { it.copy(sensitivity = s) }
            return p
        }
        val sensitive = ClipRunner.run(groove, profile = withSensitivity(0.95f)).events.size
        val insensitive = ClipRunner.run(groove, profile = withSensitivity(0.05f)).events.size
        assertTrue("sensitive $sensitive vs insensitive $insensitive", sensitive > insensitive)
    }

    @Test
    fun resetClearsState() {
        val events = mutableListOf<SoundEvent>()
        val analyzer = AudioAnalyzer(HapticProfile(), 44_100) { events += it }
        val hop = analyzer.config.hopSize
        val mid = FloatArray(hop) { 0.5f }
        val side = FloatArray(hop)
        repeat(50) { analyzer.process(mid, side, 0, it * 10_000L) }
        analyzer.reset()
        val f = analyzer.process(FloatArray(hop), side, 0, 0)
        assertEquals(0f, f.onsetStrength, 0f)
        assertTrue(f.silent)
    }
}
