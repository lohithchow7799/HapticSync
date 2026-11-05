package com.hapticsync.platform.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hapticsync.dsp.PcmFormat
import com.hapticsync.dsp.synth.WavWriter
import com.hapticsync.engine.MediaAudioInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a generated WAV file through the real Media3 pipeline and checks that the decoded PCM,
 * its timestamps and the output position reach the haptic engine's input.
 */
@RunWith(AndroidJUnit4::class)
class PcmTapInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())
    private lateinit var file: File
    private lateinit var input: RecordingInput
    private var player: HapticPlayer? = null

    @Before
    fun setUp() {
        file = File(context.cacheDir, "pcm_tap_test.wav")
        val rate = SAMPLE_RATE
        val seconds = 4
        val samples = FloatArray(rate * seconds * 2)
        for (i in 0 until rate * seconds) {
            val v = 0.25f * sin(2.0 * PI * 220.0 * i / rate).toFloat()
            samples[2 * i] = v
            samples[2 * i + 1] = v
        }
        file.outputStream().buffered().use { WavWriter.write16(it, samples, rate, 2) }
        input = RecordingInput()
    }

    @After
    fun tearDown() {
        onMain { player?.release() }
        file.delete()
    }

    @Test
    fun decodedPcmReachesTheEngineWithTimestamps() {
        onMain {
            player = HapticPlayer(context, input).also {
                it.setVolume(0f)
                it.setItems(listOf(PlayableItem(Uri.fromFile(file), "Test tone", "audio/wav")))
            }
        }
        assertTrue("playback did not start", waitUntil(15_000) { input.playing && input.bytes.get() >= SAMPLE_RATE * 4L })

        val format = input.format
        assertNotNull("no analysable format announced: ${input.unsupported}", format)
        assertEquals(SAMPLE_RATE, format!!.sampleRate)
        assertEquals(2, format.channelCount)
        assertEquals("presentation timestamps went backwards", 0, input.nonMonotonic.get())
        assertTrue("no output position reported", waitUntil(5_000) { input.positions.get() > 0 && input.lastPositionUs.get() > 0 })

        onMain { player?.pause() }
        assertTrue("pause was not reported", waitUntil(5_000) { !input.playing })
    }

    @Test
    fun seekingReportsADiscontinuity() {
        onMain {
            player = HapticPlayer(context, input).also {
                it.setVolume(0f)
                it.setItems(listOf(PlayableItem(Uri.fromFile(file), "Test tone", "audio/wav")))
            }
        }
        assertTrue(waitUntil(15_000) { input.playing && input.bytes.get() > 0 })
        val before = input.discontinuities.get()
        onMain { player?.seekTo(2_500L) }
        assertTrue("seek did not flush the tap", waitUntil(5_000) { input.discontinuities.get() > before })
        // Timestamps are in renderer time (media time plus a fixed offset): compare to the start.
        val start = input.firstPts.get()
        assertTrue("audio did not resume at the seek target", waitUntil(10_000) { input.lastPts.get() - start >= 2_000_000L })
    }

    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        main.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            SystemClock.sleep(20)
        }
        return condition()
    }

    private class RecordingInput : MediaAudioInput {
        @Volatile var format: PcmFormat? = null
        @Volatile var unsupported: String? = null
        @Volatile var playing = false
        val bytes = AtomicLong()
        val lastPts = AtomicLong(Long.MIN_VALUE)
        val firstPts = AtomicLong(Long.MIN_VALUE)
        val nonMonotonic = AtomicInteger()
        val discontinuities = AtomicInteger()
        val positions = AtomicInteger()
        val lastPositionUs = AtomicLong(-1L)

        override fun onFormat(format: PcmFormat?, unsupportedReason: String?) {
            this.format = format
            unsupported = unsupportedReason
        }

        override fun onPcm(buffer: ByteBuffer, startByte: Int, endByte: Int, presentationTimeUs: Long) {
            bytes.addAndGet((endByte - startByte).toLong())
            firstPts.compareAndSet(Long.MIN_VALUE, presentationTimeUs)
            if (presentationTimeUs < lastPts.get()) nonMonotonic.incrementAndGet()
            lastPts.set(presentationTimeUs)
        }

        override fun onDiscontinuity() {
            discontinuities.incrementAndGet()
            lastPts.set(Long.MIN_VALUE)
        }

        override fun onPlaying(playing: Boolean) {
            this.playing = playing
        }

        override fun onPosition(positionUs: Long, systemNanoTime: Long) {
            positions.incrementAndGet()
            lastPositionUs.set(positionUs)
        }

        override fun onSpeed(speed: Float) = Unit

        override fun onReset() {
            lastPts.set(Long.MIN_VALUE)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
