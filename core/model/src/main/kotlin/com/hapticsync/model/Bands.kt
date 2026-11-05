package com.hapticsync.model

import kotlinx.serialization.Serializable

/**
 * The analysis frequency bands. Default ranges follow the HapticSync specification; every range
 * can be changed by the user in Haptic Studio.
 *
 * Note that these are *audio* frequencies. A phone's vibration motor cannot reproduce them; the
 * haptic engine maps the energy and dynamics measured in each band onto vibration patterns the
 * actuator can actually produce.
 */
@Serializable
enum class BandId(
    val label: String,
    val defaultLowHz: Float,
    val defaultHighHz: Float,
    val interpretation: String,
) {
    SUB_BASS("Sub-bass", 20f, 60f, "Deep explosions, heavy impacts, cinematic rumble"),
    BASS("Bass", 60f, 150f, "Engine rumble, kicks, low-frequency impacts"),
    LOW_MID("Low-mid", 150f, 400f, "Engine body, mechanical sounds"),
    MID("Midrange", 400f, 2000f, "General mechanical and environmental sounds"),
    HIGH_MID("High-mid", 2000f, 6000f, "Drums, metallic sounds, sharper effects"),
    HIGH("High-frequency", 6000f, 12000f, "Crashes, clicks, sharp transient details"),
    ;

    companion object {
        const val COUNT = 6

        /** Lowest and highest frequency the user can assign to any band. */
        const val MIN_HZ = 15f
        const val MAX_HZ = 20_000f
    }
}

/**
 * Per-band controls (Haptic Studio → Frequency bands).
 *
 * @property enabled whether the band's energy participates in detection and haptics at all.
 * @property lowHz lower band edge.
 * @property highHz upper band edge.
 * @property sensitivity detection sensitivity in [0, 1]: higher values detect quieter onsets.
 * @property intensity haptic weight in [0, 1] applied to output driven by this band's energy.
 * @property thresholdDb minimum band level (dBFS) below which the band is treated as silent.
 * @property pulseStrength strength in [0, 1] of transient pulses dominated by this band.
 * @property smoothing envelope smoothing in [0, 1]; higher values give a slower, smoother release.
 */
@Serializable
data class BandSettings(
    val band: BandId,
    val enabled: Boolean = true,
    val lowHz: Float = band.defaultLowHz,
    val highHz: Float = band.defaultHighHz,
    val sensitivity: Float = 0.5f,
    val intensity: Float = 0.7f,
    val thresholdDb: Float = -60f,
    val pulseStrength: Float = 0.7f,
    val smoothing: Float = 0.4f,
) {
    /** Returns a copy with every value clamped to its valid range. */
    fun validated(): BandSettings {
        val lo = lowHz.finiteOr(band.defaultLowHz).coerceIn(BandId.MIN_HZ, BandId.MAX_HZ - 1f)
        val hi = highHz.finiteOr(band.defaultHighHz).coerceIn(lo + 1f, BandId.MAX_HZ)
        return copy(
            lowHz = lo,
            highHz = hi,
            sensitivity = sensitivity.unit(),
            intensity = intensity.unit(),
            thresholdDb = thresholdDb.finiteOr(-60f).coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB),
            pulseStrength = pulseStrength.unit(),
            smoothing = smoothing.unit(),
        )
    }

    companion object {
        const val MIN_THRESHOLD_DB = -90f
        const val MAX_THRESHOLD_DB = -10f

        fun defaults(): List<BandSettings> = BandId.entries.map { BandSettings(it) }
    }
}

internal fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

internal fun Float.unit(): Float = finiteOr(0f).coerceIn(0f, 1f)
