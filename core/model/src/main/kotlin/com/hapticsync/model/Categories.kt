package com.hapticsync.model

import kotlinx.serialization.Serializable

/** Groups of related sound categories, used for presentation and isolation. */
@Serializable
enum class SoundFamily(val label: String) {
    VEHICLE("Vehicles & engines"),
    MUSIC("Drums & music"),
    CINEMATIC("Cinematic effects"),
    ENVIRONMENT("Environment"),
    BACKGROUND("Background"),
}

/** Whether a category is rendered as discrete pulses, as a continuous texture, or both. */
@Serializable
enum class SoundKind { TRANSIENT, CONTINUOUS, MIXED }

/**
 * Sound categories recognised by the analysis.
 *
 * Categories are inferred from *acoustic characteristics* (spectral balance, attack and decay,
 * pitch dynamics, periodicity, stereo movement). DSP alone cannot know that a sound is literally
 * an engine or a drum; it detects sounds that behave like one. An optional on-device classifier
 * can add semantic evidence on top of the DSP features.
 *
 * The enum order is part of the persisted format only through names, never ordinals.
 */
@Serializable
enum class SoundCategory(
    val label: String,
    val family: SoundFamily,
    val kind: SoundKind,
    val defaultPriority: Int,
    val description: String,
) {
    // Vehicles
    ENGINE_IDLE("Engine idle", SoundFamily.VEHICLE, SoundKind.CONTINUOUS, 3, "Steady, low-speed harmonic engine tone"),
    ENGINE_REV("Engine revving", SoundFamily.VEHICLE, SoundKind.CONTINUOUS, 5, "Engine pitch rising or falling quickly"),
    ACCELERATION("Acceleration", SoundFamily.VEHICLE, SoundKind.CONTINUOUS, 5, "Sustained rise in engine pitch under load"),
    DECELERATION("Deceleration", SoundFamily.VEHICLE, SoundKind.CONTINUOUS, 4, "Falling engine pitch: lift-off, braking"),
    GEAR_SHIFT("Gear shifts", SoundFamily.VEHICLE, SoundKind.TRANSIENT, 7, "Abrupt drop of engine pitch between gears"),
    VEHICLE_PASSING("Vehicle passing", SoundFamily.VEHICLE, SoundKind.TRANSIENT, 6, "Loudness swell with Doppler pitch drop"),
    ENGINE_RUMBLE("Engine rumble", SoundFamily.VEHICLE, SoundKind.CONTINUOUS, 4, "Heavy low-frequency engine energy"),

    // Music
    BASS_DRUM("Bass drums", SoundFamily.MUSIC, SoundKind.TRANSIENT, 7, "Kick drums: short low-frequency thumps"),
    SNARE("Snare drums", SoundFamily.MUSIC, SoundKind.TRANSIENT, 6, "Bright, noisy mid/high drum hits"),
    CYMBAL("Cymbals", SoundFamily.MUSIC, SoundKind.TRANSIENT, 2, "High-frequency crashes and hats"),
    PERCUSSION("Percussion", SoundFamily.MUSIC, SoundKind.TRANSIENT, 4, "Other percussive hits"),
    BASS_DROP("Bass drops", SoundFamily.MUSIC, SoundKind.TRANSIENT, 8, "Sudden, sustained surge of bass"),
    BEAT("Musical beats", SoundFamily.MUSIC, SoundKind.TRANSIENT, 5, "Strong onsets on a regular beat grid"),
    SUSTAINED_BASS("Sustained bass", SoundFamily.MUSIC, SoundKind.CONTINUOUS, 3, "Held low-frequency musical notes"),

    // Cinematic
    EXPLOSION("Explosions", SoundFamily.CINEMATIC, SoundKind.TRANSIENT, 9, "Massive broadband blast with a long rumble"),
    GUNSHOT("Gunshots", SoundFamily.CINEMATIC, SoundKind.TRANSIENT, 8, "Extremely sharp, short broadband blasts"),
    PUNCH("Punches", SoundFamily.CINEMATIC, SoundKind.TRANSIENT, 7, "Short, dull mid/low hits"),
    IMPACT("Impacts", SoundFamily.CINEMATIC, SoundKind.TRANSIENT, 8, "Collisions and heavy impacts"),
    THUNDER("Thunder", SoundFamily.CINEMATIC, SoundKind.TRANSIENT, 7, "Slow, deep, rolling rumble"),
    CINEMATIC_RUMBLE("Cinematic rumble", SoundFamily.CINEMATIC, SoundKind.CONTINUOUS, 4, "Sustained sub-bass texture"),

    // Environment
    FOOTSTEPS("Footsteps", SoundFamily.ENVIRONMENT, SoundKind.TRANSIENT, 4, "Soft rhythmic steps"),
    DOOR_SLAM("Door slams", SoundFamily.ENVIRONMENT, SoundKind.TRANSIENT, 6, "Single dull thud with a short rattle"),
    MACHINERY("Machinery", SoundFamily.ENVIRONMENT, SoundKind.CONTINUOUS, 2, "Steady motors, hum and clanking"),
    RAIN("Rain", SoundFamily.ENVIRONMENT, SoundKind.CONTINUOUS, 1, "Dense high-frequency patter"),
    WIND("Wind", SoundFamily.ENVIRONMENT, SoundKind.CONTINUOUS, 1, "Gusting low-frequency noise"),

    // Background
    DIALOGUE("Dialogue", SoundFamily.BACKGROUND, SoundKind.CONTINUOUS, 0, "Speech; suppresses haptics while active"),
    GENERAL("General background audio", SoundFamily.BACKGROUND, SoundKind.MIXED, 1, "Any other audio energy"),
    ;

    companion object {
        val COUNT: Int = entries.size

        fun byFamily(family: SoundFamily): List<SoundCategory> = entries.filter { it.family == family }

        fun fromName(name: String): SoundCategory? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Per-category controls (Haptic Studio → Sound categories).
 *
 * @property enabled whether this category may produce vibration.
 * @property sensitivity detection sensitivity in [0, 1]: higher values accept weaker evidence.
 * @property intensity haptic intensity in [0, 1].
 * @property duration duration multiplier for this category's patterns ([MIN_DURATION]..[MAX_DURATION]).
 * @property priority mixing priority 0–10; higher-priority effects interrupt lower ones.
 */
@Serializable
data class CategorySettings(
    val category: SoundCategory,
    val enabled: Boolean = true,
    val sensitivity: Float = 0.5f,
    val intensity: Float = 0.7f,
    val duration: Float = 1f,
    val priority: Int = category.defaultPriority,
) {
    fun validated(): CategorySettings = copy(
        sensitivity = sensitivity.unit(),
        intensity = intensity.unit(),
        duration = duration.finiteOr(1f).coerceIn(MIN_DURATION, MAX_DURATION),
        priority = priority.coerceIn(MIN_PRIORITY, MAX_PRIORITY),
    )

    companion object {
        const val MIN_DURATION = 0.25f
        const val MAX_DURATION = 3f
        const val MIN_PRIORITY = 0
        const val MAX_PRIORITY = 10

        fun defaults(): List<CategorySettings> = SoundCategory.entries.map { defaultFor(it) }

        /** Neutral defaults: dialogue is muted and the noisiest textures are kept subtle. */
        fun defaultFor(category: SoundCategory): CategorySettings = when (category) {
            SoundCategory.DIALOGUE -> CategorySettings(category, enabled = false, intensity = 0.1f)
            SoundCategory.CYMBAL -> CategorySettings(category, intensity = 0.25f)
            SoundCategory.RAIN, SoundCategory.WIND -> CategorySettings(category, intensity = 0.2f)
            SoundCategory.MACHINERY -> CategorySettings(category, intensity = 0.3f)
            SoundCategory.GENERAL -> CategorySettings(category, intensity = 0.3f, sensitivity = 0.4f)
            else -> CategorySettings(category)
        }
    }
}
