package com.hapticsync.app.ui.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hapticsync.app.AppContainer
import com.hapticsync.app.PlaybackRequest
import com.hapticsync.model.AppSettings
import com.hapticsync.model.RecentMedia
import com.hapticsync.platform.playback.PlayableItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    val recents: StateFlow<List<RecentMedia>> = container.settings.recents
    val settings: StateFlow<AppSettings> = container.settings.settings
    val capabilities = container.hapticOutput.capabilities

    /** Name of the preset currently applied. */
    val activePresetName: StateFlow<String> = combine(container.settings.presets, container.settings.settings) { presets, s ->
        presets.firstOrNull { it.id == s.activePresetId }?.name ?: "Custom"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _busy = MutableStateFlow(false)
    /** True while picked files are being inspected before playback starts. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Plays files chosen with the Photo Picker or the document picker, in the chosen order. */
    fun openUris(uris: List<Uri>) {
        if (uris.isEmpty() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val library = container.mediaLibrary
                val infos = uris.distinct().map { uri ->
                    async {
                        library.persistAccess(uri)
                        library.info(uri)
                    }
                }.awaitAll()
                container.playbackQueue.open(
                    PlaybackRequest(
                        items = infos.map { PlayableItem(it.uri, it.title, it.mimeType) },
                        audioOnly = infos.all { !it.hasVideo && it.mimeType?.startsWith("audio/") == true },
                    ),
                )
            } finally {
                _busy.value = false
            }
        }
    }

    /** Resumes a recently played file where it was left (or from the start if it was finished). */
    fun openRecent(item: RecentMedia) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val uri = Uri.parse(item.uri)
                if (!container.mediaLibrary.isAccessible(uri)) {
                    _messages.send("“${item.title}” is no longer available. It may have been moved or deleted.")
                    return@launch
                }
                val resumeAt = if (item.progress in RESUME_MIN..RESUME_MAX) item.lastPositionMs else 0L
                container.playbackQueue.open(
                    PlaybackRequest(
                        items = listOf(PlayableItem(uri, item.title, item.mimeType)),
                        startPositionMs = resumeAt,
                        audioOnly = item.audioOnly,
                    ),
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun removeRecent(item: RecentMedia) {
        container.settings.removeRecent(item.uri)
        container.mediaLibrary.releaseAccess(Uri.parse(item.uri))
    }

    fun clearRecents() {
        recents.value.forEach { container.mediaLibrary.releaseAccess(Uri.parse(it.uri)) }
        container.settings.clearRecents()
    }

    fun setHapticsEnabled(enabled: Boolean) = container.settings.updateSettings { it.copy(hapticsEnabled = enabled) }

    suspend fun thumbnail(uri: String): Bitmap? = container.mediaLibrary.thumbnail(Uri.parse(uri))

    private companion object {
        const val RESUME_MIN = 0.01f
        const val RESUME_MAX = 0.97f
    }
}
