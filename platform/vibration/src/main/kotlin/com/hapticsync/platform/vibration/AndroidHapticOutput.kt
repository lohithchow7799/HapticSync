package com.hapticsync.platform.vibration

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.hapticsync.haptics.DeviceCapabilities
import com.hapticsync.haptics.HapticOutput
import com.hapticsync.haptics.RenderedEffect
import com.hapticsync.model.VibrationUsage

/**
 * [HapticOutput] backed by the device's default vibrator.
 *
 * - Effects are attributed to the usage class chosen in calibration (media by default), so they
 *   follow the matching system vibration-intensity setting.
 * - An effect the device rejects is retried once as a simple one-shot; repeated failures are
 *   counted and reported, never thrown into the real-time scheduler.
 * - If the app loses permission to vibrate, output is disabled gracefully.
 *
 * Thread-safe: [Vibrator] calls are binder calls and may be made from any thread.
 */
class AndroidHapticOutput(context: Context) : HapticOutput {

    private val vibrator: Vibrator? = VibrationCapabilityProbe.defaultVibrator(context.applicationContext)

    override val capabilities: DeviceCapabilities =
        VibrationCapabilityProbe.probe(context.applicationContext, vibrator)

    /** Whether predefined effects are natively supported, for the capabilities report. */
    val predefinedEffectSupport: String
        get() = vibrator?.let { VibrationCapabilityProbe.predefinedSupport(it) } ?: "No vibrator"

    /** Usage class for subsequent effects. */
    @Volatile
    var usage: VibrationUsage = VibrationUsage.MEDIA
        set(value) {
            field = value
            attributes = buildAttributes(value)
            legacyAttributes = buildLegacyAttributes(value)
        }

    @Volatile private var attributes: Any? = buildAttributes(VibrationUsage.MEDIA)
    @Volatile private var legacyAttributes: AudioAttributes = buildLegacyAttributes(VibrationUsage.MEDIA)
    @Volatile private var disabled = false

    /** Number of effects the platform rejected. */
    @Volatile var failures = 0
        private set

    /** Human-readable reason when output had to be disabled. */
    @Volatile var disabledReason: String? = null
        private set

    override fun play(effect: RenderedEffect): Boolean {
        val v = vibrator ?: return false
        if (disabled) return false
        val built: VibrationEffect? = try {
            VibrationEffects.build(effect)
        } catch (e: IllegalArgumentException) {
            failures++
            null
        }
        val platform = built ?: VibrationEffects.fallback(effect, capabilities.hasAmplitudeControl)
        return try {
            vibrate(v, platform)
            true
        } catch (e: SecurityException) {
            disabled = true
            disabledReason = "Vibration permission was revoked."
            Log.w(TAG, "Vibration disabled: ${e.message}")
            false
        } catch (e: RuntimeException) {
            failures++
            try {
                vibrate(v, VibrationEffects.fallback(effect, capabilities.hasAmplitudeControl))
                true
            } catch (inner: RuntimeException) {
                Log.w(TAG, "Vibration failed: ${inner.message}")
                false
            }
        }
    }

    override fun cancel() {
        try {
            vibrator?.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "cancel failed: ${e.message}")
        }
    }

    private fun vibrate(v: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, attributes as VibrationAttributes)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, legacyAttributes)
        }
    }

    private fun buildAttributes(usage: VibrationUsage): Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes.createForUsage(
                when (usage) {
                    VibrationUsage.MEDIA -> VibrationAttributes.USAGE_MEDIA
                    VibrationUsage.PHYSICAL_EMULATION -> VibrationAttributes.USAGE_PHYSICAL_EMULATION
                    VibrationUsage.TOUCH -> VibrationAttributes.USAGE_TOUCH
                },
            )
        } else null

    private fun buildLegacyAttributes(usage: VibrationUsage): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(
                when (usage) {
                    VibrationUsage.MEDIA -> AudioAttributes.USAGE_MEDIA
                    VibrationUsage.PHYSICAL_EMULATION -> AudioAttributes.USAGE_GAME
                    VibrationUsage.TOUCH -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

    private companion object {
        const val TAG = "HapticSyncOutput"
    }
}
