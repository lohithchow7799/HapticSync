package com.hapticsync.haptics

import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HapticSchedulerTest {

    private val clock = FakeClock()
    private val output = RecordingOutput()
    private var cfg = config()
    private val mapper = HapticMapper(cfg, 10f)
    private val scheduler = HapticScheduler(output, clock, HapticRenderer(output.capabilities), cfg, 10_000L)
    private var nanos = 0L

    /** Advances simulated time in 0.5 ms steps, ticking the scheduler whenever it asked to. */
    private fun runFor(ms: Int) {
        var nextTick = nanos
        val end = nanos + ms * 1_000_000L
        while (nanos < end) {
            output.nowNanos = nanos
            if (nanos >= nextTick) nextTick = nanos + scheduler.tick(nanos)
            nanos += 500_000L
        }
    }

    private fun mediaTimeOf(played: RecordingOutput.Played): Long = clock.mediaTimeUs(played.atNanos)

    private fun transient(category: SoundCategory, atUs: Long, priority: Int? = null, tolerance: Long = 0L): TransientEffect {
        val e = mapper.mapEvent(event(category, timeUs = atUs, latenessToleranceUs = tolerance), scheduler.currentEpoch)!!
        return if (priority == null) e else TransientEffect(e.timeUs, e.category, e.curve, e.primitive, e.primitiveScale, priority, e.latenessToleranceUs, e.epoch)
    }

    @Test
    fun transientFiresAtItsMediaTime() {
        scheduler.submit(transient(SoundCategory.GUNSHOT, 200_000))
        runFor(400)
        assertEquals(1, output.played.size)
        assertTrue("fired at ${mediaTimeOf(output.played[0])}", abs(mediaTimeOf(output.played[0]) - 200_000) <= 1_000)
    }

    @Test
    fun actuatorLatencyAndSyncOffsetShiftDispatch() {
        val shifted = HapticConfig(HapticProfile(), CalibrationProfile(actuatorLatencyMs = 20f, syncOffsetMs = 50f))
        scheduler.config = shifted
        scheduler.submit(transient(SoundCategory.GUNSHOT, 200_000))
        runFor(400)
        // Issued 20 ms early for the motor, 50 ms later on the user's request: +30 ms.
        assertTrue(abs(mediaTimeOf(output.played.single()) - 230_000) <= 1_000)
        scheduler.config = HapticConfig(HapticProfile(), CalibrationProfile(actuatorLatencyMs = 0f, syncOffsetMs = -80f))
        scheduler.submit(transient(SoundCategory.GUNSHOT, 600_000))
        runFor(400)
        assertTrue(abs(mediaTimeOf(output.played.last()) - 520_000) <= 1_000)
    }

    @Test
    fun staleEffectsAreDroppedButTolerantOnesSkipAhead() {
        clock.positionUs = 1_000_000
        scheduler.submit(transient(SoundCategory.GUNSHOT, 900_000)) // 100 ms late
        runFor(50)
        assertTrue(output.played.isEmpty())
        assertEquals(1L, scheduler.stats.droppedStale)
        // A pass-by recognised after its peak may still be rendered (its start is skipped).
        scheduler.submit(transient(SoundCategory.VEHICLE_PASSING, 1_000_000, tolerance = 300_000))
        runFor(20)
        assertEquals(1, output.played.size)
        assertTrue(output.played[0].effect.durationMs < 540)
    }

    @Test
    fun seekPurgesQueuedEffectsAndStopsOutput() {
        scheduler.submit(transient(SoundCategory.EXPLOSION, 300_000))
        runFor(100)
        val cancelsBefore = output.cancels
        scheduler.discontinuity()
        assertTrue(output.cancels > cancelsBefore)
        runFor(400)
        assertTrue("purged by the seek", output.played.isEmpty())
        // Effects analysed before the seek (old epoch) are rejected.
        val stale = TransientEffect(500_000, SoundCategory.GUNSHOT, mapper.mapEvent(event(SoundCategory.GUNSHOT), 0)!!.curve, null, 1f, 5, 0, epoch = 0)
        scheduler.submit(stale)
        runFor(600)
        assertTrue(output.played.isEmpty())
    }

    @Test
    fun pauseStopsImmediatelyAndNothingPlaysWhilePaused() {
        scheduler.submit(transient(SoundCategory.EXPLOSION, 50_000))
        runFor(100)
        assertEquals(1, output.played.size)
        clock.positionUs = clock.mediaTimeUs(nanos)
        clock.anchorNanos = nanos
        clock.isPlaying = false
        scheduler.playbackStopped()
        val cancels = output.cancels
        assertTrue(cancels >= 1)
        scheduler.submit(transient(SoundCategory.GUNSHOT, 200_000))
        runFor(500)
        assertEquals("nothing while paused", 1, output.played.size)
        // Resume: the still-future effect plays at its media time.
        clock.isPlaying = true
        clock.anchorNanos = nanos
        runFor(300)
        assertEquals(2, output.played.size)
    }

    @Test
    fun priorityAndSpacingGovernOverlappingEffects() {
        scheduler.submit(transient(SoundCategory.EXPLOSION, 100_000, priority = 9))
        scheduler.submit(transient(SoundCategory.FOOTSTEPS, 150_000, priority = 2)) // during the explosion
        runFor(300)
        assertEquals(1, output.played.size)
        assertEquals(1L, scheduler.stats.suppressedPriority)
        // A higher-priority effect interrupts a lower one.
        scheduler.submit(transient(SoundCategory.IMPACT, 500_000, priority = 3))
        scheduler.submit(transient(SoundCategory.GUNSHOT, 520_000, priority = 9))
        runFor(400)
        assertEquals(3, output.played.size)
        // Equal priorities closer than the pulse spacing (45 ms) are thinned out.
        scheduler.submit(transient(SoundCategory.SNARE, 1_000_000, priority = 5))
        scheduler.submit(transient(SoundCategory.SNARE, 1_020_000, priority = 5))
        runFor(400)
        assertEquals(4, output.played.size)
        assertTrue(scheduler.stats.suppressedSpacing >= 1)
    }

    @Test
    fun continuousSegmentsAreIssuedBackToBack() {
        var t = 0L
        val sample = PlanSample()
        while (t < 1_000_000) {
            sample.timeUs = t; sample.amplitude = 0.6f; sample.sharpness = 0.3f; sample.epoch = scheduler.currentEpoch
            scheduler.submitPlan(sample)
            t += 10_000
        }
        runFor(900)
        val segments = output.played.filter { it.effect is RenderedEffect.Waveform }
        assertTrue("segments ${segments.size}", segments.size >= 5)
        for (i in 1 until segments.size) {
            val gapMs = (segments[i].atNanos - segments[i - 1].atNanos) / 1e6 - segments[i - 1].effect.durationMs
            assertTrue("gap $gapMs ms", gapMs < 2.0)
        }
        // When the plan goes silent the vibrator is stopped.
        val cancels = output.cancels
        while (t < 1_500_000) {
            sample.timeUs = t; sample.amplitude = 0f; scheduler.submitPlan(sample); t += 10_000
        }
        runFor(500)
        assertTrue(output.cancels > cancels)
    }

    @Test
    fun playbackSpeedShortensWallClockWaits() {
        clock.speed = 2f
        scheduler.submit(transient(SoundCategory.GUNSHOT, 400_000))
        runFor(150)
        assertTrue(output.played.isEmpty())
        runFor(100) // 250 ms wall = 500 ms media at 2×
        assertEquals(1, output.played.size)
        assertTrue(abs(mediaTimeOf(output.played[0]) - 400_000) <= 2_000)
    }

    @Test
    fun masterSwitchSilencesEverything() {
        scheduler.enabled = false
        scheduler.submit(transient(SoundCategory.EXPLOSION, 100_000))
        runFor(300)
        assertTrue(output.played.isEmpty())
    }

    @Test
    fun noVibratorDeviceNeverPlays() {
        val none = RecordingOutput(DeviceCapabilities.NONE)
        val s = HapticScheduler(none, clock, HapticRenderer(none.capabilities), cfg, 10_000L)
        s.submit(transient(SoundCategory.EXPLOSION, 10_000))
        repeat(200) { s.tick(it * 1_000_000L) }
        assertTrue(none.played.isEmpty())
    }
}
