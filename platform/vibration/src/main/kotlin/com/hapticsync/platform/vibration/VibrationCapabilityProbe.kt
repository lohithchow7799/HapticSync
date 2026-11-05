package com.hapticsync.platform.vibration

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import com.hapticsync.haptics.DeviceCapabilities
import com.hapticsync.haptics.EnvelopeCapability
import com.hapticsync.haptics.HapticPrimitive

/**
 * Detects what this device's vibration hardware supports, using only public APIs and only on
 * the API levels where they exist. Anything the platform does not expose is reported as unknown
 * rather than guessed.
 */
object VibrationCapabilityProbe {

    /** Returns the device's default vibrator, or null when there is none. */
    fun defaultVibrator(context: Context): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        return vibrator?.takeIf { it.hasVibrator() }
    }

    fun probe(context: Context, vibrator: Vibrator?): DeviceCapabilities {
        val api = Build.VERSION.SDK_INT
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        if (vibrator == null || !vibrator.hasVibrator()) {
            return DeviceCapabilities(hasVibrator = false, hasAmplitudeControl = false, apiLevel = api, vibratorCount = 0, deviceName = name)
        }
        val primitives = if (api >= Build.VERSION_CODES.R) supportedPrimitives(vibrator) else emptySet()
        val durations = if (api >= Build.VERSION_CODES.S) primitiveDurations(vibrator, primitives) else emptyMap()
        val resonant = if (api >= Build.VERSION_CODES.S) vibrator.resonantFrequency.takeIf { it.isFinite() && it > 0f } else null
        val q = if (api >= Build.VERSION_CODES.S) vibrator.qFactor.takeIf { it.isFinite() && it > 0f } else null
        val count = if (api >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.vibratorIds?.size ?: 1
        } else 1
        val envelope = if (api >= 36) runCatching { envelopeCapability(vibrator) }.getOrNull() else null
        return DeviceCapabilities(
            hasVibrator = true,
            hasAmplitudeControl = vibrator.hasAmplitudeControl(),
            apiLevel = api,
            supportedPrimitives = primitives,
            primitiveDurationsMs = durations,
            supportsPredefinedEffects = true,
            envelope = envelope,
            resonantFrequencyHz = resonant,
            qFactor = q,
            vibratorCount = count,
            deviceName = name,
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun supportedPrimitives(vibrator: Vibrator): Set<HapticPrimitive> {
        val candidates = HapticPrimitive.entries.filter { Build.VERSION.SDK_INT >= it.minApi }
        val ids = candidates.mapNotNull { VibrationEffects.primitiveId(it) }.toIntArray()
        if (ids.isEmpty()) return emptySet()
        val supported = vibrator.arePrimitivesSupported(*ids)
        return candidates.filterIndexed { index, _ -> index < supported.size && supported[index] }.toSet()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun primitiveDurations(vibrator: Vibrator, primitives: Set<HapticPrimitive>): Map<HapticPrimitive, Int> {
        val list = primitives.toList()
        val ids = list.mapNotNull { VibrationEffects.primitiveId(it) }.toIntArray()
        if (ids.size != list.size || ids.isEmpty()) return emptyMap()
        val durations = vibrator.getPrimitiveDurations(*ids)
        return list.indices.filter { durations.getOrNull(it)?.let { d -> d > 0 } == true }.associate { list[it] to durations[it] }
    }

    /**
     * Android 16 envelope support. The usable frequency band is where the actuator still
     * produces at least 40 % of its peak output, sampled from its published frequency profile.
     */
    @RequiresApi(36)
    private fun envelopeCapability(vibrator: Vibrator): EnvelopeCapability? {
        if (!EnvelopeEffectsApi36.isSupported(vibrator)) return null
        val limits = EnvelopeEffectsApi36.limits(vibrator)
        val full = EnvelopeEffectsApi36.frequencyRangeHz(vibrator)
        val usable = full?.let { range ->
            val samples = 32
            val freqs = FloatArray(samples) { range.start + (range.endInclusive - range.start) * it / (samples - 1) }
            val accel = FloatArray(samples) { EnvelopeEffectsApi36.outputAccelerationGs(vibrator, freqs[it]) }
            val peak = accel.filter { it.isFinite() }.maxOrNull() ?: return@let range
            if (peak <= 0f) return@let range
            val good = freqs.indices.filter { accel[it].isFinite() && accel[it] >= 0.4f * peak }
            if (good.isEmpty()) range else freqs[good.first()]..freqs[good.last()]
        }
        return EnvelopeCapability(
            maxControlPoints = limits.maxControlPoints,
            minControlPointDurationMs = limits.minControlPointDurationMs,
            maxControlPointDurationMs = limits.maxControlPointDurationMs,
            maxDurationMs = limits.maxDurationMs,
            frequencyRangeHz = usable,
        )
    }

    /** Whether predefined effects are natively supported (API 30+ can tell; earlier: unknown). */
    fun predefinedSupport(vibrator: Vibrator): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val result = vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_TICK)
            when (result) {
                Vibrator.VIBRATION_EFFECT_SUPPORT_YES -> "Supported"
                Vibrator.VIBRATION_EFFECT_SUPPORT_NO -> "Emulated with waveforms"
                else -> "Unknown"
            }
        } else "Unknown"
}
