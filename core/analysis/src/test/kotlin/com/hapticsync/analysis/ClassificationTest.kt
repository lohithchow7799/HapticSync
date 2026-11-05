package com.hapticsync.analysis

import com.hapticsync.dsp.synth.DemoClips
import com.hapticsync.dsp.synth.DemoScenario
import com.hapticsync.dsp.synth.SynthClip
import com.hapticsync.dsp.synth.SynthLabel
import com.hapticsync.model.SoundCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End-to-end detection tests on the synthesised scenes. Thresholds leave headroom: the goal is
 * to catch regressions in the DSP classifier, not to demand perfection from heuristics.
 */
class ClassificationTest {

    private val cache = HashMap<DemoScenario, Pair<SynthClip, ClipRunner.Result>>()

    private fun run(scenario: DemoScenario) = cache.getOrPut(scenario) {
        val clip = DemoClips.render(scenario)
        clip to ClipRunner.run(clip)
    }

    /** Fraction of ground-truth times matched by an event of [categories] within [toleranceSec]. */
    private fun recall(
        result: ClipRunner.Result,
        truthTimes: List<Double>,
        toleranceSec: Double,
        vararg categories: SoundCategory,
    ): Double {
        if (truthTimes.isEmpty()) return 1.0
        val candidates = if (categories.isEmpty()) result.events else result.eventsOf(*categories)
        val hits = truthTimes.count { t -> candidates.any { abs(it.timeUs / 1e6 - t) <= toleranceSec } }
        return hits.toDouble() / truthTimes.size
    }

    @Test
    fun racingUpshiftsAreGearShifts() {
        val (clip, result) = run(DemoScenario.RACING_ONBOARD)
        val shifts = clip.times(SynthLabel.GEAR_SHIFT)
        assertTrue("gear-shift recall", recall(result, shifts, 0.08, SoundCategory.GEAR_SHIFT) >= 0.75)
        // No spurious shifts during idle.
        assertTrue(result.eventsOf(SoundCategory.GEAR_SHIFT).none { it.timeUs < 5_500_000 })
    }

    @Test
    fun racingEngineStatesFollowTheThrottle() {
        val (_, result) = run(DemoScenario.RACING_ONBOARD)
        val engineDuringRun = result.meanOf(6.0, 16.0) { it.engine }
        assertTrue("engine likelihood $engineDuringRun", engineDuringRun > 0.7f)
        val accelerating = result.meanLevel(SoundCategory.ACCELERATION, 6.0, 7.3)
        val idling = result.meanLevel(SoundCategory.ACCELERATION, 0.5, 1.8)
        assertTrue("acceleration $accelerating vs idle $idling", accelerating > idling + 0.3f)
        val revBlip = result.meanLevel(SoundCategory.ENGINE_REV, 2.0, 2.6)
        val revIdle = result.meanLevel(SoundCategory.ENGINE_REV, 0.5, 1.8)
        assertTrue("rev $revBlip vs idle $revIdle", revBlip > revIdle + 0.2f)
        val rpmHigh = result.meanOf(7.0, 7.3) { it.rpm }
        val rpmIdle = result.meanOf(0.5, 1.8) { it.rpm }
        assertTrue("rpm proxy rises: $rpmHigh vs $rpmIdle", rpmHigh > rpmIdle + 0.15f)
    }

    @Test
    fun passByIsRecognised() {
        val (clip, result) = run(DemoScenario.TRACKSIDE)
        val passes = clip.times(SynthLabel.VEHICLE_PASSING)
        assertEquals(1.0, recall(result, passes, 0.25, SoundCategory.VEHICLE_PASSING), 1e-9)
        assertTrue(result.eventsOf(SoundCategory.GEAR_SHIFT).isEmpty())
    }

    @Test
    fun drumsAreClassifiedAsDrums() {
        val (clip, result) = run(DemoScenario.DRUM_GROOVE)
        val kicks = clip.times(SynthLabel.KICK)
        val snares = clip.times(SynthLabel.SNARE)
        assertTrue("kick onsets", recall(result, kicks, 0.03) >= 0.9)
        assertTrue(
            "kicks as bass drum/beat",
            recall(result, kicks, 0.03, SoundCategory.BASS_DRUM, SoundCategory.BEAT) >= 0.6,
        )
        assertTrue("snares", recall(result, snares, 0.03, SoundCategory.SNARE) >= 0.6)
        val cinematic = result.eventsOf(
            SoundCategory.EXPLOSION, SoundCategory.GUNSHOT, SoundCategory.GEAR_SHIFT, SoundCategory.VEHICLE_PASSING,
        )
        assertTrue("no cinematic/engine events in music: $cinematic", cinematic.isEmpty())
    }

    @Test
    fun bassDropIsDetected() {
        val (clip, result) = run(DemoScenario.BASS_DROP)
        assertEquals(1.0, recall(result, clip.times(SynthLabel.BASS_DROP), 0.03, SoundCategory.BASS_DROP), 1e-9)
        assertTrue("kick onsets after the drop", recall(result, clip.times(SynthLabel.KICK), 0.03) >= 0.8)
    }

    @Test
    fun explosionsAndImpacts() {
        val (clip, result) = run(DemoScenario.EXPLOSIONS)
        assertEquals(1.0, recall(result, clip.times(SynthLabel.EXPLOSION), 0.03, SoundCategory.EXPLOSION), 1e-9)
        assertEquals(1.0, recall(result, clip.times(SynthLabel.IMPACT), 0.03, SoundCategory.IMPACT), 1e-9)
        assertEquals("only the real explosions", 2, result.eventsOf(SoundCategory.EXPLOSION).size)
        // Debris inside the explosion tails must not become separate events.
        assertTrue(result.events.none { it.timeUs in 1_600_000..4_500_000 })
        // A rumble follows the blast.
        assertTrue(result.meanLevel(SoundCategory.CINEMATIC_RUMBLE, 1.6, 3.0) > 0.3f)
    }

    @Test
    fun gunfireIncludingBursts() {
        val (clip, result) = run(DemoScenario.FIREFIGHT)
        val shots = clip.times(SynthLabel.GUNSHOT)
        assertTrue("shot onsets", recall(result, shots, 0.02) >= 0.9)
        assertTrue("shots as gunshots", recall(result, shots, 0.02, SoundCategory.GUNSHOT) >= 0.65)
    }

    @Test
    fun footstepsAreRecognised() {
        val (clip, result) = run(DemoScenario.FOOTSTEPS)
        assertTrue(recall(result, clip.times(SynthLabel.FOOTSTEP), 0.03, SoundCategory.FOOTSTEPS) >= 0.6)
    }

    @Test
    fun dialogueProducesLittleHapticContent() {
        val (_, result) = run(DemoScenario.DIALOGUE)
        val speech = result.meanOf(1.6, 15.0) { it.speech }
        assertTrue("speech likelihood $speech", speech > 0.6f)
        val nonDialogue = result.events.filter { it.category != SoundCategory.DIALOGUE && it.category != SoundCategory.GENERAL }
        assertTrue("non-dialogue events: $nonDialogue", nonDialogue.size <= 3)
        assertTrue(result.eventsOf(SoundCategory.GEAR_SHIFT, SoundCategory.VEHICLE_PASSING).isEmpty())
        assertTrue(result.meanOf(1.6, 15.0) { it.engine } < 0.1f)
    }

    @Test
    fun thunderAndRain() {
        val (_, result) = run(DemoScenario.THUNDERSTORM)
        assertTrue("distant thunder", result.eventsOf(SoundCategory.THUNDER).any { abs(it.timeUs / 1e6 - 3.0) < 0.4 })
        assertTrue("rain texture", result.meanLevel(SoundCategory.RAIN, 0.0, 20.0) > 0.15f)
    }

    @Test
    fun machineryTexture() {
        val (_, result) = run(DemoScenario.WORKSHOP)
        assertTrue(result.meanLevel(SoundCategory.MACHINERY, 1.0, 10.0) > 0.3f)
        assertTrue(result.eventsOf(SoundCategory.DOOR_SLAM).any { abs(it.timeUs / 1e6 - 13.0) < 0.03 })
    }

    @Test
    fun clickOnsetsAreSampleAccurateAtEverySampleRate() {
        for (rate in intArrayOf(16_000, 22_050, 44_100, 48_000, 96_000)) {
            val clip = DemoClips.render(DemoScenario.SYNC_CLICKS, rate)
            val result = ClipRunner.run(clip)
            val clicks = clip.times(SynthLabel.CLICK)
            for (t in clicks) {
                val nearest = result.events.minByOrNull { abs(it.timeUs / 1e6 - t) }
                assertTrue("click at $t (rate $rate) missing", nearest != null)
                val error = abs(nearest!!.timeUs / 1e6 - t)
                assertTrue("click at $t rate $rate error ${error * 1000} ms", error < 0.004)
            }
        }
    }
}
