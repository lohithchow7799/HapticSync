package com.hapticsync.model

import kotlinx.serialization.Serializable

/** A named haptic configuration. Built-in presets cannot be renamed or deleted. */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val profile: HapticProfile,
    val builtIn: Boolean = false,
    val description: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
)

/**
 * The built-in presets. Each one contains real processing values tuned for its content type.
 */
object BuiltInPresets {

    const val CINEMATIC_ID = "builtin.cinematic"
    const val RACING_ID = "builtin.racing"
    const val MUSIC_ID = "builtin.music"
    const val ACTION_ID = "builtin.action"
    const val MINIMAL_ID = "builtin.minimal"
    const val CUSTOM_ID = "builtin.custom"

    private fun base() = HapticProfile().validated()

    private fun HapticProfile.categories(
        vararg entries: Pair<SoundCategory, CategorySettings.() -> CategorySettings>,
    ): HapticProfile {
        var p = this
        for ((category, transform) in entries) p = p.updateCategory(category) { it.transform() }
        return p
    }

    private fun HapticProfile.bands(vararg entries: Pair<BandId, BandSettings.() -> BandSettings>): HapticProfile {
        var p = this
        for ((band, transform) in entries) p = p.updateBand(band) { it.transform() }
        return p
    }

    /** Movies: explosions, impacts and deep rumble lead; dialogue stays silent. */
    val CINEMATIC: Preset = Preset(
        id = CINEMATIC_ID,
        name = "Cinematic",
        builtIn = true,
        description = "Explosions, impacts and deep rumble in the lead; dialogue stays silent.",
        profile = base()
            .categories(
                SoundCategory.EXPLOSION to { copy(intensity = 1f, sensitivity = 0.6f, duration = 1.2f) },
                SoundCategory.IMPACT to { copy(intensity = 0.9f, sensitivity = 0.6f) },
                SoundCategory.THUNDER to { copy(intensity = 0.85f, duration = 1.2f) },
                SoundCategory.CINEMATIC_RUMBLE to { copy(intensity = 0.75f) },
                SoundCategory.GUNSHOT to { copy(intensity = 0.8f) },
                SoundCategory.PUNCH to { copy(intensity = 0.75f) },
                SoundCategory.BASS_DROP to { copy(intensity = 0.9f) },
                SoundCategory.DOOR_SLAM to { copy(intensity = 0.6f) },
                SoundCategory.BASS_DRUM to { copy(intensity = 0.6f) },
                SoundCategory.SNARE to { copy(intensity = 0.4f) },
                SoundCategory.CYMBAL to { copy(intensity = 0.15f) },
                SoundCategory.ENGINE_REV to { copy(intensity = 0.65f) },
                SoundCategory.ACCELERATION to { copy(intensity = 0.65f) },
                SoundCategory.GEAR_SHIFT to { copy(intensity = 0.6f) },
                SoundCategory.FOOTSTEPS to { copy(intensity = 0.4f) },
            )
            .bands(
                BandId.SUB_BASS to { copy(intensity = 1f, sensitivity = 0.6f) },
                BandId.BASS to { copy(intensity = 0.9f) },
                BandId.LOW_MID to { copy(intensity = 0.7f) },
                BandId.MID to { copy(intensity = 0.5f) },
                BandId.HIGH_MID to { copy(intensity = 0.35f) },
                BandId.HIGH to { copy(intensity = 0.25f) },
            )
            .copy(haptics = HapticParameters(releaseMs = 220f, continuousGain = 0.65f, eventSensitivity = 0.55f)),
    )

    /** Motorsport: engine texture, revs, acceleration build-up and crisp gear shifts. */
    val RACING: Preset = Preset(
        id = RACING_ID,
        name = "Racing",
        builtIn = true,
        description = "Engine texture, rev build-ups and crisp gear-shift kicks.",
        profile = base()
            .categories(
                SoundCategory.ENGINE_REV to { copy(intensity = 1f, sensitivity = 0.7f, priority = 6) },
                SoundCategory.ACCELERATION to { copy(intensity = 1f, sensitivity = 0.7f, priority = 6) },
                SoundCategory.GEAR_SHIFT to { copy(intensity = 1f, sensitivity = 0.7f, priority = 9) },
                SoundCategory.ENGINE_RUMBLE to { copy(intensity = 0.8f) },
                SoundCategory.ENGINE_IDLE to { copy(intensity = 0.45f) },
                SoundCategory.DECELERATION to { copy(intensity = 0.7f) },
                SoundCategory.VEHICLE_PASSING to { copy(intensity = 0.9f, priority = 7) },
                SoundCategory.IMPACT to { copy(intensity = 0.85f) },
                SoundCategory.EXPLOSION to { copy(intensity = 0.7f) },
                SoundCategory.BASS_DRUM to { copy(intensity = 0.35f) },
                SoundCategory.SNARE to { copy(intensity = 0.2f) },
                SoundCategory.CYMBAL to { copy(enabled = false) },
                SoundCategory.BEAT to { copy(intensity = 0.3f) },
                SoundCategory.SUSTAINED_BASS to { copy(intensity = 0.3f) },
                SoundCategory.WIND to { copy(enabled = false) },
            )
            .bands(
                BandId.SUB_BASS to { copy(intensity = 0.8f) },
                BandId.BASS to { copy(intensity = 1f, sensitivity = 0.6f) },
                BandId.LOW_MID to { copy(intensity = 0.9f, sensitivity = 0.6f) },
                BandId.MID to { copy(intensity = 0.7f) },
                BandId.HIGH_MID to { copy(intensity = 0.35f) },
                BandId.HIGH to { copy(intensity = 0.15f) },
            )
            .copy(
                haptics = HapticParameters(
                    releaseMs = 120f,
                    pulseSpacingMs = 35f,
                    continuousGain = 0.8f,
                    eventSensitivity = 0.6f,
                ),
            ),
    )

    /** Music: kicks, snares, drops and the bass line; cymbals stay light. */
    val MUSIC: Preset = Preset(
        id = MUSIC_ID,
        name = "Music",
        builtIn = true,
        description = "Kick drums, snares, drops and the bass line; cymbals stay light.",
        profile = base()
            .categories(
                SoundCategory.BASS_DRUM to { copy(intensity = 1f, sensitivity = 0.7f, priority = 9) },
                SoundCategory.SNARE to { copy(intensity = 0.7f, sensitivity = 0.6f) },
                SoundCategory.CYMBAL to { copy(intensity = 0.2f) },
                SoundCategory.PERCUSSION to { copy(intensity = 0.4f) },
                SoundCategory.BEAT to { copy(intensity = 0.8f) },
                SoundCategory.BASS_DROP to { copy(intensity = 1f, priority = 9) },
                SoundCategory.SUSTAINED_BASS to { copy(intensity = 0.55f) },
                SoundCategory.EXPLOSION to { copy(intensity = 0.5f) },
                SoundCategory.IMPACT to { copy(intensity = 0.5f) },
                SoundCategory.ENGINE_REV to { copy(intensity = 0.3f) },
                SoundCategory.ACCELERATION to { copy(intensity = 0.3f) },
                SoundCategory.ENGINE_IDLE to { copy(enabled = false) },
                SoundCategory.FOOTSTEPS to { copy(enabled = false) },
            )
            .bands(
                BandId.SUB_BASS to { copy(intensity = 0.9f) },
                BandId.BASS to { copy(intensity = 1f, sensitivity = 0.65f, pulseStrength = 0.9f) },
                BandId.LOW_MID to { copy(intensity = 0.6f) },
                BandId.MID to { copy(intensity = 0.45f) },
                BandId.HIGH_MID to { copy(intensity = 0.4f) },
                BandId.HIGH to { copy(intensity = 0.2f) },
            )
            .copy(haptics = HapticParameters(releaseMs = 90f, attackMs = 2f, continuousGain = 0.4f, pulseSpacingMs = 40f)),
    )

    /** Action: gunfire, punches and impacts as sharp, distinct hits. */
    val ACTION: Preset = Preset(
        id = ACTION_ID,
        name = "Action",
        builtIn = true,
        description = "Gunfire, punches and impacts as sharp, distinct hits.",
        profile = base()
            .categories(
                SoundCategory.GUNSHOT to { copy(intensity = 1f, sensitivity = 0.7f, priority = 9) },
                SoundCategory.PUNCH to { copy(intensity = 0.9f, sensitivity = 0.65f) },
                SoundCategory.IMPACT to { copy(intensity = 1f, sensitivity = 0.65f) },
                SoundCategory.EXPLOSION to { copy(intensity = 1f) },
                SoundCategory.DOOR_SLAM to { copy(intensity = 0.6f) },
                SoundCategory.FOOTSTEPS to { copy(intensity = 0.55f, sensitivity = 0.6f) },
                SoundCategory.GEAR_SHIFT to { copy(intensity = 0.7f) },
                SoundCategory.CINEMATIC_RUMBLE to { copy(intensity = 0.5f) },
                SoundCategory.CYMBAL to { copy(intensity = 0.1f) },
            )
            .bands(
                BandId.SUB_BASS to { copy(intensity = 0.9f) },
                BandId.BASS to { copy(intensity = 0.9f) },
                BandId.LOW_MID to { copy(intensity = 0.8f, pulseStrength = 0.85f) },
                BandId.MID to { copy(intensity = 0.65f, pulseStrength = 0.8f) },
                BandId.HIGH_MID to { copy(intensity = 0.45f) },
                BandId.HIGH to { copy(intensity = 0.3f) },
            )
            .copy(
                haptics = HapticParameters(
                    pulseSpacingMs = 30f,
                    eventSensitivity = 0.7f,
                    releaseMs = 100f,
                    continuousGain = 0.5f,
                    envelope = EnvelopeShape.SHARP,
                ),
            ),
    )

    /** Minimal: only the biggest moments, at moderate strength; no continuous texture. */
    val MINIMAL: Preset = Preset(
        id = MINIMAL_ID,
        name = "Minimal",
        builtIn = true,
        description = "Only the biggest moments, at moderate strength; no continuous texture.",
        profile = run {
            val keep = mapOf(
                SoundCategory.EXPLOSION to 0.6f,
                SoundCategory.IMPACT to 0.5f,
                SoundCategory.BASS_DRUM to 0.45f,
                SoundCategory.GUNSHOT to 0.5f,
                SoundCategory.GEAR_SHIFT to 0.4f,
                SoundCategory.BASS_DROP to 0.5f,
                SoundCategory.THUNDER to 0.45f,
            )
            var p = base()
            for (category in SoundCategory.entries) {
                p = p.updateCategory(category) {
                    val intensity = keep[category]
                    if (intensity == null) it.copy(enabled = false) else it.copy(intensity = intensity, sensitivity = 0.35f)
                }
            }
            p.copy(
                haptics = HapticParameters(
                    masterIntensity = 0.6f,
                    continuousLayer = false,
                    eventSensitivity = 0.35f,
                    minAmplitudeDb = -40f,
                    pulseSpacingMs = 90f,
                    releaseMs = 80f,
                ),
            )
        },
    )

    /** The user's own working configuration; starts from the cinematic tuning. */
    val CUSTOM: Preset = Preset(
        id = CUSTOM_ID,
        name = "Custom",
        builtIn = true,
        description = "Your own tuning. Changes made in Haptic Studio are kept here.",
        profile = CINEMATIC.profile,
    )

    val all: List<Preset> = listOf(CINEMATIC, RACING, MUSIC, ACTION, MINIMAL, CUSTOM)

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }
}
