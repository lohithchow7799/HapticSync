package com.hapticsync.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the app and walks through its main screens. */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openFromHome(tile: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(tile))
        compose.onNodeWithText(tile).performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
    }

    @Test
    fun homeShowsTheWaysToStart() {
        compose.onNodeWithText("HapticSync").assertIsDisplayed()
        compose.onNodeWithText("Choose video").assertIsDisplayed()
        compose.onNodeWithText("Browse files").assertIsDisplayed()
    }

    @Test
    fun hapticStudioShowsEveryControlGroup() {
        openFromHome("Haptic Studio")
        compose.onNodeWithText("Feel").assertIsDisplayed()
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Sound isolation"))
        list.performScrollToNode(hasText("Analysis"))
        list.performScrollToNode(hasText("Frequency bands"))
        list.performScrollToNode(hasText("Sub-bass"))
        list.performScrollToNode(hasText("Sound categories"))
        list.performScrollToNode(hasText("Gear shifts"))
        back()
        compose.onNodeWithText("Choose video").assertIsDisplayed()
    }

    @Test
    fun calibrationReportsCapabilitiesAndPatterns() {
        openFromHome("Calibration")
        compose.onNodeWithText("Your phone's vibration hardware").assertIsDisplayed()
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Test patterns"))
        list.performScrollToNode(hasText("Long rumble"))
        list.performScrollToNode(hasText("Run synchronisation test"))
        list.performScrollToNode(hasText("Save calibration"))
    }

    @Test
    fun presetsListTheBuiltIns() {
        openFromHome("Presets")
        val list = compose.onNode(hasScrollAction())
        for (name in listOf("Cinematic", "Racing", "Music", "Action", "Minimal", "Custom")) {
            list.performScrollToNode(hasText(name))
        }
    }

    @Test
    fun demoModeListsTheScenes() {
        openFromHome("Demo mode")
        compose.onNodeWithText("Race car on-board").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sync test clicks"))
    }
}
