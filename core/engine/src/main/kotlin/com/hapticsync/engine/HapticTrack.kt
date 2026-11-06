package com.hapticsync.engine

import com.hapticsync.analysis.AnalysisFrame
import com.hapticsync.analysis.SoundEvent
import com.hapticsync.haptics.TextureTemplate
import com.hapticsync.model.BandId
import kotlin.math.roundToInt

/**
 * The haptic-relevant analysis of one media item, computed ahead of playback by a
 * [HapticTrackBuilder] ("preparing haptics"). Times are media times: microseconds on the item's
 * own timeline, exactly as the player reports its position.
 *
 * Per analysis hop the track keeps what the haptic mapper needs (band levels and shares, the
 * continuous texture levels, engine speed, speech likelihood) plus the hop's level, quantised to
 * one byte each (27 bytes per 10 ms hop, about 10 MB per hour). It also keeps the detected
 * sound events after non-causal salience filtering. Because only analysis results are stored,
 * every output setting (intensities, isolation, envelope, calibration…) still applies at once
 * during playback.
 *
 * The analysed range is a set of disjoint [Segment]s, so a seek far ahead can start a new
 * segment without analysing everything in between. One builder thread writes, any thread
 * reads; every method is thread-safe.
 */
class HapticTrack {

    /** One contiguous analysed range. */
    class Segment internal constructor(
        /** Media time of the first stored hop. */
        val startUs: Long,
        /** Hop length in frames at [sampleRate] (hop times are computed exactly, without drift). */
        val hopFrames: Int,
        val sampleRate: Int,
        /** Start of the reliable range: analysis after a mid-stream start needs a short warm-up. */
        val readyStartUs: Long,
    ) {
        internal val chunks = ArrayList<ByteArray>()
        internal var hops = 0
        internal val events = ArrayList<SoundEvent>()

        /** End (exclusive) of the range whose frames and events are final. */
        @Volatile var readyEndUs: Long = readyStartUs
            internal set

        /** End of the analysed audio, final or not. */
        val analysedEndUs: Long get() = hopTimeUs(hops)

        fun hopTimeUs(index: Int): Long = startUs + index.toLong() * hopFrames * 1_000_000L / sampleRate

        /** Index of the hop containing [timeUs] (consistent with [hopTimeUs]'s rounding). */
        internal fun hopIndexAt(timeUs: Long): Int {
            var index = (((timeUs - startUs) * sampleRate) / (hopFrames.toLong() * 1_000_000L)).toInt()
            while (hopTimeUs(index + 1) <= timeUs) index++
            while (index > 0 && hopTimeUs(index) > timeUs) index--
            return index
        }

        internal fun offsetOf(index: Int): Pair<ByteArray, Int> =
            chunks[index / HOPS_PER_CHUNK] to (index % HOPS_PER_CHUNK) * STRIDE
    }

    private val lock = Any()
    private val segments = ArrayList<Segment>()

    /** Set when analysis has reached the end of the item. */
    @Volatile var complete: Boolean = false
        internal set

    /** Duration of the item, when known (for progress). */
    @Volatile var durationUs: Long = 0L

    val hopUs: Long get() = synchronized(lock) { segments.firstOrNull()?.let { it.hopFrames * 1_000_000L / it.sampleRate } ?: 10_000L }

    /** Starts a new segment at [startUs] (media time of its first hop). */
    internal fun startSegment(startUs: Long, hopFrames: Int, sampleRate: Int, warmUpUs: Long): Segment = synchronized(lock) {
        val segment = Segment(startUs, hopFrames, sampleRate, startUs + warmUpUs)
        segments += segment
        segments.sortBy { it.startUs }
        segment
    }

    internal fun appendFrame(segment: Segment, frame: AnalysisFrame) = synchronized(lock) {
        val index = segment.hops
        if (index % HOPS_PER_CHUNK == 0) segment.chunks += ByteArray(HOPS_PER_CHUNK * STRIDE)
        val (chunk, base) = segment.offsetOf(index)
        var o = base
        chunk[o++] = quantiseDb(frame.rmsDb)
        chunk[o++] = quantise(frame.rpmNormalized)
        chunk[o++] = quantise(frame.speechLikelihood)
        for (b in 0 until BandId.COUNT) chunk[o++] = quantise(frame.bandLevel[b])
        for (b in 0 until BandId.COUNT) chunk[o++] = quantise(frame.bandShare[b])
        for (category in TEXTURES) chunk[o++] = quantise(frame.continuous[category.ordinal])
        segment.hops = index + 1
    }

    /** Publishes final events and extends the ready range of [segment] to [readyEndUs]. */
    internal fun publish(segment: Segment, finalEvents: List<SoundEvent>, readyEndUs: Long) = synchronized(lock) {
        val batch = finalEvents.sortedBy { it.timeUs }
        val inOrder = segment.events.isEmpty() || batch.isEmpty() || batch.first().timeUs >= segment.events.last().timeUs
        segment.events.addAll(batch)
        if (!inOrder) segment.events.sortBy { it.timeUs }
        segment.readyEndUs = maxOf(segment.readyEndUs, minOf(readyEndUs, segment.analysedEndUs))
    }

    /** Level (dBFS) of [segment]'s hop [index] (builder use). */
    internal fun levelDb(segment: Segment, index: Int): Float = synchronized(lock) {
        val (chunk, base) = segment.offsetOf(index)
        dequantiseDb(chunk[base])
    }

    /** The segment whose ready range contains [timeUs], if any. */
    private fun readySegmentAt(timeUs: Long): Segment? =
        segments.lastOrNull { timeUs >= it.readyStartUs && timeUs < it.readyEndUs }

    /**
     * End of the ready range that contains [timeUs], following adjacent segments, or null when
     * [timeUs] is not ready.
     */
    fun readyEndFrom(timeUs: Long): Long? = synchronized(lock) {
        var segment = readySegmentAt(timeUs) ?: return null
        var end = segment.readyEndUs
        while (true) {
            val next = segments.firstOrNull { it !== segment && it.readyStartUs <= end && it.readyEndUs > end } ?: break
            end = next.readyEndUs
            segment = next
        }
        end
    }

    /** Whether everything in [fromUs, toUs) is ready (the end of a complete item counts). */
    fun isReady(fromUs: Long, toUs: Long): Boolean {
        val end = readyEndFrom(fromUs) ?: return false
        // The last partial hop of a complete item is never analysed.
        return end >= toUs || (complete && end >= durationUs - END_TOLERANCE_US)
    }

    /** Total ready media time, for progress display. */
    val readyDurationUs: Long get() = synchronized(lock) { segments.sumOf { (it.readyEndUs - it.readyStartUs).coerceAtLeast(0L) } }

    /** Number of hops stored in all segments. */
    val storedHops: Int get() = synchronized(lock) { segments.sumOf { it.hops } }

    /**
     * Decodes the hop at or before [timeUs] into [out] (only the fields the mapper uses).
     * Returns false when [timeUs] is not ready.
     */
    fun readFrame(timeUs: Long, out: AnalysisFrame): Boolean = synchronized(lock) {
        val segment = readySegmentAt(timeUs) ?: return false
        val index = segment.hopIndexAt(timeUs).coerceIn(0, segment.hops - 1)
        val (chunk, base) = segment.offsetOf(index)
        var o = base
        out.timeUs = segment.hopTimeUs(index)
        out.durationUs = segment.hopTimeUs(index + 1) - out.timeUs
        out.rmsDb = dequantiseDb(chunk[o++])
        out.rpmNormalized = dequantise(chunk[o++])
        out.speechLikelihood = dequantise(chunk[o++])
        for (b in 0 until BandId.COUNT) out.bandLevel[b] = dequantise(chunk[o++])
        for (b in 0 until BandId.COUNT) out.bandShare[b] = dequantise(chunk[o++])
        out.continuous.fill(0f)
        for (category in TEXTURES) out.continuous[category.ordinal] = dequantise(chunk[o++])
        true
    }

    /** Appends the ready events with time in [fromUs, toUs) to [out], in time order. */
    fun events(fromUs: Long, toUs: Long, out: MutableList<SoundEvent>) = synchronized(lock) {
        val start = out.size
        for (segment in segments) {
            if (segment.readyEndUs <= fromUs || segment.readyStartUs >= toUs) continue
            val list = segment.events
            // First event at or after fromUs (binary search; events are sorted by time).
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid].timeUs < fromUs) lo = mid + 1 else hi = mid
            }
            for (i in lo until list.size) {
                val e = list[i]
                if (e.timeUs >= toUs) break
                if (e.timeUs >= segment.readyStartUs && e.timeUs < segment.readyEndUs) out += e
            }
        }
        if (out.size - start > 1) out.subList(start, out.size).sortBy { it.timeUs }
    }

    /** Number of ready events (diagnostics). */
    val eventCount: Int get() = synchronized(lock) { segments.sumOf { it.events.size } }

    /** Whether another segment's ready range contains [timeUs] (the builder stops there). */
    internal fun readyElsewhere(other: Segment, timeUs: Long): Boolean = synchronized(lock) {
        segments.any { it !== other && timeUs >= it.readyStartUs && timeUs < it.readyEndUs }
    }

    companion object {
        /** Continuous categories stored per hop (those the texture layer can render). */
        val TEXTURES = TextureTemplate.CONTINUOUS_CATEGORIES

        /** Bytes per hop: level, rpm, speech, 6 band levels, 6 band shares, texture levels. */
        val STRIDE = 3 + 2 * BandId.COUNT + TEXTURES.size

        /** Unanalysed tail of a complete item that still counts as ready (one long hop). */
        private const val END_TOLERANCE_US = 25_000L

        /** Hops per storage chunk (one minute at 10 ms hops). */
        const val HOPS_PER_CHUNK = 6000

        private fun quantise(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        private fun dequantise(b: Byte): Float = (b.toInt() and 0xFF) / 255f

        /** dBFS in [-127.5, 0] at 0.5 dB resolution. */
        private fun quantiseDb(db: Float): Byte = ((db.coerceIn(-127.5f, 0f) + 127.5f) * 2f).roundToInt().coerceIn(0, 255).toByte()
        private fun dequantiseDb(b: Byte): Float = (b.toInt() and 0xFF) / 2f - 127.5f
    }
}
