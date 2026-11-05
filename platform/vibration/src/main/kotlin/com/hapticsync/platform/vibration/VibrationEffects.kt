package com.hapticsync.platform.vibration

import android.os.Build
import android.os.VibrationEffect
import androidx.annotation.RequiresApi
import com.hapticsync.haptics.HapticPrimitive
import com.hapticsync.haptics.PredefinedEffect
import com.hapticsync.haptics.RenderedEffect

/** Translates [RenderedEffect]s into platform [VibrationEffect]s. Makes no haptic decisions. */
object VibrationEffects {

    /** Returns the platform effect, or null when it cannot be expressed on this API level. */
    fun build(effect: RenderedEffect): VibrationEffect? = when (effect) {
        is RenderedEffect.Waveform -> VibrationEffect.createWaveform(effect.timingsMs, effect.amplitudes, -1)
        is RenderedEffect.OnOff -> VibrationEffect.createWaveform(effect.timingsMs, -1)
        is RenderedEffect.Predefined -> VibrationEffect.createPredefined(predefinedId(effect.effect))
        is RenderedEffect.Primitives -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) composition(effect) else null
        is RenderedEffect.Envelope -> if (Build.VERSION.SDK_INT >= 36) envelope(effect) else null
    }

    /**
     * A simpler fallback for an effect the device rejected (e.g. an unsupported primitive):
     * a short one-shot at the effect's peak strength.
     */
    fun fallback(effect: RenderedEffect, hasAmplitudeControl: Boolean): VibrationEffect {
        val duration = effect.durationMs.coerceIn(10L, 2000L)
        val amplitude = if (hasAmplitudeControl) (effect.peakAmplitude * 255f).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        return VibrationEffect.createOneShot(duration, amplitude)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun composition(effect: RenderedEffect.Primitives): VibrationEffect? {
        val composition = VibrationEffect.startComposition()
        var added = 0
        for (step in effect.steps) {
            val id = primitiveId(step.primitive) ?: continue
            composition.addPrimitive(id, step.scale.coerceIn(0f, 1f), step.delayMs.coerceAtLeast(0))
            added++
        }
        return if (added > 0) composition.compose() else null
    }

    @RequiresApi(36)
    private fun envelope(effect: RenderedEffect.Envelope): VibrationEffect =
        EnvelopeEffectsApi36.waveformEnvelope(
            initialFrequencyHz = effect.initialFrequencyHz,
            amplitudes = effect.amplitudes,
            frequenciesHz = effect.frequenciesHz,
            durationsMs = effect.durationsMs,
            count = effect.amplitudes.size,
        )

    /** Platform id of [primitive], or null when this API level does not define it. */
    fun primitiveId(primitive: HapticPrimitive): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (primitive) {
            HapticPrimitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
            HapticPrimitive.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
            HapticPrimitive.SLOW_RISE -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
            HapticPrimitive.QUICK_FALL -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
            HapticPrimitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
            HapticPrimitive.THUD -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_THUD else null
            HapticPrimitive.SPIN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_SPIN else null
            HapticPrimitive.LOW_TICK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else null
        }
    }

    private fun predefinedId(effect: PredefinedEffect): Int = when (effect) {
        PredefinedEffect.CLICK -> VibrationEffect.EFFECT_CLICK
        PredefinedEffect.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
        PredefinedEffect.TICK -> VibrationEffect.EFFECT_TICK
        PredefinedEffect.DOUBLE_CLICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
    }
}
