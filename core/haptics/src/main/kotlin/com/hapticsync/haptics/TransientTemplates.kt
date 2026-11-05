package com.hapticsync.haptics

import com.hapticsync.model.SoundCategory

/**
 * The haptic "vocabulary": how each transient category should feel.
 *
 * @property attackMs rise time to the peak.
 * @property holdMs time at full strength.
 * @property decayMs fall time back to zero (scaled by the user's release time and, where
 *   [followsAudioDecay], by the measured decay of the sound).
 * @property intensity relative strength.
 * @property sharpness 0 = deep, smooth rumble; 1 = crisp, bright tap.
 * @property primitive crisp primitive used on composition-capable devices for short effects.
 * @property repeats aftershocks after the main pulse (scaled by the pulse-repetition setting).
 * @property repeatGapMs spacing of aftershocks.
 * @property repeatDecay strength multiplier per aftershock.
 */
data class TransientTemplate(
    val attackMs: Float,
    val holdMs: Float,
    val decayMs: Float,
    val intensity: Float,
    val sharpness: Float,
    val primitive: HapticPrimitive?,
    val primitiveScale: Float = 1f,
    val repeats: Int = 0,
    val repeatGapMs: Float = 0f,
    val repeatDecay: Float = 0.5f,
    val followsAudioDecay: Boolean = false,
) {
    val nominalDurationMs: Float get() = attackMs + holdMs + decayMs + repeats * repeatGapMs

    companion object {
        /**
         * Templates per category, following the specification's mapping table:
         * kicks are strong short pulses, snares medium sharp pulses, explosions a strong impact
         * followed by a decaying rumble, collisions very short intense impacts, footsteps soft
         * rhythmic pulses, thunder a slow deep decaying vibration, gear shifts short sharp pulses.
         */
        fun forCategory(category: SoundCategory): TransientTemplate = when (category) {
            SoundCategory.BASS_DRUM -> TransientTemplate(2f, 18f, 40f, 1.0f, 0.35f, HapticPrimitive.THUD, 1.0f)
            SoundCategory.SNARE -> TransientTemplate(1f, 10f, 25f, 0.7f, 0.8f, HapticPrimitive.CLICK, 0.75f)
            SoundCategory.CYMBAL -> TransientTemplate(1f, 4f, 20f, 0.3f, 0.95f, HapticPrimitive.TICK, 0.5f)
            SoundCategory.PERCUSSION -> TransientTemplate(1f, 6f, 20f, 0.45f, 0.7f, HapticPrimitive.TICK, 0.7f)
            SoundCategory.BEAT -> TransientTemplate(2f, 14f, 35f, 0.75f, 0.5f, HapticPrimitive.THUD, 0.8f)
            SoundCategory.BASS_DROP -> TransientTemplate(3f, 60f, 500f, 1.0f, 0.2f, HapticPrimitive.THUD, 1.0f, followsAudioDecay = true)
            SoundCategory.EXPLOSION -> TransientTemplate(
                2f, 45f, 900f, 1.0f, 0.25f, HapticPrimitive.THUD, 1.0f,
                repeats = 2, repeatGapMs = 190f, repeatDecay = 0.55f, followsAudioDecay = true,
            )
            SoundCategory.GUNSHOT -> TransientTemplate(0.5f, 12f, 40f, 0.95f, 0.95f, HapticPrimitive.CLICK, 1.0f)
            SoundCategory.PUNCH -> TransientTemplate(1f, 18f, 35f, 0.8f, 0.55f, HapticPrimitive.THUD, 0.85f)
            SoundCategory.IMPACT -> TransientTemplate(0.5f, 25f, 120f, 1.0f, 0.7f, HapticPrimitive.THUD, 1.0f, followsAudioDecay = true)
            SoundCategory.THUNDER -> TransientTemplate(220f, 150f, 1600f, 0.85f, 0.1f, null, followsAudioDecay = true)
            SoundCategory.DOOR_SLAM -> TransientTemplate(1f, 25f, 120f, 0.75f, 0.45f, HapticPrimitive.THUD, 0.8f)
            SoundCategory.FOOTSTEPS -> TransientTemplate(2f, 10f, 25f, 0.4f, 0.35f, HapticPrimitive.LOW_TICK, 0.7f)
            SoundCategory.GEAR_SHIFT -> TransientTemplate(0.5f, 18f, 25f, 0.9f, 0.9f, HapticPrimitive.CLICK, 1.0f)
            SoundCategory.VEHICLE_PASSING -> TransientTemplate(180f, 60f, 300f, 0.8f, 0.25f, null)
            SoundCategory.DIALOGUE -> TransientTemplate(5f, 5f, 20f, 0.25f, 0.5f, HapticPrimitive.LOW_TICK, 0.3f)
            else -> TransientTemplate(2f, 10f, 40f, 0.5f, 0.5f, HapticPrimitive.TICK, 0.6f)
        }
    }
}

/** Continuous textures: how each sustained category should feel. */
data class TextureTemplate(
    val intensity: Float,
    val sharpness: Float,
    /** Pulse modulation rate in Hz (0 = smooth). */
    val pulseHz: Float,
    /** Depth of the pulse modulation in [0, 1]. */
    val pulseDepth: Float,
) {
    companion object {
        fun forCategory(category: SoundCategory): TextureTemplate = when (category) {
            SoundCategory.ENGINE_IDLE -> TextureTemplate(0.35f, 0.3f, 10f, 0.6f)
            SoundCategory.ENGINE_REV -> TextureTemplate(0.6f, 0.45f, 18f, 0.35f)
            SoundCategory.ACCELERATION -> TextureTemplate(0.6f, 0.4f, 20f, 0.15f)
            SoundCategory.DECELERATION -> TextureTemplate(0.4f, 0.35f, 12f, 0.4f)
            SoundCategory.ENGINE_RUMBLE -> TextureTemplate(0.55f, 0.15f, 9f, 0.2f)
            SoundCategory.CINEMATIC_RUMBLE -> TextureTemplate(0.8f, 0.1f, 0f, 0f)
            SoundCategory.SUSTAINED_BASS -> TextureTemplate(0.5f, 0.2f, 0f, 0f)
            SoundCategory.MACHINERY -> TextureTemplate(0.3f, 0.5f, 15f, 0.5f)
            SoundCategory.RAIN -> TextureTemplate(0.15f, 0.8f, 0f, 0f)
            SoundCategory.WIND -> TextureTemplate(0.2f, 0.15f, 0f, 0f)
            SoundCategory.DIALOGUE -> TextureTemplate(0.1f, 0.5f, 0f, 0f)
            SoundCategory.GENERAL -> TextureTemplate(0.4f, 0.4f, 0f, 0f)
            else -> TextureTemplate(0.3f, 0.4f, 0f, 0f)
        }

        /** Categories rendered by the continuous layer. */
        val CONTINUOUS_CATEGORIES: List<SoundCategory> = SoundCategory.entries.filter {
            it.kind != com.hapticsync.model.SoundKind.TRANSIENT
        }

        /** Engine categories, whose texture follows the engine speed. */
        val ENGINE_CATEGORIES = setOf(
            SoundCategory.ENGINE_IDLE, SoundCategory.ENGINE_REV, SoundCategory.ACCELERATION,
            SoundCategory.DECELERATION, SoundCategory.ENGINE_RUMBLE,
        )
    }
}
