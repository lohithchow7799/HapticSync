package com.hapticsync.haptics

/** Composition primitives defined by Android's `VibrationEffect.Composition`. */
enum class HapticPrimitive(val label: String, val minApi: Int) {
    CLICK("Click", 30),
    QUICK_RISE("Quick rise", 30),
    SLOW_RISE("Slow rise", 30),
    QUICK_FALL("Quick fall", 30),
    TICK("Tick", 30),
    THUD("Thud", 31),
    SPIN("Spin", 31),
    LOW_TICK("Low tick", 31),
}

/** Predefined effects (`VibrationEffect.createPredefined`, API 29). */
enum class PredefinedEffect { CLICK, HEAVY_CLICK, TICK, DOUBLE_CLICK }

/** Limits of Android 16 (API 36) envelope effects on this device. */
data class EnvelopeCapability(
    val maxControlPoints: Int,
    val minControlPointDurationMs: Long,
    val maxControlPointDurationMs: Long,
    val maxDurationMs: Long,
    /** Frequency range the actuator renders usefully, or null when not exposed. */
    val frequencyRangeHz: ClosedFloatingPointRange<Float>?,
)

/**
 * What the device's vibration hardware and Android version can do. Probed on Android by
 * `:platform:vibration`; every haptic decision downstream adapts to it.
 *
 * Android does not expose an actuator's full frequency response on most devices, so values
 * such as [resonantFrequencyHz] are optional and only used when present.
 */
data class DeviceCapabilities(
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val apiLevel: Int,
    val supportedPrimitives: Set<HapticPrimitive> = emptySet(),
    val primitiveDurationsMs: Map<HapticPrimitive, Int> = emptyMap(),
    val supportsPredefinedEffects: Boolean = apiLevel >= 29,
    val envelope: EnvelopeCapability? = null,
    val resonantFrequencyHz: Float? = null,
    val qFactor: Float? = null,
    val vibratorCount: Int = if (hasVibrator) 1 else 0,
    val deviceName: String = "",
) {
    val supportsComposition: Boolean get() = apiLevel >= 30 && supportedPrimitives.isNotEmpty()
    val supportsEnvelopes: Boolean get() = apiLevel >= 36 && envelope != null
    val supportsFrequencyControl: Boolean get() = supportsEnvelopes && envelope?.frequencyRangeHz != null

    /** Human-readable summary of the best rendering strategy available. */
    val bestStrategy: RenderStrategy
        get() = when {
            !hasVibrator -> RenderStrategy.NONE
            supportsFrequencyControl -> RenderStrategy.ENVELOPE
            supportsComposition && hasAmplitudeControl -> RenderStrategy.PRIMITIVES_AND_WAVEFORM
            hasAmplitudeControl -> RenderStrategy.AMPLITUDE_WAVEFORM
            else -> RenderStrategy.ON_OFF
        }

    companion object {
        /** A capable modern phone (used by tests and previews). */
        val FULL = DeviceCapabilities(
            hasVibrator = true,
            hasAmplitudeControl = true,
            apiLevel = 36,
            supportedPrimitives = HapticPrimitive.entries.toSet(),
            primitiveDurationsMs = HapticPrimitive.entries.associateWith { if (it == HapticPrimitive.SLOW_RISE) 150 else 20 },
            envelope = EnvelopeCapability(16, 1, 1000, 5000, 60f..250f),
            resonantFrequencyHz = 160f,
            qFactor = 10f,
        )

        /** Amplitude control but no composition or envelopes (typical API 29 device). */
        val AMPLITUDE_ONLY = DeviceCapabilities(hasVibrator = true, hasAmplitudeControl = true, apiLevel = 29)

        /** On/off motor only. */
        val ON_OFF = DeviceCapabilities(hasVibrator = true, hasAmplitudeControl = false, apiLevel = 29)

        /** No vibrator at all. */
        val NONE = DeviceCapabilities(hasVibrator = false, hasAmplitudeControl = false, apiLevel = 29)
    }
}

/** How effects are rendered for a device. */
enum class RenderStrategy(val label: String) {
    ENVELOPE("Amplitude + frequency envelopes (Android 16)"),
    PRIMITIVES_AND_WAVEFORM("Haptic primitives + amplitude waveforms"),
    AMPLITUDE_WAVEFORM("Amplitude waveforms"),
    ON_OFF("On/off pulse-width patterns"),
    NONE("No vibrator"),
}
