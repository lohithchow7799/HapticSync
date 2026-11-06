package com.hapticsync.engine

import com.hapticsync.analysis.AnalysisFrame
import com.hapticsync.analysis.SoundEvent
import com.hapticsync.haptics.HapticConfig
import com.hapticsync.haptics.HapticMapper
import com.hapticsync.haptics.HapticOutput
import com.hapticsync.haptics.HapticRenderer
import com.hapticsync.haptics.MediaClock
import com.hapticsync.haptics.OutputHistory
import com.hapticsync.haptics.PlanSample
import com.hapticsync.haptics.RenderedEffect
import com.hapticsync.haptics.TransientEffect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Counters of the prepared-track player, for diagnostics. */
class TrackStats {
    @Volatile var windowsIssued = 0L
    @Volatile var silentWindows = 0L
    @Volatile var restarts = 0L
    @Volatile var transientsRendered = 0L
    @Volatile var notReady = 0L

    /** Mean |issue time − window start| of seamless continuations (µs), exponentially averaged. */
    @Volatile var meanHandoverErrorUs = 0.0

    fun reset() {
        windowsIssued = 0; silentWindows = 0; restarts = 0; transientsRendered = 0; notReady = 0
        meanHandoverErrorUs = 0.0
    }
}

/**
 * Plays a prepared [HapticTrack] in sync with the media clock.
 *
 * Live mode sends one vibrator command per event plus short texture segments. With a prepared
 * track the future is known, so this player renders the haptic signal of the next 0.5–1.5 s of
 * media at once: transients and texture mixed on a 5 ms grid with the current settings, then
 * calibrated and issued as one amplitude waveform (on/off pattern on simple motors). Each
 * window is issued at `content time + sync offset − motor latency` on the media clock, and the
 * next one exactly when it ends; the boundary is placed at the quietest moment available so the
 * hand-over cannot be felt. Strong linear actuators (such as the Galaxy S25 Ultra's) therefore
 * receive about one command per second instead of many, with every transient exactly where the
 * analysis placed it.
 *
 * [tick] runs on the engine's scheduler thread; [stop] may be called from any thread.
 */
internal class TrackPlayer(
    private val output: HapticOutput,
    private val renderer: HapticRenderer,
    private val history: OutputHistory,
    private val eventLog: EventLog,
    config: HapticConfig,
    /** The scheduler's current epoch: output prepared for an older epoch is discarded. */
    private val currentEpoch: () -> Int,
) {
    @Volatile
    var config: HapticConfig = config
        set(value) {
            field = value
            textureMapper.config = value
            transientMapper.config = value
        }

    private var textureHopUs = DEFAULT_HOP_US
    private var textureMapper = HapticMapper(config, DEFAULT_HOP_US / 1000f)

    @Volatile
    var track: HapticTrack? = null
        set(value) {
            field = value
            stop()
        }

    val stats = TrackStats()

    private val lock = Any()
    private val transientMapper = HapticMapper(config, DEFAULT_HOP_US / 1000f)
    private val frame = AnalysisFrame()
    private val sample = PlanSample()
    private val events = ArrayList<SoundEvent>()
    private val keptEvents = ArrayList<SoundEvent>()
    private val keptEffects = ArrayList<TransientEffect>()
    private val texture = FloatArray(MAX_STEPS)
    private val transient = FloatArray(MAX_STEPS)
    private val mixed = FloatArray(MAX_STEPS)
    private val boundaryTimes = LongArray(MAX_HOPS)
    private val boundaryStates = Array(MAX_HOPS) { HapticMapper.State() }
    private val windowState = HapticMapper.State()

    // Scheduler-thread state.
    @Volatile private var windowStartUs = NONE
    @Volatile private var windowEndUs = NONE
    private var issuedEpoch = Int.MIN_VALUE
    private var issuedConfig: HapticConfig? = null
    private var issuedSpeed = 1f
    private var lastIssueNanos = 0L
    private var vibrating = false

    /** Stops output at once; the next [tick] starts afresh at the current position. */
    fun stop() = synchronized(lock) {
        if (vibrating) output.cancel()
        vibrating = false
        windowStartUs = NONE
        windowEndUs = NONE
    }

    /**
     * Issues the next window when it is due. [sinkMinusMediaUs] maps track (media) time to the
     * clock's time base. Returns how long the scheduler thread may sleep (nanoseconds).
     */
    fun tick(nowNanos: Long, clock: MediaClock, sinkMinusMediaUs: Long, epoch: Int, enabled: Boolean): Long {
        val t = track
        val c = config
        if (t == null || !enabled || !clock.isPlaying || !clock.isValid || sinkMinusMediaUs == UNKNOWN_OFFSET ||
            !output.capabilities.hasVibrator
        ) {
            if (vibrating || windowEndUs != NONE) stop()
            return IDLE_WAIT_NANOS
        }
        val hopUs = t.hopUs
        if (hopUs != textureHopUs) {
            // Texture smoothing depends on the hop length of the analysis.
            textureHopUs = hopUs
            textureMapper = HapticMapper(c, hopUs / 1000f)
            windowEndUs = NONE
        }
        val speed = clock.speed.coerceIn(0.1f, 8f)
        val mediaNow = clock.mediaTimeUs(nowNanos) - sinkMinusMediaUs
        // Content whose vibration has to start now so it is felt with the sound.
        val contentNow = mediaNow - c.fireOffsetUs
        val configChanged = c !== issuedConfig && nowNanos - lastIssueNanos >= CONFIG_RESTART_NANOS
        val restart = windowEndUs == NONE || epoch != issuedEpoch || speed != issuedSpeed || configChanged ||
            contentNow > windowEndUs + RESYNC_LATE_US || contentNow < windowStartUs - RESYNC_EARLY_US
        if (!restart && contentNow < windowEndUs - ISSUE_LEAD_US) {
            return waitNanos(windowEndUs - ISSUE_LEAD_US - contentNow, speed)
        }
        val continuation = !restart
        val from = if (continuation) max(windowEndUs, contentNow) else contentNow
        val readyEnd = t.readyEndFrom(from)
        // Less than MIN_READY_US ahead is enough when it is the end of the audio.
        if (readyEnd == null || (readyEnd - from < MIN_READY_US && !t.isAudioEnd(readyEnd))) {
            stats.notReady++
            if (vibrating || windowEndUs != NONE) stop()
            return NOT_READY_WAIT_NANOS
        }
        if (continuation) {
            // Late wake-up: advance the texture state over the content that is skipped.
            if (from > windowEndUs) advanceTexture(t, windowEndUs, from)
            stats.meanHandoverErrorUs = stats.meanHandoverErrorUs * 0.9 + abs(contentNow - windowEndUs) * 0.1
        } else {
            stats.restarts++
            warmUpTexture(t, from)
        }
        val window = render(t, c, from, readyEnd, epoch, speed) ?: run {
            stop()
            return NOT_READY_WAIT_NANOS
        }
        val issued = synchronized(lock) {
            // Paused or seeked while rendering: drop this window.
            if (!clock.isPlaying || currentEpoch() != epoch) return@synchronized false
            val effect = window.effect
            if (effect != null) {
                vibrating = output.play(effect) || vibrating
            } else if (vibrating) {
                output.cancel()
                vibrating = false
            }
            windowStartUs = from
            windowEndUs = window.endUs
            true
        }
        if (!issued) return IDLE_WAIT_NANOS
        issuedEpoch = epoch
        issuedConfig = c
        issuedSpeed = speed
        lastIssueNanos = nowNanos
        stats.windowsIssued++
        if (window.effect == null) stats.silentWindows++
        record(from, window, sinkMinusMediaUs)
        return waitNanos(window.endUs - ISSUE_LEAD_US - contentNow, speed)
    }

    private class Window(val endUs: Long, val steps: Int, val effect: RenderedEffect?)

    /** Renders [from, end) where end is chosen in the ready range at a quiet hop boundary. */
    private fun render(t: HapticTrack, c: HapticConfig, from: Long, readyEnd: Long, epoch: Int, speed: Float): Window? {
        val maxEnd = min(readyEnd, from + MAX_WINDOW_US)
        val steps = ((maxEnd - from + STEP_US - 1) / STEP_US).toInt().coerceAtMost(MAX_STEPS)
        if (steps <= 0) return null
        texture.fill(0f, 0, steps)
        transient.fill(0f, 0, steps)

        // 1. Texture, hop by hop, remembering the mapper state at every hop boundary.
        textureMapper.saveState(windowState)
        var hops = 0
        var time = from
        val end = from + steps * STEP_US
        while (time < end && hops < MAX_HOPS) {
            if (!t.readFrame(time, frame)) break
            val hopStart = frame.timeUs
            val hopEnd = hopStart + max(1L, frame.durationUs)
            textureMapper.mapFrame(frame, epoch, sample)
            // Faint texture would otherwise be lifted to the weakest perceptible level by the
            // calibration and felt as a constant low buzz on strong actuators.
            val a = if (sample.amplitude < TEXTURE_GATE) 0f else sample.amplitude
            val first = ((max(time, hopStart) - from) / STEP_US).toInt().coerceIn(0, steps)
            val last = ((min(hopEnd, end) - from + STEP_US - 1) / STEP_US).toInt().coerceIn(0, steps)
            for (i in first until last) texture[i] = a
            boundaryTimes[hops] = hopEnd
            textureMapper.saveState(boundaryStates[hops])
            hops++
            time = hopEnd
        }
        if (hops == 0) {
            textureMapper.restoreState(windowState)
            return null
        }

        // 2. Transients (with the live scheduler's spacing and priority rules), on the same grid.
        events.clear()
        t.events(from - LOOKBACK_US, end, events)
        keptEvents.clear()
        keptEffects.clear()
        for (e in events) {
            val effect = transientMapper.mapEvent(e, epoch) ?: continue
            val last = keptEvents.lastOrNull()
            if (last != null) {
                val gap = e.timeUs - last.timeUs
                val lastEffect = keptEffects.last()
                if (gap < COALESCE_US) {
                    // Simultaneous: keep the more important one.
                    if (effect.priority > lastEffect.priority || (effect.priority == lastEffect.priority && effect.curve.peak > lastEffect.curve.peak)) {
                        keptEvents[keptEvents.size - 1] = e
                        keptEffects[keptEffects.size - 1] = effect
                    }
                    continue
                }
                if (gap < c.pulseSpacingUs && effect.priority <= lastEffect.priority) continue
            }
            keptEvents += e
            keptEffects += effect
        }
        for (k in keptEffects.indices) {
            val effect = keptEffects[k]
            val curve = effect.curve.amplitudes
            val curveStepUs = (effect.curve.stepMs * 1000f).toLong().coerceAtLeast(1L)
            for (j in curve.indices) {
                val at = effect.timeUs + j * curveStepUs
                if (at < from) continue
                val i = ((at - from) / STEP_US).toInt()
                if (i >= steps) break
                if (curve[j] > transient[i]) transient[i] = curve[j]
            }
        }

        // 3. Mix: a transient stands out over the texture, which ducks underneath it.
        for (i in 0 until steps) {
            val tr = transient[i]
            mixed[i] = if (tr > TRANSIENT_FLOOR) max(tr, texture[i] * DUCK) else texture[i]
        }

        // 4. Window end: the quietest hop boundary between MIN and MAX length, close to nominal.
        val minEnd = from + MIN_WINDOW_US
        val nominal = from + NOMINAL_WINDOW_US
        var bestHop = hops - 1
        var bestScore = Float.MAX_VALUE
        for (h in 0 until hops) {
            val boundary = boundaryTimes[h]
            if (boundary < minEnd && h < hops - 1) continue
            if (boundary > end) break
            val i = ((boundary - from) / STEP_US).toInt().coerceIn(0, steps)
            var loud = 0f
            for (k in max(0, i - 2) until min(steps, i + 2)) loud = max(loud, mixed[k])
            val score = loud + abs(boundary - nominal) / 1_000_000f * DISTANCE_WEIGHT
            if (score < bestScore) { bestScore = score; bestHop = h }
        }
        val endUs = boundaryTimes[bestHop].coerceAtMost(end)
        textureMapper.restoreState(boundaryStates[bestHop])
        val count = ((endUs - from + STEP_US - 1) / STEP_US).toInt().coerceIn(1, steps)

        // 5. Encode for the device (calibration is applied by the renderer).
        val stepMs = STEP_US / 1000f / speed
        val effect = if (output.capabilities.hasAmplitudeControl) {
            renderer.waveform(mixed, count, stepMs, c)
        } else {
            renderer.onOff(mixed, count, stepMs, c)
        }
        stats.transientsRendered += keptEvents.count { it.timeUs in from until endUs }
        windowEvents.clear()
        for (e in keptEvents) if (e.timeUs in from until endUs) windowEvents += e
        return Window(endUs, count, effect)
    }

    private val windowEvents = ArrayList<SoundEvent>()

    /** Visualisation: commanded intensity in 50 ms blocks, and markers for the felt events. */
    private fun record(from: Long, window: Window, sinkMinusMediaUs: Long) {
        val c = config
        var i = 0
        while (i < window.steps) {
            val n = min(BLOCK_STEPS, window.steps - i)
            var peak = 0f
            var anyTransient = false
            for (k in i until i + n) {
                peak = max(peak, mixed[k])
                if (transient[k] > TRANSIENT_FLOOR) anyTransient = true
            }
            if (peak > 0f) {
                history.add(from + i * STEP_US + sinkMinusMediaUs, n * STEP_US, c.calibrate(peak), anyTransient, -1)
            }
            i += n
        }
        for (e in windowEvents) eventLog.add(e.timeUs + sinkMinusMediaUs, e.category.ordinal, e.strength)
    }

    /** Starts the texture state afresh at [from], warmed up on the preceding audio. */
    private fun warmUpTexture(t: HapticTrack, from: Long) {
        textureMapper.reset()
        advanceTexture(t, from - WARM_UP_US, from)
    }

    /** Runs the texture mapper over the hops in [fromUs, toUs) without producing output. */
    private fun advanceTexture(t: HapticTrack, fromUs: Long, toUs: Long) {
        var time = fromUs
        var guard = 0
        while (time < toUs && guard++ < MAX_ADVANCE_HOPS) {
            if (!t.readFrame(time, frame)) {
                time += DEFAULT_HOP_US
                continue
            }
            val hopEnd = frame.timeUs + max(1L, frame.durationUs)
            if (hopEnd > toUs) break
            textureMapper.mapFrame(frame, issuedEpoch, sample)
            time = hopEnd
        }
    }

    private fun waitNanos(mediaUs: Long, speed: Float): Long =
        ((mediaUs.coerceAtLeast(MIN_WAIT_US) / speed) * 1000f).toLong().coerceIn(MIN_WAIT_NANOS, MAX_WAIT_NANOS)

    companion object {
        /** Marker for "no media-time mapping yet". */
        const val UNKNOWN_OFFSET = Long.MIN_VALUE
        private const val NONE = Long.MIN_VALUE / 4

        /** Resolution of rendered windows (media µs). */
        const val STEP_US = 5_000L
        const val MIN_WINDOW_US = 500_000L
        const val NOMINAL_WINDOW_US = 1_000_000L
        const val MAX_WINDOW_US = 1_500_000L
        private const val MAX_STEPS = (MAX_WINDOW_US / STEP_US).toInt() + 2
        private const val MAX_HOPS = 400

        /** The next window is issued this long before the current one ends. */
        const val ISSUE_LEAD_US = 2_000L

        /** Clock jumps beyond these restart rendering at the new position. */
        private const val RESYNC_LATE_US = 150_000L
        private const val RESYNC_EARLY_US = 20_000L

        /** Minimum prepared audio ahead of the position for output to start. */
        private const val MIN_READY_US = 200_000L

        /** Earlier events whose curves reach into the window. */
        private const val LOOKBACK_US = 500_000L
        private const val COALESCE_US = 15_000L
        private const val TRANSIENT_FLOOR = 0.02f
        private const val TEXTURE_GATE = 0.06f
        private const val DUCK = 0.4f
        private const val DISTANCE_WEIGHT = 0.15f
        private const val BLOCK_STEPS = 10
        private const val WARM_UP_US = 3_000_000L
        private const val MAX_ADVANCE_HOPS = 2000
        private const val DEFAULT_HOP_US = 10_000L

        /** Settings changes restart the window at most this often (slider drags). */
        private const val CONFIG_RESTART_NANOS = 150_000_000L

        private const val IDLE_WAIT_NANOS = 20_000_000L
        private const val NOT_READY_WAIT_NANOS = 20_000_000L
        private const val MIN_WAIT_US = 500L
        private const val MIN_WAIT_NANOS = 500_000L
        private const val MAX_WAIT_NANOS = 25_000_000L
    }
}
