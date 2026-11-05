package com.hapticsync.engine

import com.hapticsync.analysis.AnalysisFrame
import com.hapticsync.analysis.SoundEvent
import com.hapticsync.model.BandId

/**
 * Everything the live visualisation draws for one display frame. Owned by the UI and filled by
 * [HapticSyncEngine.snapshot]; reuse one instance to avoid allocation per frame.
 *
 * All values come from the actual analysis of the playing audio, aligned to the media time that
 * is currently being *heard* (the analysis itself runs a few hundred milliseconds ahead).
 */
class VisualizationSnapshot {
    var valid = false
    var mediaTimeUs = 0L

    /** Log-spaced spectrum, 0..1. */
    val spectrum = FloatArray(AnalysisFrame.SPECTRUM_BARS)

    /** Band levels, 0..1, in [BandId] order. */
    val bandLevels = FloatArray(BandId.COUNT)

    /** Amplitude envelope over the last [HISTORY_SECONDS] (oldest first), 0..1. */
    val envelope = FloatArray(HISTORY_POINTS)

    /** Commanded haptic intensity over the same window, 0..1. */
    val hapticHistory = FloatArray(HISTORY_POINTS)

    /** Event markers in the window: position 0..1 (1 = now), category ordinal and strength. */
    val markerPosition = FloatArray(MAX_MARKERS)
    val markerCategory = IntArray(MAX_MARKERS)
    val markerStrength = FloatArray(MAX_MARKERS)
    var markerCount = 0

    var transientIntensity = 0f
    var onset = false
    var hapticIntensity = 0f
    var dominantCategory = -1
    var engineLikelihood = 0f
    var rpm = 0f
    var speech = 0f
    var levelDb = -120f

    companion object {
        const val HISTORY_POINTS = 240
        const val HISTORY_SECONDS = 3f
        const val MAX_MARKERS = 24
    }
}

/**
 * Ring of per-hop analysis summaries keyed by media time, written by the analysis thread and
 * read by the UI thread.
 */
internal class VisualizationBuffer(private val capacity: Int = 512) {
    private val times = LongArray(capacity)
    private val spectra = Array(capacity) { FloatArray(AnalysisFrame.SPECTRUM_BARS) }
    private val bands = Array(capacity) { FloatArray(BandId.COUNT) }
    private val levels = FloatArray(capacity)
    private val levelDb = FloatArray(capacity)
    private val transients = FloatArray(capacity)
    private val onsets = BooleanArray(capacity)
    private val dominant = IntArray(capacity)
    private val engine = FloatArray(capacity)
    private val rpm = FloatArray(capacity)
    private val speech = FloatArray(capacity)
    private var head = 0
    private var count = 0
    private val lock = Any()

    fun push(frame: AnalysisFrame) = synchronized(lock) {
        if (count > 0 && frame.timeUs <= times[(head - 1 + capacity) % capacity]) clearLocked()
        val i = head
        times[i] = frame.timeUs
        frame.spectrum.copyInto(spectra[i])
        frame.bandLevel.copyInto(bands[i])
        levels[i] = ((frame.rmsDb + 60f) / 60f).coerceIn(0f, 1f)
        levelDb[i] = frame.rmsDb
        transients[i] = frame.transientIntensity
        onsets[i] = frame.onsetDetected
        dominant[i] = frame.dominantCategory
        engine[i] = frame.engineLikelihood
        rpm[i] = frame.rpmNormalized
        speech[i] = frame.speechLikelihood
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        head = 0
        count = 0
    }

    /** Fills the frame-dependent parts of [out] for media time [nowUs]. Returns false if empty. */
    fun fill(nowUs: Long, out: VisualizationSnapshot): Boolean = synchronized(lock) {
        if (count == 0) return false
        val oldest = (head - count + capacity) % capacity
        val current = indexAtOrBefore(nowUs, oldest) ?: return false
        spectra[current].copyInto(out.spectrum)
        bands[current].copyInto(out.bandLevels)
        out.transientIntensity = transients[current]
        out.onset = onsets[current]
        out.dominantCategory = dominant[current]
        out.engineLikelihood = engine[current]
        out.rpm = rpm[current]
        out.speech = speech[current]
        out.levelDb = levelDb[current]
        // Envelope history: sample the window [now − history, now].
        val windowUs = (VisualizationSnapshot.HISTORY_SECONDS * 1_000_000f).toLong()
        val stepUs = windowUs / VisualizationSnapshot.HISTORY_POINTS
        var t = nowUs - windowUs
        var search = oldest
        for (p in 0 until VisualizationSnapshot.HISTORY_POINTS) {
            val index = indexAtOrBefore(t, search)
            if (index == null) {
                out.envelope[p] = 0f
            } else {
                out.envelope[p] = levels[index]
                search = index
            }
            t += stepUs
        }
        true
    }

    /** Index of the newest frame with time ≤ [timeUs], searching forward from [fromIndex]. */
    private fun indexAtOrBefore(timeUs: Long, fromIndex: Int): Int? {
        val oldest = (head - count + capacity) % capacity
        if (times[oldest] > timeUs) return null
        // Binary search over logical positions.
        var lo = 0
        var hi = count - 1
        var best = 0
        val start = ((fromIndex - oldest + capacity) % capacity).coerceAtMost(count - 1)
        lo = if (times[(oldest + start) % capacity] <= timeUs) start else 0
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (times[(oldest + m) % capacity] <= timeUs) { best = m; lo = m + 1 } else hi = m - 1
        }
        return (oldest + best) % capacity
    }
}

/** Ring of recent classified events (for timeline markers and the "current sound" chip). */
internal class EventLog(private val capacity: Int = 64) {
    private val times = LongArray(capacity)
    private val categories = IntArray(capacity)
    private val strengths = FloatArray(capacity)
    private var head = 0
    private var count = 0
    private val lock = Any()

    fun add(event: SoundEvent) = synchronized(lock) {
        times[head] = event.timeUs
        categories[head] = event.category.ordinal
        strengths[head] = event.strength
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() = synchronized(lock) { head = 0; count = 0 }

    /** Adds markers for events heard within the visualisation window ending at [nowUs]. */
    fun fill(nowUs: Long, out: VisualizationSnapshot) = synchronized(lock) {
        val windowUs = (VisualizationSnapshot.HISTORY_SECONDS * 1_000_000f).toLong()
        var n = 0
        var latestTime = Long.MIN_VALUE
        var latestCategory = -1
        for (i in 0 until count) {
            val index = (head - 1 - i + capacity) % capacity
            val t = times[index]
            if (t > nowUs) continue // analysed but not heard yet
            if (t < nowUs - windowUs) continue
            if (n < VisualizationSnapshot.MAX_MARKERS) {
                out.markerPosition[n] = 1f - (nowUs - t).toFloat() / windowUs
                out.markerCategory[n] = categories[index]
                out.markerStrength[n] = strengths[index]
                n++
            }
            if (t > latestTime) { latestTime = t; latestCategory = categories[index] }
        }
        out.markerCount = n
        // An event heard within the last 400 ms names the current sound.
        if (latestCategory >= 0 && nowUs - latestTime < 400_000L) out.dominantCategory = latestCategory
    }
}
