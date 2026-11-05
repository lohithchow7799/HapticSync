package com.hapticsync.engine

import com.hapticsync.dsp.PcmEncoding
import com.hapticsync.dsp.synth.DemoClips
import com.hapticsync.dsp.synth.DemoScenario
import com.hapticsync.dsp.synth.SynthLabel
import com.hapticsync.haptics.CalibrationPattern
import com.hapticsync.haptics.DeviceCapabilities
import com.hapticsync.haptics.RenderedEffect
import com.hapticsync.model.BuiltInPresets
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.IsolationSettings
import com.hapticsync.model.IsolationTarget
import com.hapticsync.model.SoundCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End-to-end tests of the engine with a simulated player: decoded PCM in, vibration commands
 * out, all on a simulated clock. Covers playback, pause/resume, seeking, completion, format and
 * sample-rate changes, device capabilities, isolation, live preset changes and sync offsets.
 */
class EngineIntegrationTest {

    private val groove = DemoClips.render(DemoScenario.DRUM_GROOVE, 44_100)
    private val kicks = groove.times(SynthLabel.KICK)

    private fun player(
        profile: HapticProfile = BuiltInPresets.MUSIC.profile,
        calibration: CalibrationProfile = CalibrationProfile(actuatorLatencyMs = 0f),
        capabilities: DeviceCapabilities = DeviceCapabilities.AMPLITUDE_ONLY,
        clip: FloatArray = groove.interleaved(),
        rate: Int = 44_100,
        channels: Int = 2,
        encoding: PcmEncoding = PcmEncoding.PCM_16BIT,
    ) = SimulatedPlayer(clip, rate, channels, profile, calibration, capabilities, encoding)

    /** Fraction of [times] with a transient dispatched within ±[toleranceUs] (plus [offsetUs]). */
    private fun hitRate(p: SimulatedPlayer, times: List<Double>, toleranceUs: Long = 25_000, offsetUs: Long = 0): Double {
        val sent = p.output.transients().map { it.mediaTimeUs }
        if (times.isEmpty()) return 1.0
        return times.count { t -> sent.any { abs(it - (t * 1e6).toLong() - offsetUs) <= toleranceUs } }.toDouble() / times.size
    }

    @Test
    fun drumHitsAreFeltOnTime() {
        val p = player()
        p.play()
        p.advance(10_000)
        val inWindow = kicks.filter { it < 9.7 }
        assertTrue("kick hit rate ${hitRate(p, inWindow)}", hitRate(p, inWindow) >= 0.8)
        assertTrue("dispatch error ${p.engine.schedulerStats.meanDispatchErrorUs} µs", p.engine.schedulerStats.meanDispatchErrorUs < 2_000)
        assertTrue("analysis ran ahead: lead ${p.engine.schedulerStats.meanLeadUs}", p.engine.schedulerStats.meanLeadUs > 50_000)
        assertTrue(p.output.played.all { it.playing })
    }

    @Test
    fun pauseStopsAtOnceAndResumeContinues() {
        val p = player()
        p.play()
        p.advance(3_000)
        val before = p.output.played.size
        val cancels = p.output.cancels
        p.pause()
        assertTrue("cancelled on pause", p.output.cancels > cancels)
        p.advance(2_000)
        assertEquals("nothing while paused", before, p.output.played.size)
        p.play()
        p.advance(3_000)
        assertTrue(p.output.played.size > before)
        assertTrue("never vibrates while paused", p.output.played.all { it.playing })
    }

    @Test
    fun seekingResynchronises() {
        val p = player()
        p.play()
        p.advance(2_000)
        p.seek(9_000_000)
        val afterSeek = p.output.played.size
        p.advance(3_000)
        val sentAfterSeek = p.output.transients(fromIndex = afterSeek).map { it.mediaTimeUs }
        assertTrue("events after seek", sentAfterSeek.isNotEmpty())
        // Nothing from the pre-seek region (< 9 s) may be dispatched after the seek.
        assertTrue(sentAfterSeek.all { it >= 9_000_000 })
        val kicksAfter = kicks.filter { it in 9.3..11.8 }
        val hits = kicksAfter.count { t -> sentAfterSeek.any { abs(it - (t * 1e6).toLong()) <= 25_000 } }
        assertTrue("re-synchronised kicks $hits/${kicksAfter.size}", hits >= kicksAfter.size * 0.75)
    }

    @Test
    fun completionStopsHaptics() {
        val short = groove.interleaved().copyOf(44_100 * 2 * 4) // 4 s
        val p = player(clip = short)
        p.play()
        p.advance(5_000)
        assertFalse(p.playing)
        val count = p.output.played.size
        p.advance(1_000)
        assertEquals(count, p.output.played.size)
        assertTrue(p.output.cancels > 0)
    }

    @Test
    fun worksAtManySampleRatesAndFormats() {
        for (rate in intArrayOf(16_000, 32_000, 48_000, 96_000)) {
            val clip = DemoClips.render(DemoScenario.DRUM_GROOVE, rate)
            val p = player(clip = clip.interleaved(), rate = rate)
            p.play()
            p.advance(6_000)
            val window = clip.times(SynthLabel.KICK).filter { it < 5.7 }
            assertTrue("rate $rate hit rate ${hitRate(p, window)}", hitRate(p, window) >= 0.7)
        }
        // Float PCM, mono.
        val stereo = groove.interleaved()
        val mono = FloatArray(stereo.size / 2) { 0.5f * (stereo[2 * it] + stereo[2 * it + 1]) }
        val p = player(clip = mono, channels = 1, encoding = PcmEncoding.PCM_FLOAT)
        p.play()
        p.advance(6_000)
        assertTrue("mono float", hitRate(p, kicks.filter { it < 5.7 }) >= 0.7)
    }

    @Test
    fun adaptsToDeviceCapabilities() {
        val onOff = player(capabilities = DeviceCapabilities.ON_OFF)
        onOff.play()
        onOff.advance(4_000)
        assertTrue(onOff.output.played.isNotEmpty())
        assertTrue(onOff.output.played.none { it.effect is RenderedEffect.Waveform || it.effect is RenderedEffect.Envelope })

        val full = player(capabilities = DeviceCapabilities.FULL)
        full.play()
        full.advance(4_000)
        assertTrue(full.output.played.any { it.effect is RenderedEffect.Envelope })

        val none = player(capabilities = DeviceCapabilities.NONE)
        none.play()
        none.advance(3_000)
        assertTrue(none.output.played.isEmpty())
    }

    @Test
    fun isolationLeavesOnlyTheSelectedComponent() {
        val racing = DemoClips.render(DemoScenario.RACING_ONBOARD, 44_100)
        val onlyRevs = BuiltInPresets.RACING.profile.copy(
            isolation = IsolationSettings(true, IsolationTarget.of(SoundCategory.ENGINE_REV)),
        )
        val p = player(profile = onlyRevs, clip = racing.interleaved())
        p.play()
        p.advance(15_000)
        // Gear shifts (transients) must not be felt; the rev texture must be.
        val shiftTimes = racing.times(SynthLabel.GEAR_SHIFT).filter { it < 14.5 }
        assertEquals(0.0, hitRate(p, shiftTimes, 15_000), 1e-9)
        assertTrue("rev texture is felt", p.output.played.any { it.mediaTimeUs in 1_900_000..2_800_000 })

        val withShifts = player(profile = BuiltInPresets.RACING.profile, clip = racing.interleaved())
        withShifts.play()
        withShifts.advance(15_000)
        assertTrue("shifts felt without isolation", hitRate(withShifts, shiftTimes, 30_000) >= 0.75)
    }

    @Test
    fun presetChangesApplyDuringPlayback() {
        val explosions = DemoClips.render(DemoScenario.EXPLOSIONS, 44_100)
        val p = player(profile = BuiltInPresets.CINEMATIC.profile, clip = explosions.interleaved())
        p.play()
        p.advance(4_000)
        assertTrue(p.output.played.isNotEmpty())
        val minimal = BuiltInPresets.MINIMAL.profile.updateCategory(SoundCategory.EXPLOSION) { it.copy(enabled = false) }
            .updateCategory(SoundCategory.IMPACT) { it.copy(enabled = false) }
        p.engine.updateProfile(minimal)
        val count = p.output.played.size
        p.advance(9_000) // explosion at 10.5 s and impacts are disabled; continuous layer is off
        val later = p.output.played.drop(count).filter { it.mediaTimeUs > 4_500_000 }
        assertTrue("nothing felt after switching to the minimal preset: ${later.size}", later.size <= 3)
    }

    @Test
    fun syncOffsetShiftsHaptics() {
        val offset = player(calibration = CalibrationProfile(actuatorLatencyMs = 0f, syncOffsetMs = 100f))
        offset.play()
        offset.advance(8_000)
        val window = kicks.filter { it < 7.5 }
        assertTrue("shifted by +100 ms", hitRate(offset, window, 25_000, offsetUs = 100_000) >= 0.75)
        assertTrue("not at the original times", hitRate(offset, window, 25_000) < 0.3)
    }

    @Test
    fun doubleSpeedKeepsMediaAlignment() {
        val p = player()
        p.setSpeed(2f)
        p.play()
        p.advance(4_000) // 8 s of media
        val window = kicks.filter { it < 7.7 }
        assertTrue("2x hit rate ${hitRate(p, window, 30_000)}", hitRate(p, window, 30_000) >= 0.7)
    }

    @Test
    fun masterSwitchSilencesButAnalysisContinues() {
        val p = player()
        p.engine.hapticsEnabled = false
        p.play()
        p.advance(3_000)
        assertTrue(p.output.played.isEmpty())
        val snapshot = VisualizationSnapshot()
        assertTrue(p.engine.snapshot(snapshot, p.nanos))
        assertTrue(snapshot.spectrum.any { it > 0.1f })
    }

    @Test
    fun visualisationShowsWhatIsHeard() {
        val p = player()
        p.play()
        p.advance(4_000)
        val snapshot = VisualizationSnapshot()
        assertTrue(p.engine.snapshot(snapshot, p.nanos))
        assertTrue(abs(snapshot.mediaTimeUs - p.positionUs) < 20_000)
        assertTrue("markers", snapshot.markerCount > 0)
        assertTrue(snapshot.envelope.any { it > 0.2f })
        assertTrue(snapshot.hapticHistory.any { it > 0f })
        assertTrue(snapshot.markerPosition.take(snapshot.markerCount).all { it in 0f..1f })
    }

    @Test
    fun unsupportedAudioIsReported() {
        val p = player()
        p.engine.onFormat(null, "Encoded passthrough")
        assertEquals("Encoded passthrough", p.engine.status.unsupportedReason)
    }

    @Test
    fun previewsPlayImmediately() {
        val p = player()
        for (pattern in CalibrationPattern.entries) assertTrue(pattern.name, p.engine.preview(pattern))
        assertTrue(p.engine.previewCategory(SoundCategory.EXPLOSION))
        assertTrue(p.engine.previewCategory(SoundCategory.ENGINE_REV))
        assertEquals(CalibrationPattern.entries.size + 2, p.output.played.size)
    }
}

/** Format changes mid-stream (e.g. a playlist moving to a file with a different audio format). */
class FormatChangeTest {
    @Test
    fun analysisFollowsAFormatChange() {
        val output = RecordingOutput()
        var nanos = 1_000_000_000L
        val engine = HapticSyncEngine(output, BuiltInPresets.MUSIC.profile, CalibrationProfile(actuatorLatencyMs = 0f), nanoTime = { nanos })
        output.mediaTimeProvider = { engine.clock.mediaTimeUs(nanos) }
        val first = DemoClips.render(DemoScenario.DRUM_GROOVE, 48_000)
        val second = DemoClips.render(DemoScenario.DRUM_GROOVE, 22_050)
        val secondMono = second.interleaved().let { s -> FloatArray(s.size / 2) { 0.5f * (s[2 * it] + s[2 * it + 1]) } }

        fun feed(samples: FloatArray, rate: Int, channels: Int, fromUs: Long, seconds: Int, positionStartUs: Long) {
            engine.onFormat(com.hapticsync.dsp.PcmFormat(rate, channels, PcmEncoding.PCM_FLOAT), null)
            val frames = rate * seconds
            var frame = 0
            var position = positionStartUs
            var tick = 0L
            while (frame < frames) {
                val n = minOf(1024, frames - frame)
                val buffer = java.nio.ByteBuffer.allocate(n * channels * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n * channels) buffer.putFloat(samples[frame * channels + i])
                buffer.flip()
                engine.onPcm(buffer, 0, buffer.limit(), fromUs + frame * 1_000_000L / rate)
                frame += n
                // Keep the playback position ~250 ms behind the written audio.
                val writtenUs = fromUs + frame * 1_000_000L / rate
                while (position < writtenUs - 250_000) {
                    nanos += 1_000_000
                    position += 1_000
                    engine.onPosition(position, nanos)
                    engine.processPending()
                    if (nanos >= tick) tick = nanos + engine.tickScheduler(nanos)
                }
            }
        }

        engine.onPlaying(true)
        feed(first.interleaved(), 48_000, 2, 0L, 5, 0L)
        val before = output.played.size
        // Gapless switch: timestamps continue, format changes.
        feed(secondMono, 22_050, 1, 5_000_000L, 5, 4_750_000L)
        assertTrue("haptics before the change", before > 0)
        assertTrue("haptics continue after the change", output.played.size > before + 5)
        assertEquals(22_050, engine.status.format?.sampleRate)
    }
}
