package com.hapticsync.haptics

import com.hapticsync.model.BandId
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.IsolationGroup
import com.hapticsync.model.IsolationSettings
import com.hapticsync.model.IsolationTarget
import com.hapticsync.model.SoundCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticMapperTest {

    private fun mapper(profile: HapticProfile = HapticProfile()) = HapticMapper(config(profile), 10f)

    @Test
    fun drumBeatIsAShortStrongPulse() {
        val effect = mapper().mapEvent(event(SoundCategory.BASS_DRUM, shares = LOW_SHARES), 0)
        assertNotNull(effect)
        assertTrue("duration ${effect!!.curve.durationMs}", effect.curve.durationMs <= 90f)
        assertTrue(effect.curve.peak > 0.5f)
        assertEquals(HapticPrimitive.THUD, effect.primitive)
    }

    @Test
    fun explosionIsAnImpactFollowedByADecayingRumble() {
        val effect = mapper().mapEvent(event(SoundCategory.EXPLOSION, durationHintMs = 900f), 0)!!
        val a = effect.curve.amplitudes
        assertTrue("explosion duration ${effect.curve.durationMs}", effect.curve.durationMs >= 600f)
        val peakIndex = a.indices.maxBy { a[it] }
        assertTrue("impact first", peakIndex * HapticMapper.CURVE_STEP_MS < 60f)
        assertTrue("decays", a.last() < 0.2f * a[peakIndex])
        // Aftershocks: a later local maximum well after the main impact.
        val aftershock = (peakIndex + 20 until a.size - 1).any { a[it] > a[it - 1] && a[it] >= a[it + 1] && a[it] > 0.1f }
        assertTrue("aftershock present", aftershock)
    }

    @Test
    fun differentEventsFeelDifferent() {
        val m = mapper()
        val gunshot = m.mapEvent(event(SoundCategory.GUNSHOT), 0)!!
        val explosion = m.mapEvent(event(SoundCategory.EXPLOSION), 0)!!
        val thunder = m.mapEvent(event(SoundCategory.THUNDER, durationHintMs = 1800f), 0)!!
        val footstep = m.mapEvent(event(SoundCategory.FOOTSTEPS), 0)!!
        assertTrue(gunshot.curve.durationMs < explosion.curve.durationMs)
        assertTrue(gunshot.curve.sharpness > explosion.curve.sharpness)
        // Thunder rises slowly; a gunshot peaks immediately.
        val thunderPeak = thunder.curve.amplitudes.indices.maxBy { thunder.curve.amplitudes[it] }
        assertTrue(thunderPeak * HapticMapper.CURVE_STEP_MS > 150f)
        assertTrue(footstep.curve.peak < gunshot.curve.peak)
    }

    @Test
    fun disabledAndDialogueCategoriesAreSilent() {
        assertNull("dialogue is off by default", mapper().mapEvent(event(SoundCategory.DIALOGUE), 0))
        val noKicks = HapticProfile().updateCategory(SoundCategory.BASS_DRUM) { it.copy(enabled = false) }
        assertNull(mapper(noKicks).mapEvent(event(SoundCategory.BASS_DRUM), 0))
        val noTransients = HapticProfile().copy(haptics = HapticProfile().haptics.copy(transientLayer = false))
        assertNull(mapper(noTransients).mapEvent(event(SoundCategory.EXPLOSION), 0))
    }

    @Test
    fun categoryIsolationKeepsOnlyTheTarget() {
        val onlyRevs = HapticProfile().copy(isolation = IsolationSettings(true, IsolationTarget.of(SoundCategory.ENGINE_REV)))
        val m = mapper(onlyRevs)
        assertNull(m.mapEvent(event(SoundCategory.EXPLOSION), 0))
        assertNull(m.mapEvent(event(SoundCategory.GEAR_SHIFT), 0))
        val out = PlanSample()
        m.mapFrame(frameWith(SoundCategory.CINEMATIC_RUMBLE, 1f), 0, out)
        assertEquals(0f, out.amplitude, 1e-6f)
        repeat(30) { m.mapFrame(frameWith(SoundCategory.ENGINE_REV, 1f, rpm = 0.8f), 0, out) }
        assertTrue(out.amplitude > 0.1f)
    }

    @Test
    fun bandIsolationFeelsOnlyThoseFrequencies() {
        val lows = HapticProfile().copy(isolation = IsolationSettings(true, IsolationTarget.of(IsolationGroup.LOW_FREQUENCY)))
        val m = mapper(lows)
        assertNotNull("low-heavy events pass", m.mapEvent(event(SoundCategory.CYMBAL, shares = LOW_SHARES), 0))
        assertNull("high-only events are ignored", m.mapEvent(event(SoundCategory.BASS_DRUM, shares = HIGH_SHARES), 0))
    }

    @Test
    fun intensityAndDurationSettingsScaleTheEffect() {
        val base = mapper().mapEvent(event(SoundCategory.IMPACT), 0)!!
        val quiet = HapticProfile().updateCategory(SoundCategory.IMPACT) { it.copy(intensity = 0.2f) }
        val quieter = mapper(quiet).mapEvent(event(SoundCategory.IMPACT), 0)!!
        assertTrue(quieter.curve.peak < base.curve.peak * 0.5f)
        val longer = HapticProfile().updateCategory(SoundCategory.IMPACT) { it.copy(duration = 2f) }
        val long = mapper(longer).mapEvent(event(SoundCategory.IMPACT), 0)!!
        assertTrue(long.curve.durationMs > base.curve.durationMs * 1.6f)
        val master = HapticProfile().copy(haptics = HapticProfile().haptics.copy(masterIntensity = 0.3f))
        assertTrue(mapper(master).mapEvent(event(SoundCategory.IMPACT), 0)!!.curve.peak < base.curve.peak)
        val prio = HapticProfile().updateCategory(SoundCategory.IMPACT) { it.copy(priority = 10) }
        assertEquals(10, mapper(prio).mapEvent(event(SoundCategory.IMPACT), 0)!!.priority)
    }

    @Test
    fun bandPulseStrengthWeightsTransients() {
        val weakLows = HapticProfile()
            .updateBand(BandId.SUB_BASS) { it.copy(pulseStrength = 0.1f) }
            .updateBand(BandId.BASS) { it.copy(pulseStrength = 0.1f) }
        val base = mapper().mapEvent(event(SoundCategory.BASS_DRUM, shares = LOW_SHARES), 0)!!
        val weak = mapper(weakLows).mapEvent(event(SoundCategory.BASS_DRUM, shares = LOW_SHARES), 0)!!
        assertTrue(weak.curve.peak < base.curve.peak * 0.5f)
    }

    @Test
    fun engineRevvingBuildsUpWithEngineSpeed() {
        val m = mapper()
        val out = PlanSample()
        repeat(40) { m.mapFrame(frameWith(SoundCategory.ENGINE_REV, 1f, rpm = 0.15f), 0, out) }
        var lowRpm = 0f
        repeat(20) { m.mapFrame(frameWith(SoundCategory.ENGINE_REV, 1f, rpm = 0.15f), 0, out); lowRpm = maxOf(lowRpm, out.amplitude) }
        m.reset()
        repeat(40) { m.mapFrame(frameWith(SoundCategory.ENGINE_REV, 1f, rpm = 0.95f), 0, out) }
        var highRpm = 0f
        repeat(20) { m.mapFrame(frameWith(SoundCategory.ENGINE_REV, 1f, rpm = 0.95f), 0, out); highRpm = maxOf(highRpm, out.amplitude) }
        assertTrue("rpm 0.95 ($highRpm) stronger than 0.15 ($lowRpm)", highRpm > lowRpm * 1.4f)
    }

    @Test
    fun idleEngineIsPulsedWhileRumbleIsSmooth() {
        val m = mapper()
        val out = PlanSample()
        val idle = FloatArray(60)
        repeat(60) { m.mapFrame(frameWith(SoundCategory.ENGINE_IDLE, 1f, rpm = 0.1f), 0, out); idle[it] = out.amplitude }
        val idleTail = idle.copyOfRange(30, 60)
        assertTrue("idle pulses", (idleTail.max() - idleTail.min()) / idleTail.max() > 0.3f)
        m.reset()
        val rumble = FloatArray(60)
        repeat(60) { m.mapFrame(frameWith(SoundCategory.CINEMATIC_RUMBLE, 1f), 0, out); rumble[it] = out.amplitude }
        val rumbleTail = rumble.copyOfRange(30, 60)
        assertTrue("rumble smooth", rumbleTail.max() - rumbleTail.min() < 0.02f)
    }

    @Test
    fun dialogueSuppressesTheContinuousLayer() {
        val m = mapper()
        val out = PlanSample()
        repeat(50) { m.mapFrame(frameWith(SoundCategory.CINEMATIC_RUMBLE, 1f), 0, out) }
        val noSpeech = out.amplitude
        m.reset()
        repeat(50) { m.mapFrame(frameWith(SoundCategory.CINEMATIC_RUMBLE, 1f, speech = 1f), 0, out) }
        assertTrue(out.amplitude < noSpeech * 0.5f)
    }

    @Test
    fun continuousLayerSwitchAndFatigueProtection() {
        val off = HapticProfile().copy(haptics = HapticProfile().haptics.copy(continuousLayer = false))
        val out = PlanSample()
        val m = mapper(off)
        repeat(20) { m.mapFrame(frameWith(SoundCategory.CINEMATIC_RUMBLE, 1f), 0, out) }
        assertEquals(0f, out.amplitude, 0f)

        val loud = HapticProfile().copy(haptics = HapticProfile().haptics.copy(masterIntensity = 1f, continuousGain = 1f))
        val fatigue = mapper(loud)
        repeat(100) { fatigue.mapFrame(frameWith(SoundCategory.CINEMATIC_RUMBLE, 1f), 0, out) }
        val early = out.amplitude
        repeat(3000) { fatigue.mapFrame(frameWith(SoundCategory.CINEMATIC_RUMBLE, 1f), 0, out) } // 30 s of strong rumble
        assertTrue("fatigue protection eases off: $early -> ${out.amplitude}", out.amplitude < early * 0.85f)
        assertTrue(fatigue.fatigueGain < 1f)
    }
}
