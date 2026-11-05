package com.hapticsync.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JSON encoding of persisted model objects.
 *
 * Decoding is lenient: unknown keys are ignored and unknown enum values fall back to defaults,
 * so settings written by a newer app version never crash an older one. Any value that cannot be
 * decoded yields `null`, and callers fall back to defaults.
 */
object ModelJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun encodeProfile(profile: HapticProfile): String = json.encodeToString(HapticProfile.serializer(), profile)

    fun decodeProfile(text: String): HapticProfile? = decode(HapticProfile.serializer(), text)?.validated()

    fun encodePresets(presets: List<Preset>): String = json.encodeToString(ListSerializer(Preset.serializer()), presets)

    fun decodePresets(text: String): List<Preset>? =
        decode(ListSerializer(Preset.serializer()), text)?.map { it.copy(profile = it.profile.validated()) }

    fun encodeCalibration(profile: CalibrationProfile): String = json.encodeToString(CalibrationProfile.serializer(), profile)

    fun decodeCalibration(text: String): CalibrationProfile? = decode(CalibrationProfile.serializer(), text)?.validated()

    fun encodeCalibrations(profiles: List<CalibrationProfile>): String =
        json.encodeToString(ListSerializer(CalibrationProfile.serializer()), profiles)

    fun decodeCalibrations(text: String): List<CalibrationProfile>? =
        decode(ListSerializer(CalibrationProfile.serializer()), text)?.map { it.validated() }

    fun encodeSettings(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), settings)

    fun decodeSettings(text: String): AppSettings? = decode(AppSettings.serializer(), text)

    fun encodeRecents(items: List<RecentMedia>): String = json.encodeToString(ListSerializer(RecentMedia.serializer()), items)

    fun decodeRecents(text: String): List<RecentMedia>? = decode(ListSerializer(RecentMedia.serializer()), text)

    private fun <T> decode(serializer: KSerializer<T>, text: String): T? =
        try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            null
        }
}
