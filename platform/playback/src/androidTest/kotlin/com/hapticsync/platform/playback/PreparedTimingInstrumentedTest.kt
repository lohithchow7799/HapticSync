package com.hapticsync.platform.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hapticsync.dsp.PcmEncoding
import com.hapticsync.dsp.PcmFormat
import com.hapticsync.dsp.synth.WavWriter
import com.hapticsync.engine.EngineThreadFactory
import com.hapticsync.engine.HapticTrack
import com.hapticsync.engine.MediaAudioInput
import com.hapticsync.model.BuiltInPresets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * The prepared-haptics timeline and seeking, end to end on a device:
 * - the offline decoder and the playing sink agree on when each sound happens (prepared haptics
 *   are placed on the decoder's timeline and played on the sink's);
 * - fragmented MP4 without an index can be seeked (the defaults restart such files at 0).
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PreparedTimingInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())
    private val files = ArrayList<File>()
    private var player: HapticPlayer? = null
    private var plain: ExoPlayer? = null

    @After
    fun tearDown() {
        onMain {
            player?.release()
            plain?.release()
        }
        files.forEach { it.delete() }
    }

    @Test
    fun decoderAndSinkAgreeOnTheAacTimeline() {
        val encoded = AacTestMedia.encode(AacTestMedia.clicks(RATE, 3.0, listOf(1.0, 2.0)), RATE)
        // An encoder delay in the container (trimmed by the sink) and a platform-muxed file.
        val delayed = newFile("clicks_delay.m4a").also { AacTestMedia.writeMp4WithDelay(it, encoded, ENCODER_DELAY) }
        val muxed = newFile("clicks_muxer.m4a").also { AacTestMedia.writeMuxerMp4(it, encoded) }
        for (file in listOf(delayed, muxed)) {
            val decodedClickUs = firstLoudSampleUs(decodeAll(file).second, 0L)
            assertTrue("${file.name}: decoded click is not near 1 s: ${decodedClickUs}us", abs(decodedClickUs - 1_000_000L) < 100_000L)
            val playedMediaUs = playedClickMediaUs(file, seekToMs = null, afterMediaUs = Long.MIN_VALUE)
            assertTrue(
                "${file.name}: prepared and played timelines differ: decoded ${decodedClickUs}us, played ${playedMediaUs}us",
                abs(playedMediaUs - decodedClickUs) <= 3_000L,
            )
        }
    }

    @Test
    fun decoderAndSinkAgreeAfterASeek() {
        // After a seek the sink no longer trims the encoder delay: the mapping must follow.
        val encoded = AacTestMedia.encode(AacTestMedia.clicks(RATE, 4.0, listOf(1.0, 3.0)), RATE)
        val file = newFile("clicks_seek.m4a").also { AacTestMedia.writeMp4WithDelay(it, encoded, ENCODER_DELAY) }
        val decodedClickUs = firstLoudSampleUs(decodeAll(file).second, 2_000_000L)
        assertTrue("second click is not near 3 s: ${decodedClickUs}us", abs(decodedClickUs - 3_000_000L) < 100_000L)
        val playedMediaUs = playedClickMediaUs(file, seekToMs = 2_000L, afterMediaUs = 1_900_000L)
        assertTrue(
            "timelines differ after a seek: decoded ${decodedClickUs}us, played ${playedMediaUs}us",
            abs(playedMediaUs - decodedClickUs) <= 3_000L,
        )
    }

    @Test
    fun wavDecodesFromZeroWithoutGaps() {
        val samples = FloatArray(RATE * 2 * 2) { if (it % 200 < 100) 0.2f else -0.2f }
        val file = newFile("tone.wav")
        file.outputStream().buffered().use { WavWriter.write16(it, samples, RATE, 2) }
        val (format, chunks) = decodeAll(file)
        assertEquals(RATE, format.sampleRate)
        assertEquals(0L, chunks.first().timeUs)
        var expectedUs = 0L
        var frames = 0L
        for (c in chunks) {
            assertTrue("gap or overlap at ${c.timeUs}us (expected ${expectedUs}us)", abs(c.timeUs - expectedUs) <= 50L)
            frames += c.frames
            expectedUs = frames * 1_000_000L / RATE
        }
        assertEquals(RATE * 2L, frames)
    }

    @Test
    fun preparerStartsAnywhereAndFillsGapsAfterASeek() {
        val encoded = AacTestMedia.encode(AacTestMedia.clicks(RATE, 6.0, listOf(1.0, 2.5, 4.0, 5.0)), RATE)
        val file = newFile("prepare.m4a").also { AacTestMedia.writeMp4WithDelay(it, encoded, ENCODER_DELAY) }
        val preparer = HapticTrackPreparer(
            MediaAudioDecoder(context),
            Uri.fromFile(file),
            HapticTrack(),
            BuiltInPresets.CINEMATIC.profile,
            EngineThreadFactory { name, _, body -> Thread(body, name).apply { isDaemon = true } },
        )
        try {
            // Opening at a resume position: preparation starts there, not at 0.
            preparer.playbackPositionUs = 3_000_000L
            preparer.start(3_000_000L)
            assertTrue("not prepared from the start position", waitUntil(15_000) { preparer.track.isReady(3_000_000L, 5_000_000L) })
            assertFalse("prepared audio before the start position", preparer.track.isReady(0L, 1_000_000L))

            // Seek back to the beginning: the gap is prepared and joins the existing range.
            preparer.playbackPositionUs = 0L
            preparer.ensure(0L)
            assertTrue(
                "the whole item was not prepared after seeking back (${preparer.status.value})",
                waitUntil(15_000) { preparer.status.value == HapticTrackPreparer.Status.Complete },
            )
            assertTrue(preparer.track.isReady(0L, preparer.track.durationUs))
        } finally {
            preparer.close()
        }
    }

    @Test
    fun fragmentedMp4WithoutIndexCanBeSeeked() {
        val encoded = AacTestMedia.encode(AacTestMedia.clicks(RATE, 6.0, listOf(1.0, 4.5)), RATE)
        val file = newFile("fragmented.mp4").also { AacTestMedia.writeFragmentedMp4(it, encoded) }

        // Control: the default extractors cannot seek this file (seeks restart at 0).
        val defaultSeekable = AtomicLong(-1L)
        onMain {
            plain = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context, DefaultExtractorsFactory()))
                .build().also { p ->
                    p.volume = 0f
                    p.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) defaultSeekable.set(if (p.isCurrentMediaItemSeekable) 1L else 0L)
                        }
                    })
                    p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    p.prepare()
                }
        }
        assertTrue("default player did not get ready", waitUntil(15_000) { defaultSeekable.get() >= 0 })
        assertEquals("the test file should be unseekable for the default extractors", 0L, defaultSeekable.get())

        val input = ClickInput()
        onMain {
            player = HapticPlayer(context, input).also {
                it.setVolume(0f)
                it.setItems(listOf(PlayableItem(Uri.fromFile(file), "Fragmented", null)))
            }
        }
        assertTrue("playback did not start", waitUntil(15_000) { input.playing })
        val seekable = AtomicLong(-1L)
        onMain { seekable.set(if (player!!.exoPlayer.isCurrentMediaItemSeekable) 1L else 0L) }
        assertEquals("indexed fragmented MP4 should be seekable", 1L, seekable.get())

        input.expectAfterSeek(fromMediaUs = 3_900_000L)
        onMain { player?.seekTo(4_000L) }
        assertTrue("the click after the seek target was not played", waitUntil(15_000) { input.clickSinkUs.get() != Long.MIN_VALUE })
        assertTrue(
            "playback restarted instead of seeking (audio resumed at ${input.firstMediaUsAfterSeek.get()}us)",
            input.firstMediaUsAfterSeek.get() in 3_900_000L..4_100_000L,
        )
        val playedMediaUs = input.clickSinkUs.get() - input.offsetAtClick.get()
        val decodedClickUs = firstLoudSampleUs(decodeAll(file).second, 4_000_000L)
        assertTrue("second click is not near 4.5 s: ${decodedClickUs}us", abs(decodedClickUs - 4_500_000L) < 100_000L)
        assertTrue("seek landed elsewhere: click decoded at ${decodedClickUs}us, heard at ${playedMediaUs}us", abs(playedMediaUs - decodedClickUs) <= 3_000L)

        // The offline decoder seeks in the same file (preparing haptics after a seek).
        val fromSeek = decodeAll(file, startUs = 4_000_000L).second
        assertFalse(fromSeek.isEmpty())
        assertTrue("decoder did not seek (first chunk at ${fromSeek.first().timeUs}us)", fromSeek.first().timeUs in 3_500_000L..4_000_000L)
        assertEquals("click after the decoder seek is misplaced", decodedClickUs.toDouble(), firstLoudSampleUs(fromSeek, 4_000_000L).toDouble(), 100.0)
    }

    // --- helpers ---

    /**
     * Plays [file] (optionally seeking to [seekToMs] once playing) and returns the media time of
     * the first click heard at or after [afterMediaUs]: its sink time minus the reported offset.
     */
    private fun playedClickMediaUs(file: File, seekToMs: Long?, afterMediaUs: Long): Long {
        val input = ClickInput()
        onMain {
            player = HapticPlayer(context, input).also {
                it.setVolume(0f)
                it.setItems(listOf(PlayableItem(Uri.fromFile(file), file.name, "audio/mp4")))
            }
        }
        assertTrue("${file.name}: playback did not start", waitUntil(15_000) { input.playing })
        if (seekToMs != null) {
            input.expectAfterSeek(afterMediaUs)
            onMain { player?.seekTo(seekToMs) }
        }
        assertTrue("${file.name}: the click was not played", waitUntil(15_000) { input.clickSinkUs.get() != Long.MIN_VALUE })
        assertTrue("${file.name}: no media time offset reported", input.offsetReported)
        onMain {
            player?.release()
            player = null
        }
        return input.clickSinkUs.get() - input.offsetAtClick.get()
    }

    private class Chunk(val timeUs: Long, val samples: FloatArray, val channels: Int) {
        val frames: Int get() = samples.size / channels
    }

    private fun decodeAll(file: File, startUs: Long = 0L): Pair<PcmFormat, List<Chunk>> {
        var format: PcmFormat? = null
        val chunks = ArrayList<Chunk>()
        MediaAudioDecoder(context).decode(
            Uri.fromFile(file),
            startUs,
            shouldStop = { false },
            onFormat = { format = it },
            onPcm = { buffer, start, end, timeUs ->
                chunks += Chunk(timeUs, readSamples(buffer, start, end, format!!), format!!.channelCount)
                true
            },
        )
        return format!! to chunks
    }

    /** Media time of the first sample louder than [THRESHOLD] at or after [fromUs]. */
    private fun firstLoudSampleUs(chunks: List<Chunk>, fromUs: Long): Long {
        for (c in chunks) {
            for (f in 0 until c.frames) {
                val t = c.timeUs + f * 1_000_000L / RATE
                if (t >= fromUs && abs(c.samples[f * c.channels]) > THRESHOLD) return t
            }
        }
        return -1L
    }

    private fun newFile(name: String) = File(context.cacheDir, name).also { files += it }

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
            SystemClock.sleep(10)
        }
        return condition()
    }

    /** Records the sink time of the first loud sample (a click) the tap sees. */
    private class ClickInput : MediaAudioInput {
        @Volatile var format: PcmFormat? = null
        @Volatile var playing = false
        @Volatile var offsetReported = false
        @Volatile private var offsetUs = 0L
        @Volatile private var armed = true
        @Volatile private var armOnDiscontinuity = false
        @Volatile private var minMediaUs = Long.MIN_VALUE
        val clickSinkUs = AtomicLong(Long.MIN_VALUE)
        val offsetAtClick = AtomicLong()
        val firstMediaUsAfterSeek = AtomicLong(Long.MIN_VALUE)

        /** Ignores audio until the sink is flushed by a seek, then looks for a click from [fromMediaUs]. */
        fun expectAfterSeek(fromMediaUs: Long) {
            armed = false
            minMediaUs = fromMediaUs
            clickSinkUs.set(Long.MIN_VALUE)
            firstMediaUsAfterSeek.set(Long.MIN_VALUE)
            armOnDiscontinuity = true
        }

        override fun onFormat(format: PcmFormat?, unsupportedReason: String?) {
            this.format = format
        }

        override fun onPcm(buffer: ByteBuffer, startByte: Int, endByte: Int, presentationTimeUs: Long) {
            val f = format ?: return
            if (!armed || clickSinkUs.get() != Long.MIN_VALUE) return
            firstMediaUsAfterSeek.compareAndSet(Long.MIN_VALUE, presentationTimeUs - offsetUs)
            val samples = readSamples(buffer, startByte, endByte, f)
            for (i in 0 until samples.size / f.channelCount) {
                val sinkUs = presentationTimeUs + i * 1_000_000L / f.sampleRate
                if (sinkUs - offsetUs < minMediaUs) continue
                if (abs(samples[i * f.channelCount]) > THRESHOLD) {
                    offsetAtClick.set(offsetUs)
                    clickSinkUs.set(sinkUs)
                    return
                }
            }
        }

        override fun onMediaTimeOffset(sinkMinusMediaUs: Long) {
            offsetUs = sinkMinusMediaUs
            offsetReported = true
        }

        override fun onDiscontinuity() {
            if (armOnDiscontinuity) {
                armOnDiscontinuity = false
                armed = true
            }
        }

        override fun onPlaying(playing: Boolean) {
            this.playing = playing
        }

        override fun onPosition(positionUs: Long, systemNanoTime: Long) = Unit
        override fun onSpeed(speed: Float) = Unit
        override fun onReset() = Unit
    }

    private companion object {
        const val RATE = 44_100
        const val THRESHOLD = 0.3f

        /** Encoder delay declared by the test files (the common iTunes value). */
        const val ENCODER_DELAY = 2112

        fun readSamples(buffer: ByteBuffer, start: Int, end: Int, format: PcmFormat): FloatArray {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            return when (format.encoding) {
                PcmEncoding.PCM_16BIT -> FloatArray((end - start) / 2) { b.getShort(start + 2 * it) / 32768f }
                PcmEncoding.PCM_FLOAT -> FloatArray((end - start) / 4) { b.getFloat(start + 4 * it) }
                else -> error("unexpected encoding ${format.encoding}")
            }
        }
    }
}
