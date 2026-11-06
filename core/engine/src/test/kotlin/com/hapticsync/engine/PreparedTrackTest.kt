package com.hapticsync.engine

import com.hapticsync.dsp.PcmEncoding
import com.hapticsync.dsp.PcmFormat
import com.hapticsync.dsp.synth.DemoClips
import com.hapticsync.dsp.synth.DemoScenario
import com.hapticsync.dsp.synth.SynthClip
import com.hapticsync.dsp.synth.SynthLabel
import com.hapticsync.haptics.DeviceCapabilities
import com.hapticsync.haptics.HapticConfig
import com.hapticsync.haptics.HapticRenderer
import com.hapticsync.haptics.MediaClock
import com.hapticsync.haptics.OutputHistory
import com.hapticsync.haptics.RenderedEffect
import com.hapticsync.model.BuiltInPresets
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Builds tracks the way the Android preparer does: 16-bit PCM in decoder-sized chunks. */
internal fun buildTrack(
    interleaved: FloatArray,
    sampleRate: Int,
    channels: Int = 2,
    profile: HapticProfile = BuiltInPresets.MUSIC.profile,
    track: HapticTrack = HapticTrack(),
    fromSec: Double = 0.0,
    toSec: Double = Double.MAX_VALUE,
): Pair<HapticTrack, HapticTrackBuilder> {
    val totalFrames = interleaved.size / channels
    track.durationUs = totalFrames * 1_000_000L / sampleRate
    val builder = HapticTrackBuilder(track, profile, decodeStartUs = (fromSec * 1e6).toLong())
    builder.configure(PcmFormat(sampleRate, channels, PcmEncoding.PCM_16BIT))
    var frame = (fromSec * sampleRate).toInt()
    val end = minOf(totalFrames, (toSec.coerceAtMost(1e9) * sampleRate).toInt())
    while (frame < end) {
        val n = minOf(1024, end - frame)
        val buffer = ByteBuffer.allocate(n * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n * channels) buffer.putShort((interleaved[frame * channels + i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        buffer.flip()
        if (!builder.write(buffer, 0, buffer.limit(), frame * 1_000_000L / sampleRate)) break
        frame += n
    }
    builder.finish(endOfStream = frame >= totalFrames)
    return track to builder
}

internal fun buildTrack(clip: SynthClip, profile: HapticProfile = BuiltInPresets.MUSIC.profile): HapticTrack =
    buildTrack(clip.interleaved(), clip.sampleRate, profile = profile).first

/**
 * Media times (µs) of the pulses in [effect], issued at [startUs]: where the amplitude rises
 * above [threshold] or jumps up by at least [jump] (a hit on top of a texture).
 */
internal fun pulseStarts(effect: RenderedEffect, startUs: Long, threshold: Int = 40, jump: Int = 50): List<Long> {
    val w = effect as? RenderedEffect.Waveform ?: return emptyList()
    val out = ArrayList<Long>()
    var t = 0L
    for (i in w.amplitudes.indices) {
        val previous = if (i > 0) w.amplitudes[i - 1] else 0
        val a = w.amplitudes[i]
        if ((a > threshold && previous <= threshold) || a - previous >= jump) out += startUs + t * 1000L
        t += w.timingsMs[i]
    }
    return out
}

class PreparedTrackBuilderTest {

    @Test
    fun preparedEventsLandOnTheDrums() {
        val groove = DemoClips.render(DemoScenario.DRUM_GROOVE, 44_100)
        val track = buildTrack(groove)
        assertTrue(track.complete)
        assertTrue(track.isReady(0, track.durationUs))
        val events = ArrayList<com.hapticsync.analysis.SoundEvent>()
        track.events(0, track.durationUs, events)
        val kicks = groove.times(SynthLabel.KICK)
        val felt = events.filter { it.category != SoundCategory.DIALOGUE }
        val hits = kicks.count { k -> felt.any { abs(it.timeUs - (k * 1e6).toLong()) <= 15_000 } }
        assertTrue("kicks found $hits/${kicks.size}", hits >= kicks.size * 0.85)
        // Ordered and non-redundant.
        assertEquals(events.sortedBy { it.timeUs }, events)
    }

    @Test
    fun quietClutterIsFilteredOut() {
        val rate = 44_100
        val seconds = 20
        val samples = FloatArray(rate * seconds * 2)
        val kickTimes = (1 until seconds).map { it.toDouble() }
        val clickTimes = (1 until seconds).map { it + 0.5 }
        val rng = java.util.Random(7)
        for (k in kickTimes) {
            val start = (k * rate).toInt()
            for (i in 0 until rate / 4) {
                val v = (0.6 * exp(-i / (0.08 * rate)) * sin(2 * PI * 55.0 * i / rate)).toFloat()
                samples[2 * (start + i)] += v
                samples[2 * (start + i) + 1] += v
            }
        }
        for (c in clickTimes) {
            val start = (c * rate).toInt()
            for (i in 0 until (0.003 * rate).toInt()) {
                val v = ((rng.nextFloat() * 2f - 1f) * 0.004f)
                samples[2 * (start + i)] += v
                samples[2 * (start + i) + 1] += v
            }
        }
        val sensitive = BuiltInPresets.MUSIC.profile.let { it.copy(haptics = it.haptics.copy(eventSensitivity = 1f)) }
        val (track, _) = buildTrack(samples, rate, profile = sensitive)
        val events = ArrayList<com.hapticsync.analysis.SoundEvent>()
        track.events(0, track.durationUs, events)
        val kept = kickTimes.count { k -> events.any { abs(it.timeUs - (k * 1e6).toLong()) <= 20_000 } }
        assertTrue("kicks kept $kept/${kickTimes.size}", kept >= kickTimes.size - 1)
        val clutter = clickTimes.count { c -> events.any { abs(it.timeUs - (c * 1e6).toLong()) <= 20_000 } }
        assertEquals("quiet clicks felt", 0, clutter)
    }

    @Test
    fun audioThatStartsLateIsReadyFromZero() {
        // Screen recordings often start their audio a little after the video.
        val rate = 44_100
        val clip = DemoClips.render(DemoScenario.DRUM_GROOVE, rate).interleaved()
        val track = HapticTrack()
        val builder = HapticTrackBuilder(track, BuiltInPresets.MUSIC.profile, decodeStartUs = 0L)
        builder.configure(PcmFormat(rate, 2, PcmEncoding.PCM_16BIT))
        val offsetUs = 400_000L
        var frame = 0
        val total = clip.size / 2
        while (frame < total) {
            val n = minOf(1024, total - frame)
            val buffer = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n * 2) buffer.putShort((clip[frame * 2 + i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            buffer.flip()
            builder.write(buffer, 0, buffer.limit(), offsetUs + frame * 1_000_000L / rate)
            frame += n
        }
        builder.finish(endOfStream = true)
        assertTrue("the silent start is not ready", track.isReady(0L, 5_000_000L))
        assertTrue(track.isFullyPrepared)
        val out = com.hapticsync.analysis.AnalysisFrame()
        assertTrue(track.readFrame(100_000L, out))
        assertTrue("the silent start is not silent", out.bandLevel.all { it == 0f } && out.timeUs + out.durationUs == offsetUs)
    }

    @Test
    fun theEndOfTheAudioCountsAsReadyEvenIfTheVideoIsLonger() {
        val clip = DemoClips.render(DemoScenario.EXPLOSIONS, 44_100)
        val (track, _) = buildTrack(clip.interleaved(), clip.sampleRate)
        val audioEnd = track.durationUs
        // The player's duration: a video track 2 s longer (or a duration with the encoder delay).
        track.durationUs = audioEnd + 2_000_000L
        assertTrue(track.complete)
        assertTrue(track.audioEndUs <= audioEnd && track.audioEndUs > audioEnd - 25_000L)
        assertTrue("the last second is not ready", track.isReady(audioEnd - 1_000_000L, track.durationUs))
        assertTrue("after the audio is not ready", track.isReady(audioEnd + 500_000L, track.durationUs))
        assertTrue(track.isFullyPrepared)
    }

    @Test
    fun segmentsWarmUpJoinAndStopAtPreparedAudio() {
        val clip = DemoClips.render(DemoScenario.EXPLOSIONS, 44_100)
        val audio = clip.interleaved()
        val track = HapticTrack()
        buildTrack(audio, clip.sampleRate, track = track, fromSec = 0.0, toSec = 8.0)
        buildTrack(audio, clip.sampleRate, track = track, fromSec = 15.0)
        assertTrue(track.isReady(1_000_000, 7_000_000))
        assertFalse("gap before the second segment", track.isReady(9_000_000, 10_000_000))
        assertNull("second segment still warming up", track.readyEndFrom(15_500_000))
        assertNotNull(track.readyEndFrom(17_000_000))
        // Fill the gap from just before the first segment's end: it joins both segments.
        val (_, filler) = buildTrack(audio, clip.sampleRate, track = track, fromSec = 8.0 - HapticTrackBuilder.WARM_UP_US / 1e6)
        assertTrue("stopped at the prepared audio", filler.reachedAnalysedRange)
        assertTrue(filler.analysedUntilUs < 17_000_000)
        assertTrue("whole clip ready", track.isReady(0, track.durationUs))
    }
}

/** A media clock under test control. */
private class ManualClock : MediaClock {
    var timeUs = 0L
    override fun mediaTimeUs(systemNanoTime: Long): Long = timeUs
    override var isPlaying = false
    override var speed = 1f
    override var isValid = true
}

class TrackPlayerTest {

    private val clicks = DemoClips.render(DemoScenario.SYNC_CLICKS, 44_100)
    private val clickTimes = clicks.times(SynthLabel.CLICK)
    private val track = buildTrack(clicks, BuiltInPresets.CINEMATIC.profile)

    private class Setup(track: HapticTrack, latencyMs: Float = 0f, caps: DeviceCapabilities = DeviceCapabilities.AMPLITUDE_ONLY) {
        val output = RecordingOutput(caps)
        val clock = ManualClock()
        var epoch = 0
        val config = HapticConfig(BuiltInPresets.CINEMATIC.profile, CalibrationProfile(actuatorLatencyMs = latencyMs, minPerceptibleAmplitude = 0f))
        val player = TrackPlayer(output, HapticRenderer(caps), OutputHistory(), EventLog(), config) { epoch }.also { it.track = track }
        var nanos = 1_000_000_000L
        private var nextTick = 0L

        init {
            output.mediaTimeProvider = { clock.timeUs }
        }

        /** Advances media and wall time by [ms] in 1 ms steps, ticking when the player asks. */
        fun advance(ms: Int, offsetUs: Long) {
            repeat(ms) {
                nanos += 1_000_000L
                if (clock.isPlaying) clock.timeUs += 1_000L
                if (nanos >= nextTick) nextTick = nanos + player.tick(nanos, clock, offsetUs, epoch, enabled = true)
            }
        }
    }

    /** Media times of the pulses the output played (content time = call time + offset in the waveform). */
    private fun feltPulses(s: Setup, offsetUs: Long, latencyUs: Long = 0L): List<Long> =
        s.output.played.flatMap { pulseStarts(it.effect, it.mediaTimeUs - offsetUs + latencyUs) }

    @Test
    fun windowsCarryEveryClickOnTime() {
        val offset = 1_000_000_000_000L
        val s = Setup(track)
        s.clock.timeUs = offset
        s.clock.isPlaying = true
        s.advance(12_000, offset)
        val pulses = feltPulses(s, offset)
        val expected = clickTimes.filter { it < 11.5 }
        for (t in expected) {
            val us = (t * 1e6).toLong()
            val nearest = pulses.minByOrNull { abs(it - us) }
            assertNotNull("click at $t", nearest)
            assertTrue("click at $t felt at ${nearest!! / 1000} ms", abs(nearest - us) <= 10_000)
        }
        // About one command per second, not one per event and segment.
        val calls = s.output.played.size
        assertTrue("calls $calls", calls in 7..26)
        assertEquals(0L, s.player.stats.notReady)
    }

    @Test
    fun motorLatencyIsCompensated() {
        val s = Setup(track, latencyMs = 30f)
        s.clock.isPlaying = true
        s.advance(6_000, 0)
        // The vibrator is driven 30 ms early, so with its latency the pulses land on the clicks.
        val pulses = feltPulses(s, 0, latencyUs = 30_000)
        for (t in clickTimes.filter { it in 1.0..5.5 }) {
            val us = (t * 1e6).toLong()
            assertTrue("click at $t", pulses.any { abs(it - us) <= 10_000 })
        }
    }

    @Test
    fun pauseStopsAndSeekRestartsAtTheNewPosition() {
        val s = Setup(track)
        s.clock.isPlaying = true
        s.advance(2_300, 0)
        val before = s.output.played.size
        s.clock.isPlaying = false
        s.player.stop()
        val cancels = s.output.cancels
        s.advance(1_000, 0)
        assertEquals("nothing while paused", before, s.output.played.size)
        assertTrue(cancels >= 1)
        // Seek to 7.2 s and resume.
        s.epoch++
        s.player.stop()
        s.clock.timeUs = 7_200_000
        s.clock.isPlaying = true
        s.advance(1_500, 0)
        val after = s.output.played.drop(before)
        assertTrue(after.isNotEmpty())
        assertTrue("restarts at the new position: ${after.first().mediaTimeUs}", after.first().mediaTimeUs in 7_200_000..7_225_000)
        val pulses = after.flatMap { pulseStarts(it.effect, it.mediaTimeUs) }
        assertTrue("click at 8 s after the seek", pulses.any { abs(it - 8_000_000) <= 10_000 })
        assertTrue("no pulses from before the seek", pulses.none { it < 7_200_000 })
    }

    @Test
    fun nothingPlaysWhereTheTrackIsNotPrepared() {
        val partial = HapticTrack()
        buildTrack(clicks.interleaved(), clicks.sampleRate, profile = BuiltInPresets.CINEMATIC.profile, track = partial, toSec = 4.0)
        val s = Setup(partial)
        s.clock.timeUs = 10_000_000
        s.clock.isPlaying = true
        s.advance(500, 0)
        assertTrue(s.output.played.isEmpty())
        assertTrue(s.player.stats.notReady > 0)
    }

    @Test
    fun onOffMotorsGetPatternsToo() {
        val s = Setup(track, caps = DeviceCapabilities.ON_OFF)
        s.clock.isPlaying = true
        s.advance(3_000, 0)
        assertTrue(s.output.played.any { it.effect is RenderedEffect.OnOff })
    }
}

class PreparedEngineTest {

    @Test
    fun engineFeelsThePreparedTrackOnTheSinkClock() {
        val groove = DemoClips.render(DemoScenario.DRUM_GROOVE, 44_100)
        val track = buildTrack(groove)
        val p = SimulatedPlayer(groove.interleaved(), 44_100, 2, BuiltInPresets.MUSIC.profile, CalibrationProfile(actuatorLatencyMs = 0f))
        p.engine.setPreparedTrack(track)
        p.engine.onMediaTimeOffset(0L)
        p.play()
        p.advance(10_000)
        val pulses = p.output.played.flatMap { pulseStarts(it.effect, it.mediaTimeUs) }
        val kicks = groove.times(SynthLabel.KICK).filter { it in 0.5..9.5 }
        val hits = kicks.count { k -> pulses.any { abs(it - (k * 1e6).toLong()) <= 15_000 } }
        assertTrue("kicks felt $hits/${kicks.size}", hits >= kicks.size * 0.85)
        assertTrue("prepared windows, not per-event commands: ${p.output.played.size}", p.output.played.size <= 25)
        assertTrue(p.engine.trackStats.windowsIssued > 0)
        assertTrue(p.output.played.all { it.playing })
        // Visualisation shows the felt events.
        val snapshot = VisualizationSnapshot()
        p.engine.snapshot(snapshot, p.nanos)
        assertTrue(snapshot.markerCount > 0)
    }

    @Test
    fun backToLiveAnalysisWithoutATrack() {
        val groove = DemoClips.render(DemoScenario.DRUM_GROOVE, 44_100)
        val p = SimulatedPlayer(groove.interleaved(), 44_100, 2, BuiltInPresets.MUSIC.profile, CalibrationProfile(actuatorLatencyMs = 0f))
        p.engine.setPreparedTrack(buildTrack(groove))
        p.engine.setPreparedTrack(null)
        p.play()
        p.advance(4_000)
        assertTrue(p.engine.schedulerStats.transientsDispatched > 0)
        assertEquals(0L, p.engine.trackStats.windowsIssued)
    }
}
