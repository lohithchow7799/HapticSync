package com.hapticsync.app.data

import com.hapticsync.engine.HapticSyncEngine
import com.hapticsync.model.AppSettings
import com.hapticsync.model.BuiltInPresets
import com.hapticsync.model.CalibrationProfile
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.IsolationSettings
import com.hapticsync.model.Preset
import com.hapticsync.model.RecentMedia
import com.hapticsync.platform.vibration.AndroidHapticOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns the live configuration of HapticSync. Every change is applied to the [engine] at once
 * (so Haptic Studio edits are felt immediately, even during playback) and persisted shortly
 * afterwards (debounced, so dragging a slider does not hammer storage).
 */
@OptIn(FlowPreview::class)
class HapticSettingsController(
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
    private val engine: HapticSyncEngine,
    private val output: AndroidHapticOutput,
    val deviceKey: String,
) {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _profile = MutableStateFlow(BuiltInPresets.CINEMATIC.profile)
    val profile: StateFlow<HapticProfile> = _profile.asStateFlow()

    private val _customProfile = MutableStateFlow(BuiltInPresets.CUSTOM.profile)
    private val _userPresets = MutableStateFlow<List<Preset>>(emptyList())

    private val _presets = MutableStateFlow(BuiltInPresets.all)
    /** Built-in presets (with "Custom" holding the user's latest tuning) followed by user presets. */
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _calibration = MutableStateFlow(CalibrationProfile(deviceKey = deviceKey))
    val calibration: StateFlow<CalibrationProfile> = _calibration.asStateFlow()
    private var otherCalibrations: List<CalibrationProfile> = emptyList()

    private val _recents = MutableStateFlow<List<RecentMedia>>(emptyList())
    val recents: StateFlow<List<RecentMedia>> = _recents.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            val stored = repository.load()
            _settings.value = stored.settings
            _profile.value = stored.workingProfile
            _customProfile.value = stored.customProfile
            _userPresets.value = stored.userPresets
            otherCalibrations = stored.calibrations.filter { it.deviceKey != deviceKey }
            _calibration.value = stored.calibrations.firstOrNull { it.deviceKey == deviceKey } ?: CalibrationProfile(deviceKey = deviceKey)
            _recents.value = stored.recents
            rebuildPresets()
            applyToEngine()
            _loaded.value = true
            persistOnChange()
        }
    }

    private fun applyToEngine() {
        engine.updateProfile(_profile.value)
        engine.updateCalibration(_calibration.value)
        engine.hapticsEnabled = _settings.value.hapticsEnabled
        output.usage = _calibration.value.usage
    }

    private fun persistOnChange() {
        scope.launch { _profile.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { repository.saveWorkingProfile(it) } }
        scope.launch { _customProfile.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { repository.saveCustomProfile(it) } }
        scope.launch { _settings.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { repository.saveSettings(it) } }
        scope.launch { _userPresets.drop(1).collect { repository.saveUserPresets(it) } }
        scope.launch {
            _calibration.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { repository.saveCalibrations(otherCalibrations + it) }
        }
        scope.launch { _recents.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { repository.saveRecents(it) } }
    }

    private fun rebuildPresets() {
        val builtIns = BuiltInPresets.all.map { if (it.id == BuiltInPresets.CUSTOM_ID) it.copy(profile = _customProfile.value) else it }
        _presets.value = builtIns + _userPresets.value
    }

    // ---- Profile & presets ---------------------------------------------------------------

    val activePresetId: String get() = _settings.value.activePresetId

    fun activePreset(): Preset? = _presets.value.firstOrNull { it.id == _settings.value.activePresetId }


    /**
     * Edits the working profile. Edits become the user's "Custom" tuning, so built-in presets
     * are never overwritten.
     */
    fun updateProfile(transform: (HapticProfile) -> HapticProfile) {
        val next = transform(_profile.value).validated()
        _profile.value = next
        engine.updateProfile(next)
        _customProfile.value = next
        if (_settings.value.activePresetId != BuiltInPresets.CUSTOM_ID) {
            _settings.update { it.copy(activePresetId = BuiltInPresets.CUSTOM_ID) }
        }
        rebuildPresets()
    }

    /** Changes the working profile without switching to "Custom" (e.g. toggling isolation). */
    fun updateProfileInPlace(transform: (HapticProfile) -> HapticProfile) {
        val next = transform(_profile.value).validated()
        _profile.value = next
        engine.updateProfile(next)
    }

    fun applyPreset(preset: Preset) {
        // Isolation is a listening mode: switching presets keeps it as it is.
        val profile = preset.profile.copy(isolation = _profile.value.isolation).validated()
        _profile.value = profile
        engine.updateProfile(profile)
        _settings.update { it.copy(activePresetId = preset.id) }
    }

    fun savePreset(name: String): Preset {
        val trimmed = name.trim().ifEmpty { "My preset" }
        val now = System.currentTimeMillis()
        val existing = _userPresets.value.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        val tuning = _profile.value.copy(isolation = IsolationSettings())
        val preset = existing?.copy(profile = tuning, updatedAtMs = now)
            ?: Preset(id = "user." + UUID.randomUUID(), name = trimmed, profile = tuning, createdAtMs = now, updatedAtMs = now)
        _userPresets.value = if (existing != null) _userPresets.value.map { if (it.id == existing.id) preset else it } else _userPresets.value + preset
        _settings.update { it.copy(activePresetId = preset.id) }
        rebuildPresets()
        return preset
    }

    fun renamePreset(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _userPresets.value = _userPresets.value.map { if (it.id == id) it.copy(name = trimmed, updatedAtMs = System.currentTimeMillis()) else it }
        rebuildPresets()
    }

    fun duplicatePreset(id: String): Preset? {
        val source = _presets.value.firstOrNull { it.id == id } ?: return null
        val now = System.currentTimeMillis()
        var name = "${source.name} copy"
        var n = 2
        while (_presets.value.any { it.name.equals(name, ignoreCase = true) }) name = "${source.name} copy ${n++}"
        val copy = Preset(id = "user." + UUID.randomUUID(), name = name, profile = source.profile, createdAtMs = now, updatedAtMs = now,
            description = source.description)
        _userPresets.value = _userPresets.value + copy
        rebuildPresets()
        return copy
    }

    fun deletePreset(id: String) {
        _userPresets.value = _userPresets.value.filterNot { it.id == id }
        if (_settings.value.activePresetId == id) _settings.update { it.copy(activePresetId = BuiltInPresets.CUSTOM_ID) }
        rebuildPresets()
    }

    // ---- Calibration -------------------------------------------------------------------------

    fun updateCalibration(transform: (CalibrationProfile) -> CalibrationProfile) {
        val next = transform(_calibration.value).validated().copy(deviceKey = deviceKey)
        _calibration.value = next
        engine.updateCalibration(next)
        output.usage = next.usage
    }

    fun saveCalibration() {
        updateCalibration { it.copy(savedAtMs = System.currentTimeMillis()) }
    }

    fun resetCalibration() {
        updateCalibration { CalibrationProfile(deviceKey = deviceKey) }
    }

    // ---- App settings --------------------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        engine.hapticsEnabled = next.hapticsEnabled
    }

    // ---- Recents ---------------------------------------------------------------------------------

    fun recordPlayed(item: RecentMedia) {
        val rest = _recents.value.filterNot { it.uri == item.uri }
        _recents.value = (listOf(item.copy(lastPlayedAtMs = System.currentTimeMillis())) + rest).take(RecentMedia.MAX_ENTRIES)
    }

    fun updateProgress(uri: String, positionMs: Long, durationMs: Long) {
        _recents.update { list ->
            list.map { if (it.uri == uri) it.copy(lastPositionMs = positionMs, durationMs = if (durationMs > 0) durationMs else it.durationMs) else it }
        }
    }

    fun removeRecent(uri: String) {
        _recents.update { list -> list.filterNot { it.uri == uri } }
    }

    fun clearRecents() {
        _recents.value = emptyList()
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 400L
    }
}
