package com.hapticsync.platform.vibration

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hapticsync.haptics.CalibrationPattern
import com.hapticsync.haptics.DeviceCapabilities
import com.hapticsync.haptics.HapticConfig
import com.hapticsync.haptics.HapticPrimitive
import com.hapticsync.haptics.HapticRenderer
import com.hapticsync.haptics.RenderedEffect
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * Runs the vibration backend against the real platform: the capability probe must be
 * consistent with the API level, and every effect the renderer produces must be accepted by
 * Android's `VibrationEffect` validation on this device.
 */
@RunWith(AndroidJUnit4::class)
class VibrationOutputInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = HapticConfig(HapticProfile(), CalibrationProfile())

    @Test
    fun probeIsConsistentWithTheApiLevel() {
        val caps = AndroidHapticOutput(context).capabilities
        assertEquals(Build.VERSION.SDK_INT, caps.apiLevel)
        if (!caps.hasVibrator) {
            assertEquals(0, caps.vibratorCount)
            assertFalse(caps.supportsComposition)
        } else {
            assertTrue(caps.vibratorCount >= 1)
        }
        for (primitive in caps.supportedPrimitives) assertTrue(Build.VERSION.SDK_INT >= primitive.minApi)
        if (Build.VERSION.SDK_INT < 30) assertTrue(caps.supportedPrimitives.isEmpty())
        if (Build.VERSION.SDK_INT < 36) assertNull(caps.envelope)
        assertNotNull(AndroidHapticOutput(context).predefinedEffectSupport)
    }

    @Test
    fun everyRenderedEffectIsAcceptedByThePlatform() {
        val sdk = Build.VERSION.SDK_INT
        val tiers = buildList {
            add(DeviceCapabilities(hasVibrator = true, hasAmplitudeControl = true, apiLevel = sdk))
            add(DeviceCapabilities(hasVibrator = true, hasAmplitudeControl = false, apiLevel = sdk))
            if (sdk >= 30) {
                val primitives = HapticPrimitive.entries.filter { sdk >= it.minApi }.toSet()
                add(DeviceCapabilities(hasVibrator = true, hasAmplitudeControl = true, apiLevel = sdk, supportedPrimitives = primitives))
            }
            val device = AndroidHapticOutput(context).capabilities
            if (device.hasVibrator) add(device)
        }
        for (caps in tiers) {
            val renderer = HapticRenderer(caps)
            val effects = mutableListOf<RenderedEffect>()
            for (pattern in CalibrationPattern.entries) {
                renderer.renderTransient(pattern.build(), config, 1f)?.let(effects::add)
                renderer.renderTransient(pattern.build(), config, 2f)?.let(effects::add)
            }
            val n = 40
            val amplitudes = FloatArray(n) { (0.5f + 0.5f * sin(it / 3f)).coerceIn(0f, 1f) }
            val sharpness = FloatArray(n) { 0.3f }
            renderer.renderContinuous(amplitudes, sharpness, n, 10f, 4f, config, 1f)?.let(effects::add)
            assertTrue("nothing rendered for $caps", effects.isNotEmpty())
            for (effect in effects) {
                // Throws IllegalArgumentException if the platform rejects the encoding.
                val built = VibrationEffects.build(effect) ?: VibrationEffects.fallback(effect, caps.hasAmplitudeControl)
                assertNotNull(built)
            }
        }
    }
}
