package com.hapticsync.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.hapticsync.app.ui.HapticSyncApp
import com.hapticsync.app.ui.theme.HapticSyncTheme
import com.hapticsync.platform.playback.PlayableItem
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = appContainer
        // A restored activity already showed this intent's video before it was recreated.
        if (savedInstanceState == null) handleViewIntent(intent)
        setContent {
            HapticSyncTheme {
                HapticSyncApp(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (home, another app, screen off) stops playback and vibration at once.
        if (!isChangingConfigurations) appContainer.onAppBackgrounded()
    }

    /** "Open with HapticSync" from a file manager or gallery. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val container = appContainer
        lifecycleScope.launch {
            val persisted = container.mediaLibrary.persistAccess(uri)
            val info = container.mediaLibrary.info(uri)
            val mime = info.mimeType ?: intent.type
            container.playbackQueue.open(
                PlaybackRequest(
                    items = listOf(PlayableItem(uri, info.title, mime)),
                    audioOnly = mime?.startsWith("audio/") == true,
                    // A one-time grant from another app expires; only remember files we can reopen.
                    recordInRecents = persisted,
                ),
            )
        }
    }
}
