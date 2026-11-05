package com.hapticsync.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hapticsync.model.AppSettings
import com.hapticsync.model.BuiltInPresets
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.ModelJson
import com.hapticsync.model.Preset
import com.hapticsync.model.RecentMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.hapticSyncStore: DataStore<Preferences> by preferencesDataStore(name = "hapticsync")

/** Everything HapticSync persists, as JSON documents in a Preferences DataStore. */
data class StoredState(
    val settings: AppSettings,
    val workingProfile: HapticProfile,
    val customProfile: HapticProfile,
    val userPresets: List<Preset>,
    val calibrations: List<CalibrationProfile>,
    val recents: List<RecentMedia>,
)

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.hapticSyncStore

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val state: Flow<StoredState> = data.map { prefs ->
        StoredState(
            settings = prefs[KEY_SETTINGS]?.let(ModelJson::decodeSettings) ?: AppSettings(),
            workingProfile = prefs[KEY_WORKING_PROFILE]?.let(ModelJson::decodeProfile) ?: BuiltInPresets.CINEMATIC.profile,
            customProfile = prefs[KEY_CUSTOM_PROFILE]?.let(ModelJson::decodeProfile) ?: BuiltInPresets.CUSTOM.profile,
            userPresets = prefs[KEY_USER_PRESETS]?.let(ModelJson::decodePresets) ?: emptyList(),
            calibrations = prefs[KEY_CALIBRATIONS]?.let(ModelJson::decodeCalibrations) ?: emptyList(),
            recents = prefs[KEY_RECENTS]?.let(ModelJson::decodeRecents) ?: emptyList(),
        )
    }

    suspend fun load(): StoredState = state.first()

    suspend fun saveSettings(settings: AppSettings) = store.edit { it[KEY_SETTINGS] = ModelJson.encodeSettings(settings) }

    suspend fun saveWorkingProfile(profile: HapticProfile) = store.edit { it[KEY_WORKING_PROFILE] = ModelJson.encodeProfile(profile) }

    suspend fun saveCustomProfile(profile: HapticProfile) = store.edit { it[KEY_CUSTOM_PROFILE] = ModelJson.encodeProfile(profile) }

    suspend fun saveUserPresets(presets: List<Preset>) = store.edit { it[KEY_USER_PRESETS] = ModelJson.encodePresets(presets) }

    suspend fun saveCalibrations(profiles: List<CalibrationProfile>) =
        store.edit { it[KEY_CALIBRATIONS] = ModelJson.encodeCalibrations(profiles) }

    suspend fun saveRecents(items: List<RecentMedia>) = store.edit { it[KEY_RECENTS] = ModelJson.encodeRecents(items) }

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("app_settings")
        val KEY_WORKING_PROFILE = stringPreferencesKey("working_profile")
        val KEY_CUSTOM_PROFILE = stringPreferencesKey("custom_profile")
        val KEY_USER_PRESETS = stringPreferencesKey("user_presets")
        val KEY_CALIBRATIONS = stringPreferencesKey("calibrations")
        val KEY_RECENTS = stringPreferencesKey("recents")
    }
}
