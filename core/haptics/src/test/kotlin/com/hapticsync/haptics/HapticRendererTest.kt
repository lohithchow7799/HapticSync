package com.hapticsync.haptics

import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticRendererTest {

    private val cfg = config()
    private val mapper = HapticMapper(cfg, 10f)

    private fun effect(category: SoundCategory) = mapper.mapEvent(event(category), 0)!!

    @Test
    fun amplitudeDevicesGetCalibratedWaveforms() {
        val renderer = HapticRenderer(DeviceCapabilities.AMPLITUDE_ONLY)
        val explosion = effect(SoundCategory.EXPLOSION)
        val rendered = renderer.renderTransient(explosion, cfg, 1f) as RenderedEffect.Waveform
        assertTrue(rendered.amplitudes.all { it in 0..255 })
        val expected = (explosion.curve.peak * 255).toInt()
        assertEquals(expected.toDouble(), rendered.amplitudes.max().toDouble(), 3.0)
        // Trailing near-silence is trimmed, so the waveform may end slightly before the curve.
        assertTrue(rendered.durationMs <= explosion.curve.durationMs + 5 && rendered.durationMs >= 0.7 * explosion.curve.durationMs)
    }

    @Test
    fun onOffMotorsEncodeStrengthAsDutyCycle() {
        val renderer = HapticRenderer(DeviceCapabilities.ON_OFF)
        val weak = renderer.renderContinuous(FloatArray(15) { 0.3f }, FloatArray(15), 15, 10f, 10f, cfg, 1f) as RenderedEffect.OnOff
        val strong = renderer.renderContinuous(FloatArray(15) { 0.7f }, FloatArray(15), 15, 10f, 10f, cfg, 1f) as RenderedEffect.OnOff
        assertTrue("weak ${weak.dutyCycle} < strong ${strong.dutyCycle}", weak.dutyCycle < strong.dutyCycle)
        assertEquals(0.3, weak.dutyCycle.toDouble(), 0.08)
        assertEquals(0L, weak.timingsMs[0]) // starts immediately
    }

    @Test
    fun compositionDevicesUsePrimitivesForCrispTransients() {
        val caps = DeviceCapabilities.FULL.copy(envelope = null)
        val renderer = HapticRenderer(caps)
        val kick = renderer.renderTransient(effect(SoundCategory.BASS_DRUM), cfg, 1f)
        assertTrue(kick is RenderedEffect.Primitives)
        assertEquals(HapticPrimitive.THUD, (kick as RenderedEffect.Primitives).steps.single().primitive)
        // Long effects still need a waveform (a primitive cannot express a 1 s decay).
        assertTrue(renderer.renderTransient(effect(SoundCategory.EXPLOSION), cfg, 1f) is RenderedEffect.Waveform)
        // With primitives disabled in calibration, waveforms are used.
        val noPrimitives = HapticConfig(HapticProfile(), CalibrationProfile(preferPrimitives = false))
        assertTrue(renderer.renderTransient(effect(SoundCategory.BASS_DRUM), noPrimitives, 1f) is RenderedEffect.Waveform)
    }

    @Test
    fun api29DevicesWithoutAmplitudeUsePredefinedClicks() {
        val renderer = HapticRenderer(DeviceCapabilities.ON_OFF)
        val shift = renderer.renderTransient(effect(SoundCategory.GEAR_SHIFT), cfg, 1f)
        assertTrue(shift is RenderedEffect.Predefined)
    }

    @Test
    fun envelopeDevicesGetValidAmplitudeFrequencyEnvelopes() {
        val caps = DeviceCapabilities.FULL
        val renderer = HapticRenderer(caps)
        val env = renderer.renderTransient(effect(SoundCategory.EXPLOSION), cfg, 1f) as RenderedEffect.Envelope
        val limits = caps.envelope!!
        assertTrue(env.amplitudes.size <= limits.maxControlPoints)
        assertTrue(env.durationsMs.all { it >= limits.minControlPointDurationMs && it <= limits.maxControlPointDurationMs })
        assertEquals(0f, env.amplitudes.last(), 0f)
        val range = limits.frequencyRangeHz!!
        assertTrue(env.frequenciesHz.all { it in range })
        assertTrue(env.durationMs <= limits.maxDurationMs)
        // Crisper sound → higher vibration frequency.
        assertTrue(renderer.frequencyFor(0.9f, range) > renderer.frequencyFor(0.1f, range))
    }

    @Test
    fun calibrationShapesAmplitudes() {
        val calibrated = HapticConfig(
            HapticProfile().copy(haptics = HapticProfile().haptics.copy(maxIntensity = 0.5f)),
            CalibrationProfile(amplitudeGamma = 0.5f, minPerceptibleAmplitude = 0.2f, outputGain = 1f),
        )
        assertEquals(0f, calibrated.calibrate(0.005f), 0f)                // below half the floor: off
        assertEquals(0.2f, calibrated.calibrate(0.02f), 1e-3f)             // lifted to the floor
        assertEquals(0.5f, calibrated.calibrate(0.9f), 1e-6f)              // capped by max intensity
        assertEquals(kotlin.math.sqrt(0.16f), calibrated.calibrate(0.16f), 1e-4f) // gamma 0.5
    }

    @Test
    fun playbackSpeedScalesDurations() {
        val renderer = HapticRenderer(DeviceCapabilities.AMPLITUDE_ONLY)
        val impact = effect(SoundCategory.IMPACT)
        val normal = renderer.renderTransient(impact, cfg, 1f)!!.durationMs
        val fast = renderer.renderTransient(impact, cfg, 2f)!!.durationMs
        assertEquals(normal / 2.0, fast.toDouble(), 6.0)
        val fixed = HapticConfig(HapticProfile().copy(haptics = HapticProfile().haptics.copy(scaleWithSpeed = false)), CalibrationProfile())
        assertEquals(renderer.renderTransient(impact, fixed, 1f)!!.durationMs, renderer.renderTransient(impact, fixed, 2f)!!.durationMs)
    }

    @Test
    fun lateEffectsSkipTheirBeginning() {
        val renderer = HapticRenderer(DeviceCapabilities.AMPLITUDE_ONLY)
        val explosion = effect(SoundCategory.EXPLOSION)
        val full = renderer.renderTransient(explosion, cfg, 1f)!!.durationMs
        val skipped = renderer.renderTransient(explosion, cfg, 1f, skipMs = 200f)!!.durationMs
        assertEquals(full - 200.0, skipped.toDouble(), 12.0)
    }

    @Test
    fun noVibratorRendersNothing() {
        assertNull(HapticRenderer(DeviceCapabilities.NONE).renderTransient(effect(SoundCategory.EXPLOSION), cfg, 1f))
    }
}
