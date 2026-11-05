package com.hapticsync.app.ui.settings

import androidx.lifecycle.ViewModel
import com.hapticsync.app.AppContainer
import com.hapticsync.app.BuildConfig
import com.hapticsync.model.AppSettings
import com.hapticsync.model.BuiltInPresets
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(container: AppContainer) : ViewModel() {

    private val controller = container.settings

    val settings: StateFlow<AppSettings> = controller.settings
    val hasVibrator: Boolean = container.hapticOutput.capabilities.hasVibrator
    val versionName: String = BuildConfig.VERSION_NAME

    fun update(transform: (AppSettings) -> AppSettings) = controller.updateSettings(transform)

    /** Returns to the default tuning (the Cinematic preset). Saved presets are kept. */
    fun restoreDefaultTuning() = controller.applyPreset(BuiltInPresets.CINEMATIC)
}
