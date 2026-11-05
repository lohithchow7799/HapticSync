package com.hapticsync.app.ui.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hapticsync.app.AppContainer
import com.hapticsync.model.AppSettings
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.IsolationSettings
import com.hapticsync.model.Preset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PresetsViewModel(container: AppContainer) : ViewModel() {

    private val controller = container.settings

    val presets: StateFlow<List<Preset>> = controller.presets
    val settings: StateFlow<AppSettings> = controller.settings

    /** Whether the live tuning differs from the stored values of the active preset. */
    val modified: StateFlow<Boolean> = combine(controller.profile, controller.presets, controller.settings) { profile, presets, s ->
        val stored = presets.firstOrNull { it.id == s.activePresetId }?.profile ?: return@combine false
        // Isolation is a listening mode, not part of the tuning.
        stored.withoutIsolation() != profile.withoutIsolation()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun HapticProfile.withoutIsolation() = copy(isolation = IsolationSettings()).validated()

    fun apply(preset: Preset) = controller.applyPreset(preset)

    fun saveCurrent(name: String): Preset = controller.savePreset(name)

    fun rename(preset: Preset, name: String) = controller.renamePreset(preset.id, name)

    fun duplicate(preset: Preset): Preset? = controller.duplicatePreset(preset.id)

    fun delete(preset: Preset) = controller.deletePreset(preset.id)
}
