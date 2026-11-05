package com.hapticsync.app

import com.hapticsync.app.ui.components.LogFrequency
import com.hapticsync.app.ui.components.formatHz
import com.hapticsync.app.ui.components.formatTime
import com.hapticsync.model.BandId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class PlaybackQueueTest {

    @Test
    fun openedRequestIsConsumedExactlyOnce() = runTest {
        val queue = PlaybackQueue()
        val request = PlaybackRequest(items = emptyList(), startPositionMs = 1234L)
        queue.open(request)
        assertEquals(request, queue.request.value)
        assertEquals(Unit, queue.navigation.first())
        assertEquals(request, queue.consume())
        assertNull(queue.request.value)
        assertNull(queue.consume())
    }

    @Test
    fun navigationEventsAreConflatedAndNotReplayed() = runTest {
        val queue = PlaybackQueue()
        queue.open(PlaybackRequest(items = emptyList()))
        queue.open(PlaybackRequest(items = emptyList(), syncTest = true))
        assertEquals(Unit, queue.navigation.first())
        assertNull(withTimeoutOrNull(100) { queue.navigation.first() })
        assertTrue(queue.consume()!!.syncTest)
    }
}

class LogFrequencyTest {

    @Test
    fun axisSpansTheFullBandRange() {
        assertEquals(0f, LogFrequency.toPosition(BandId.MIN_HZ), 1e-6f)
        assertEquals(1f, LogFrequency.toPosition(BandId.MAX_HZ), 1e-6f)
        assertEquals(0f, LogFrequency.toPosition(1f), 1e-6f)
        assertEquals(1f, LogFrequency.toPosition(96_000f), 1e-6f)
    }

    @Test
    fun positionsAreMonotonicAndRoundTrip() {
        var last = -1f
        for (band in BandId.entries) {
            for (hz in listOf(band.defaultLowHz, band.defaultHighHz)) {
                val p = LogFrequency.toPosition(hz)
                assertTrue(p >= last)
                last = p
                val back = LogFrequency.toHz(p)
                assertTrue("$hz -> $back", abs(back - hz) / hz < 0.03f)
            }
        }
    }

    @Test
    fun roundingFollowsTheScale() {
        assertEquals(37f, LogFrequency.round(37.4f), 0f)
        assertEquals(150f, LogFrequency.round(152f), 0f)
        assertEquals(720f, LogFrequency.round(723f), 0f)
        assertEquals(2000f, LogFrequency.round(2023f), 0f)
        assertEquals(12_300f, LogFrequency.round(12_345f), 0f)
        assertEquals(BandId.MIN_HZ, LogFrequency.round(3f), 0f)
    }
}

class FormattingTest {

    @Test
    fun timesUseMinutesAndHours() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:00", formatTime(-500))
        assertEquals("1:01", formatTime(61_000))
        assertEquals("59:59", formatTime(3_599_999))
        assertEquals("1:00:00", formatTime(3_600_000))
    }

    @Test
    fun frequenciesSwitchToKilohertz() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("60 Hz", formatHz(60f))
            assertEquals("400 Hz", formatHz(400f))
            assertEquals("2.0 kHz", formatHz(2000f))
            assertEquals("12.0 kHz", formatHz(12_000f))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
