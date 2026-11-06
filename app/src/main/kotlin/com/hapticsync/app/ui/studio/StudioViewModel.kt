package com.hapticsync.app.ui.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hapticsync.app.AppContainer
import com.hapticsync.model.AnalysisSettings
import com.hapticsync.model.BandId
import com.hapticsync.model.BandSettings
import com.hapticsync.model.CategorySettings
import com.hapticsync.model.HapticParameters
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.IsolationSettings
import com.hapticsync.model.SoundCategory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Haptic Studio: every edit is applied to the engine immediately (so it can be felt during
 * playback) and becomes the user's "Custom" tuning.
 */
class StudioViewModel(private val container: AppContainer) : ViewModel() {

    private val controller = container.settings

    val profile: StateFlow<HapticProfile> = controller.profile
    val capabilities = container.hapticOutput.capabilities

    /** Name of the preset the current tuning belongs to. */
    val presetName: StateFlow<String> = combine(controller.presets, controller.settings) { presets, s ->
        presets.firstOrNull { it.id == s.activePresetId }?.name ?: "Custom"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "Custom")

    fun updateHaptics(transform: (HapticParameters) -> HapticParameters) =
        controller.updateProfile { it.copy(haptics = transform(it.haptics)) }

    fun updateAnalysis(transform: (AnalysisSettings) -> AnalysisSettings) =
        controller.updateProfile { it.copy(analysis = transform(it.analysis)) }

    fun updateBand(band: BandId, transform: (BandSettings) -> BandSettings) =
        controller.updateProfile { it.updateBand(band, transform) }

    fun updateCategory(category: SoundCategory, transform: (CategorySettings) -> CategorySettings) =
        controller.updateProfile { it.updateCategory(category, transform) }

    fun resetBand(band: BandId) = controller.updateProfile { it.withBand(BandSettings(band)) }

    fun resetCategory(category: SoundCategory) = controller.updateProfile { it.withCategory(CategorySettings.defaultFor(category)) }

    /** Isolation is a listening mode, so toggling it does not turn the preset into "Custom". */
    fun setIsolation(isolation: IsolationSettings) =
        controller.updateProfileInPlace { it.copy(isolation = isolation.validated()) }

    /** Plays [category]'s haptic response with the current settings. */
    fun testCategory(category: SoundCategory): Boolean = container.engine.previewCategory(category)

    fun stopTest() = container.engine.stopOutput()

    fun savePreset(name: String) = controller.savePreset(name)
}
