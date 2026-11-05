package com.hapticsync.engine

import com.hapticsync.dsp.PcmConverter
import com.hapticsync.dsp.PcmFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-producer / single-consumer hand-off of decoded PCM from the player's playback thread
 * to the analysis thread.
 *
 * The producer converts PCM bytes straight into two pre-allocated float rings (mono "mid" and
 * stereo "side") and records an *anchor* — (frame position, presentation timestamp, epoch,
 * format) — for every chunk. The consumer uses the anchors to recover the exact media time of
 * every frame it reads, to skip audio from before a seek (older epoch) and to notice format
 * changes. Nothing blocks: if the analysis falls behind, new chunks are dropped (and counted)
 * rather than stalling playback.
 */
class PcmIngest(private val capacityFrames: Int = DEFAULT_CAPACITY_FRAMES) {

    private val mid = FloatArray(capacityFrames)
    private val side = FloatArray(capacityFrames)
    private val converter = PcmConverter()
    private val scratchMid = FloatArray(MAX_CHUNK_FRAMES)
    private val scratchSide = FloatArray(MAX_CHUNK_FRAMES)

    // Positions in frames since creation; written by one side, read by both.
    private val writePos = AtomicLong(0)
    private val readPos = AtomicLong(0)

    private val anchorFrame = LongArray(ANCHORS)
    private val anchorPts = LongArray(ANCHORS)
    private val anchorEpoch = IntArray(ANCHORS)
    private val anchorFormat = IntArray(ANCHORS)
    private val anchorWrite = AtomicLong(0)
    @Volatile private var anchorRead = 0L // written by the consumer, read by the producer

    private val formats = arrayOfNulls<PcmFormat>(FORMAT_SLOTS)
    @Volatile private var formatId = 0
    private var producerFormat: PcmFormat? = null

    /** Chunks dropped because the consumer fell behind. */
    @Volatile var droppedChunks = 0L
        private set

    /** Frames currently buffered and not yet consumed. */
    val backlogFrames: Long get() = writePos.get() - readPos.get()

    /** Fraction of the ring in use, 0..1. */
    val fillRatio: Float get() = backlogFrames.toFloat() / capacityFrames

    // --------------------------------------------------------------------------------------
    // Producer side (playback thread)

    fun setFormat(format: PcmFormat?) {
        producerFormat = format
        if (format != null) converter.configure(format)
        val id = formatId + 1
        formats[id % FORMAT_SLOTS] = format
        formatId = id
    }

    /**
     * Converts and stores `[startByte, endByte)` of [buffer] (absolute indices, interleaved PCM in
     * the current format) starting at media time [ptsUs]. Returns false when the data was dropped.
     */
    fun write(buffer: ByteBuffer, startByte: Int, endByte: Int, ptsUs: Long, epoch: Int): Boolean {
        val format = producerFormat ?: return false
        val bytesPerFrame = format.bytesPerFrame
        var frames = (endByte - startByte) / bytesPerFrame
        if (frames <= 0) return true
        val w = writePos.get()
        val free = capacityFrames - (w - readPos.get())
        if (frames > free || anchorWrite.get() - anchorRead >= ANCHORS - 1) {
            droppedChunks++
            return false
        }
        var byte = startByte
        var pos = w
        var remaining = frames
        var chunkPts = ptsUs
        var first = true
        while (remaining > 0) {
            val n = minOf(remaining, MAX_CHUNK_FRAMES)
            converter.convert(buffer, byte, n, scratchMid, scratchSide, 0)
            copyIntoRing(pos, n)
            if (first) {
                addAnchor(pos, chunkPts, epoch)
                first = false
            }
            pos += n
            byte += n * bytesPerFrame
            remaining -= n
            chunkPts += format.framesToUs(n.toLong())
        }
        writePos.set(pos)
        return true
    }

    /** Test/demo helper: writes interleaved float samples in the current format. */
    fun writeFloats(interleaved: FloatArray, startFrame: Int, frames: Int, ptsUs: Long, epoch: Int): Boolean {
        val format = producerFormat ?: return false
        if (frames <= 0) return true
        val w = writePos.get()
        if (frames > capacityFrames - (w - readPos.get()) || anchorWrite.get() - anchorRead >= ANCHORS - 1) {
            droppedChunks++
            return false
        }
        var pos = w
        var remaining = frames
        var frame = startFrame
        var first = true
        while (remaining > 0) {
            val n = minOf(remaining, MAX_CHUNK_FRAMES)
            converter.convertFloats(interleaved, frame, n, scratchMid, scratchSide, 0)
            copyIntoRing(pos, n)
            if (first) { addAnchor(pos, ptsUs, epoch); first = false }
            pos += n
            frame += n
            remaining -= n
        }
        writePos.set(pos)
        return format.channelCount > 0
    }

    private fun copyIntoRing(pos: Long, n: Int) {
        var index = (pos % capacityFrames).toInt()
        for (i in 0 until n) {
            mid[index] = scratchMid[i]
            side[index] = scratchSide[i]
            index++
            if (index == capacityFrames) index = 0
        }
    }

    private fun addAnchor(framePos: Long, ptsUs: Long, epoch: Int) {
        val a = anchorWrite.get()
        val slot = (a % ANCHORS).toInt()
        anchorFrame[slot] = framePos
        anchorPts[slot] = ptsUs
        anchorEpoch[slot] = epoch
        anchorFormat[slot] = formatId
        anchorWrite.set(a + 1)
    }

    // --------------------------------------------------------------------------------------
    // Consumer side (analysis thread)

    /** Description of the frames at the current read position. */
    class Position {
        var ptsUs = 0L
        var epoch = 0
        var format: PcmFormat? = null
        var formatId = 0
        /** Frames until the next anchor that starts a different epoch/format or a PTS jump. */
        var framesUntilBoundary = Long.MAX_VALUE
    }

    /** Frames available to read. */
    fun available(): Long = writePos.get() - readPos.get()

    /**
     * Describes the frame at the read position. Returns false when no data is available.
     */
    fun peekPosition(out: Position): Boolean {
        val r = readPos.get()
        if (writePos.get() <= r) return false
        val written = anchorWrite.get()
        // Advance to the last anchor at or before the read position.
        while (anchorRead + 1 < written && anchorFrame[((anchorRead + 1) % ANCHORS).toInt()] <= r) anchorRead++
        val slot = (anchorRead % ANCHORS).toInt()
        val format = formats[anchorFormat[slot] % FORMAT_SLOTS]
        out.format = format
        out.formatId = anchorFormat[slot]
        out.epoch = anchorEpoch[slot]
        out.ptsUs = anchorPts[slot] + if (format != null) format.framesToUs(r - anchorFrame[slot]) else 0L
        out.framesUntilBoundary = Long.MAX_VALUE
        // Look ahead for the next anchor that breaks continuity.
        var a = anchorRead + 1
        var expectedFrame = anchorFrame[slot]
        var expectedPts = anchorPts[slot]
        var lastFormat = anchorFormat[slot]
        var lastEpoch = anchorEpoch[slot]
        while (a < written) {
            val s = (a % ANCHORS).toInt()
            val f = anchorFrame[s]
            val predictedPts = expectedPts + (format?.framesToUs(f - expectedFrame) ?: 0L)
            val jump = kotlin.math.abs(anchorPts[s] - predictedPts) > PTS_JUMP_US
            if (anchorEpoch[s] != lastEpoch || anchorFormat[s] != lastFormat || jump) {
                out.framesUntilBoundary = f - r
                break
            }
            expectedFrame = f
            expectedPts = anchorPts[s]
            lastFormat = anchorFormat[s]
            lastEpoch = anchorEpoch[s]
            a++
            if (f - r > LOOKAHEAD_LIMIT_FRAMES) break
        }
        return true
    }

    /** Copies [frames] frames at the read position into the arrays and advances the read position. */
    fun read(outMid: FloatArray, outSide: FloatArray, offset: Int, frames: Int) {
        var index = (readPos.get() % capacityFrames).toInt()
        for (i in 0 until frames) {
            outMid[offset + i] = mid[index]
            outSide[offset + i] = side[index]
            index++
            if (index == capacityFrames) index = 0
        }
        readPos.addAndGet(frames.toLong())
    }

    /** Discards [frames] frames at the read position. */
    fun skip(frames: Long) {
        readPos.addAndGet(minOf(frames, available()))
    }

    /** Discards everything buffered (consumer side). */
    fun skipAll() {
        readPos.set(writePos.get())
    }

    /** Media time of the newest buffered frame (end of written data), or MIN when empty. */
    fun frontierPtsUs(): Long {
        val written = anchorWrite.get()
        if (written == 0L) return Long.MIN_VALUE / 4
        val slot = ((written - 1) % ANCHORS).toInt()
        val format = formats[anchorFormat[slot] % FORMAT_SLOTS] ?: return anchorPts[slot]
        return anchorPts[slot] + format.framesToUs(writePos.get() - anchorFrame[slot])
    }

    companion object {
        /** ~2.7 s at 48 kHz, ~1.4 s at 96 kHz. */
        const val DEFAULT_CAPACITY_FRAMES = 131_072
        private const val MAX_CHUNK_FRAMES = 4096
        private const val ANCHORS = 4096
        private const val FORMAT_SLOTS = 16
        private const val PTS_JUMP_US = 50_000L
        private const val LOOKAHEAD_LIMIT_FRAMES = 1_000_000L
    }
}
