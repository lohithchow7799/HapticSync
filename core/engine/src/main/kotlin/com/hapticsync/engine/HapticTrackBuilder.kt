package com.hapticsync.engine

import com.hapticsync.analysis.AudioAnalyzer
import com.hapticsync.analysis.SoundEvent
import com.hapticsync.analysis.SoundEventSink
import com.hapticsync.dsp.PcmConverter
import com.hapticsync.dsp.PcmFormat
import com.hapticsync.model.AnalysisQuality
import com.hapticsync.model.AnalysisSettings
import com.hapticsync.model.BandSettings
import com.hapticsync.model.HapticProfile
import com.hapticsync.model.SoundCategory
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The profile settings that change what the analysis detects (as opposed to how detected
 * sounds are felt). A prepared track stays valid until these change.
 */
data class DetectionSettings(
    val bands: List<BandSettings>,
    val categorySensitivity: List<Float>,
    val analysis: AnalysisSettings,
    val eventSensitivity: Float,
    val minAmplitudeDb: Float,
    val pulseSpacingMs: Float,
) {
    companion object {
        fun of(profile: HapticProfile): DetectionSettings {
            val p = profile.validated()
            return DetectionSettings(
                bands = p.bands,
                categorySensitivity = SoundCategory.entries.map { p.category(it).sensitivity },
                analysis = p.analysis,
                eventSensitivity = p.haptics.eventSensitivity,
                minAmplitudeDb = p.haptics.minAmplitudeDb,
                pulseSpacingMs = p.haptics.pulseSpacingMs,
            )
        }
    }
}

/**
 * Analyses decoded audio ahead of playback into a [HapticTrack] ("preparing haptics").
 *
 * Feed PCM in media-time order with [write] (from one thread), then call [finish]. Unlike live
 * analysis, the builder sees the future: the classifier always gets its full decision window,
 * and events are published only once [LOOKAHEAD_US] of later audio has been analysed, so each
 * can be judged against its surroundings (salience filtering):
 *
 * - events far quieter than the programme around them are dropped (background clutter), the
 *   margin following the user's event sensitivity;
 * - the catch-all "general audio" category must be clearly prominent to be felt;
 * - an event right next to a much stronger one is dropped (it would only blur the stronger hit);
 * - at most [MAX_EVENTS_PER_SECOND] events per second survive.
 *
 * Detection always runs at full quality, whatever the live quality setting.
 */
class HapticTrackBuilder(
    val track: HapticTrack,
    profile: HapticProfile,
    /** Media time where decoding starts (0: the start of the item). */
    private val decodeStartUs: Long,
) {
    private var startedSegments = 0
    private val profile: HapticProfile = profile.validated().let {
        it.copy(analysis = it.analysis.copy(quality = AnalysisQuality.HIGH))
    }
    private val sensitivity = this.profile.haptics.eventSensitivity
    private val converter = PcmConverter()
    private var format: PcmFormat? = null
    private var analyzer: AudioAnalyzer? = null
    private var segment: HapticTrack.Segment? = null
    private var hopFrames = 0
    private var mid = FloatArray(0)
    private var side = FloatArray(0)
    private var filled = 0
    private var hopIndex = 0
    private val pending = ArrayList<SoundEvent>()
    private val recentKept = ArrayDeque<SoundEvent>()
    private var lastPublishedHop = 0
    private val levels = LevelWindow(PROGRAMME_WINDOW_HOPS)
    private val sink = SoundEventSink { pending += it }

    /** Media time up to which audio has been analysed. */
    val analysedUntilUs: Long get() = segment?.analysedEndUs ?: Long.MIN_VALUE

    /** Media time up to which the track is final. */
    val readyUntilUs: Long get() = segment?.readyEndUs ?: Long.MIN_VALUE

    /** Set once analysis ran into a range another segment already covers: nothing left to do. */
    var reachedAnalysedRange: Boolean = false
        private set

    /** Number of events dropped by salience filtering (diagnostics). */
    var droppedBySalience: Int = 0
        private set

    /** Sets the PCM format of the following [write] calls. */
    fun configure(format: PcmFormat) {
        if (this.format == format) return
        val previous = this.format
        this.format = format
        converter.configure(format)
        if (previous == null || previous.sampleRate != format.sampleRate) {
            // A new rate means a new hop grid: close the current segment and start afresh.
            if (segment != null) closeSegment()
            analyzer = AudioAnalyzer(profile, format.sampleRate, sink)
            hopFrames = analyzer!!.config.hopSize
            mid = FloatArray(hopFrames)
            side = FloatArray(hopFrames)
            filled = 0
        }
    }

    /**
     * Analyses interleaved PCM `buffer[startByte, endByte)` in the configured format, starting
     * at media time [ptsUs]. Returns false once there is nothing more to analyse (the audio has
     * reached a range that is already prepared).
     */
    fun write(buffer: ByteBuffer, startByte: Int, endByte: Int, ptsUs: Long): Boolean {
        val f = checkNotNull(format) { "configure() first" }
        val a = checkNotNull(analyzer)
        if (reachedAnalysedRange) return false
        var current = segment
        if (current == null || abs(ptsUs - expectedTimeUs(current)) > RESYNC_US) {
            // First audio, or a timestamp jump: (re)start a segment at this position.
            if (current != null) closeSegment()
            // Audio that starts the item is reliable from 0 (even if it begins a little later,
            // e.g. in screen recordings); elsewhere the analysis needs a warm-up first.
            val startOfItem = ptsUs <= START_OF_STREAM_US || (startedSegments == 0 && decodeStartUs <= 0L)
            val readyFrom = if (startOfItem) 0L else ptsUs + WARM_UP_US
            current = track.startSegment(ptsUs, hopFrames, f.sampleRate, readyFrom)
            startedSegments++
            segment = current
            hopIndex = 0
            lastPublishedHop = 0
            filled = 0
            a.reset()
            levels.clear()
            pending.clear()
            recentKept.clear()
        }
        val frameBytes = f.bytesPerFrame
        var byte = startByte
        var frames = (endByte - startByte) / frameBytes
        while (frames > 0) {
            val n = minOf(frames, hopFrames - filled)
            converter.convert(buffer, byte, n, mid, side, filled)
            filled += n
            byte += n * frameBytes
            frames -= n
            if (filled == hopFrames) {
                val time = current.hopTimeUs(hopIndex)
                // Once past its own warm-up, stop where another segment is already prepared.
                if (time >= current.readyStartUs && track.readyElsewhere(current, time)) {
                    reachedAnalysedRange = true
                    publish(current, final = true)
                    return false
                }
                val frame = a.process(mid, side, 0, time)
                track.appendFrame(current, frame)
                levels.add(frame.rmsDb)
                hopIndex++
                filled = 0
                if (hopIndex - lastPublishedHop >= PUBLISH_EVERY_HOPS) publish(current, final = false)
            }
        }
        return true
    }

    /** End of the audio (or of this preparation): publishes everything analysed. */
    fun finish(endOfStream: Boolean) {
        val current = segment
        current?.let { publish(it, final = true) }
        // No audio at all from the decode start: the audio ends before it.
        if (endOfStream) track.markEndOfAudio(current?.analysedEndUs ?: decodeStartUs)
    }

    private fun expectedTimeUs(segment: HapticTrack.Segment): Long {
        val f = format ?: return segment.analysedEndUs
        return segment.hopTimeUs(hopIndex) + filled * 1_000_000L / f.sampleRate
    }

    private fun closeSegment() {
        segment?.let { publish(it, final = true) }
        segment = null
    }

    private fun publish(segment: HapticTrack.Segment, final: Boolean) {
        lastPublishedHop = hopIndex
        val analysedEnd = segment.analysedEndUs
        val readyEnd = if (final) analysedEnd else analysedEnd - LOOKAHEAD_US
        if (readyEnd <= segment.readyEndUs && !final) return
        val programmeDb = levels.percentile(0.85f)
        val batch = ArrayList<SoundEvent>()
        val remaining = ArrayList<SoundEvent>()
        for (e in pending) if (e.timeUs < readyEnd) batch += e else remaining += e
        batch.sortBy { it.timeUs }
        val kept = ArrayList<SoundEvent>(batch.size)
        for (e in batch) {
            if (keep(segment, e, programmeDb, batch, remaining)) {
                kept += e
                recentKept.addLast(e)
            } else {
                droppedBySalience++
            }
        }
        pending.clear()
        pending.addAll(remaining)
        track.publish(segment, kept, readyEnd)
    }

    private fun keep(
        segment: HapticTrack.Segment,
        e: SoundEvent,
        programmeDb: Float,
        batch: List<SoundEvent>,
        later: List<SoundEvent>,
    ): Boolean {
        // 1. Prominence against the programme level around it.
        val level = eventLevelDb(segment, e.timeUs)
        val margin = MIN_MARGIN_DB + MARGIN_RANGE_DB * sensitivity
        val general = e.category == SoundCategory.GENERAL
        if (programmeDb > SILENCE_DB) {
            val required = programmeDb - if (general) margin * 0.5f else margin
            if (level < required) return false
        }
        if (general && e.strength < GENERAL_MIN_STRENGTH) return false
        // 2. Masking by a much stronger neighbour.
        for (other in batch) {
            if (other !== e && abs(other.timeUs - e.timeUs) <= MASK_US && other.strength >= e.strength * MASK_RATIO) return false
        }
        for (other in later) {
            if (other.timeUs - e.timeUs > MASK_US) continue
            if (other.strength >= e.strength * MASK_RATIO) return false
        }
        // 3. Density cap over the last second of kept events.
        while (recentKept.isNotEmpty() && e.timeUs - recentKept.first().timeUs >= 1_000_000L) recentKept.removeFirst()
        if (recentKept.size >= MAX_EVENTS_PER_SECOND) return false
        return true
    }

    /** Loudest hop level within [EVENT_LEVEL_SPAN_US] after the onset. */
    private fun eventLevelDb(segment: HapticTrack.Segment, timeUs: Long): Float {
        val first = segment.hopIndexAt(timeUs).coerceAtLeast(0)
        val last = segment.hopIndexAt(timeUs + EVENT_LEVEL_SPAN_US).coerceAtMost(hopIndex - 1)
        var best = -127.5f
        for (i in first..last) best = maxOf(best, track.levelDb(segment, i))
        return best
    }

    /** Sliding histogram of hop levels (0.5 dB bins) for programme-level percentiles. */
    private class LevelWindow(private val capacity: Int) {
        private val ring = IntArray(capacity)
        private val histogram = IntArray(BINS)
        private var head = 0
        private var count = 0
        private var audible = 0

        fun clear() {
            histogram.fill(0)
            head = 0
            count = 0
            audible = 0
        }

        fun add(db: Float) {
            val bin = ((db.coerceIn(-127.5f, 0f) + 127.5f) * 2f).roundToInt().coerceIn(0, BINS - 1)
            if (count == capacity) {
                val old = ring[head]
                histogram[old]--
                if (old >= AUDIBLE_BIN) audible--
            } else {
                count++
            }
            ring[head] = bin
            histogram[bin]++
            if (bin >= AUDIBLE_BIN) audible++
            head = (head + 1) % capacity
        }

        /** [p]-quantile of the audible levels, or [SILENCE_DB] when there are none. */
        fun percentile(p: Float): Float {
            if (audible == 0) return SILENCE_DB
            val target = (audible * p).toInt().coerceIn(0, audible - 1)
            var seen = 0
            for (bin in AUDIBLE_BIN until BINS) {
                seen += histogram[bin]
                if (seen > target) return bin / 2f - 127.5f
            }
            return 0f
        }

        companion object {
            const val BINS = 256
            /** Bins below −80 dBFS count as silence. */
            val AUDIBLE_BIN = ((-80f + 127.5f) * 2f).roundToInt()
        }
    }

    companion object {
        /** Events are published once this much later audio has been analysed. */
        const val LOOKAHEAD_US = 4_000_000L

        /** Analysis started mid-stream settles (noise floors, loudness) within this time. */
        const val WARM_UP_US = 1_500_000L

        /** A first timestamp at or before this is treated as the start of the stream. */
        const val START_OF_STREAM_US = 50_000L

        /** Timestamp jumps beyond this start a new segment. */
        const val RESYNC_US = 100_000L

        private const val PUBLISH_EVERY_HOPS = 50
        private const val PROGRAMME_WINDOW_HOPS = 4000

        private const val MIN_MARGIN_DB = 8f
        private const val MARGIN_RANGE_DB = 14f
        private const val SILENCE_DB = -90f
        private const val GENERAL_MIN_STRENGTH = 0.35f
        private const val MASK_US = 120_000L
        private const val MASK_RATIO = 1.6f
        private const val EVENT_LEVEL_SPAN_US = 60_000L
        const val MAX_EVENTS_PER_SECOND = 12
    }
}
