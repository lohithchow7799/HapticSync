package com.hapticsync.haptics

import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

/**
 * Timestamp-aware haptic scheduler.
 *
 * Every effect carries the *media* time at which it must be felt. The scheduler compares it
 * with the [MediaClock] (the audio sink's output position, extrapolated between samples) and
 * issues the command when `mediaTime + syncOffset − actuatorLatency` is reached. It never uses
 * free-running timers: waits are computed from the media clock and divided by the playback
 * speed.
 *
 * Rules:
 * - **Epochs**: after a seek or other discontinuity [discontinuity] increments the epoch; queued
 *   effects and plan samples of older epochs are discarded and the vibrator is stopped.
 * - **Staleness**: an effect whose fire time has passed by more than [STALE_US] (or its own
 *   lateness tolerance) is dropped; a moderately late long effect has its beginning skipped so
 *   its remainder stays in sync.
 * - **Mixing**: transients replace the continuous texture; a running transient is interrupted only
 *   by a higher priority one or once it is half over; consecutive transients respect the
 *   user's pulse spacing; simultaneous effects coalesce to the most important one.
 * - **Continuous layer**: segments of ~[SEGMENT_US] are issued back to back from the
 *   [ContinuousPlan], re-issued just before the previous one ends.
 * - **Safety**: [playbackStopped] cancels output immediately; dispatch and cancellation share a
 *   lock, so nothing can start after playback stopped.
 *
 * Threading: [submit]/[submitPlan] are called from the analysis thread, [discontinuity] and
 * [playbackStopped] from the player's thread, [tick] from the scheduler thread.
 */
class HapticScheduler(
    private val output: HapticOutput,
    private val clock: MediaClock,
    private val renderer: HapticRenderer,
    config: HapticConfig,
    hopDurationUs: Long,
) {
    @Volatile
    var config: HapticConfig = config

    /** Master haptics switch. */
    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) playbackStopped()
        }

    @Volatile
    var hopDurationUs: Long = hopDurationUs

    val stats = SchedulerStats()
    val plan = ContinuousPlan()
    val history = OutputHistory()

    private val queue = PriorityQueue<TransientEffect>(32, compareBy<TransientEffect> { it.timeUs })
    private val queueLock = Any()
    private val outputLock = Any()
    private val slice = ContinuousPlan.Slice(64)

    @Volatile
    private var epoch = 0

    // Scheduler-thread state.
    private var vibrating = false
    private var activeTransientStartUs = NONE
    private var activeTransientUntilUs = NONE
    private var activePriority = 0
    private var lastTransientFireUs = NONE
    private var lastTransientPriority = 0
    private var continuousUntilUs = NONE
    private var lastOutputNanos = 0L

    /** Epoch that new effects must carry. */
    val currentEpoch: Int get() = epoch

    /** Number of transients waiting in the queue. */
    val pendingTransients: Int get() = synchronized(queueLock) { queue.size }

    /** Queues a transient (analysis thread). Effects from an old epoch are ignored. */
    fun submit(effect: TransientEffect) {
        if (effect.epoch != epoch) return
        synchronized(queueLock) {
            if (queue.size >= MAX_QUEUE) queue.poll()
            queue.add(effect)
        }
    }

    /** Appends a continuous-plan sample (analysis thread). */
    fun submitPlan(sample: PlanSample) {
        if (sample.epoch != epoch) return
        plan.add(sample)
    }

    /**
     * Seek, track change or flush: invalidates everything scheduled so far and stops the
     * vibrator. Returns the new epoch.
     */
    fun discontinuity(): Int = synchronized(outputLock) {
        epoch++
        synchronized(queueLock) { queue.clear() }
        plan.clear()
        stopLocked()
        epoch
    }

    /** Playback paused, buffering, ended or the app went to the background: stop at once. */
    fun playbackStopped() = synchronized(outputLock) { stopLocked() }

    private fun stopLocked() {
        if (vibrating) stats.cancellations++
        output.cancel()
        vibrating = false
        activeTransientUntilUs = NONE
        continuousUntilUs = NONE
    }

    /**
     * Performs all dispatches that are due and returns how long the scheduler thread may sleep
     * (nanoseconds) before the next one, assuming nothing new is submitted.
     */
    fun tick(nowNanos: Long): Long {
        val c = config
        if (!enabled || !clock.isPlaying || !clock.isValid || !output.capabilities.hasVibrator) {
            if (vibrating) playbackStopped()
            return IDLE_WAIT_NANOS
        }
        val speed = clock.speed.coerceIn(0.1f, 8f)
        val tickEpoch = epoch
        val now = clock.mediaTimeUs(nowNanos)
        val fireOffset = c.fireOffsetUs
        var nextWake = now + (MAX_WAIT_US * speed).toLong()

        // 1. Transients that are due (coalesced to the most important one).
        var chosen: TransientEffect? = null
        synchronized(queueLock) {
            while (true) {
                val head = queue.peek() ?: break
                val fireAt = head.timeUs + fireOffset
                if (fireAt > now + EARLY_US) {
                    nextWake = min(nextWake, fireAt)
                    break
                }
                queue.poll()
                if (head.epoch != tickEpoch) { stats.droppedEpoch++; continue }
                if (now - fireAt > max(STALE_US, head.latenessToleranceUs)) { stats.droppedStale++; continue }
                val current = chosen
                chosen = if (current == null || isMoreImportant(head, current)) head else current
            }
        }
        val effect = chosen
        if (effect != null) {
            val fireAt = effect.timeUs + fireOffset
            val transientActive = now < activeTransientUntilUs
            val progress = if (transientActive) {
                (now - activeTransientStartUs).toFloat() / max(1L, activeTransientUntilUs - activeTransientStartUs)
            } else 1f
            val mayInterrupt = !transientActive || effect.priority > activePriority || progress >= 0.5f
            val spacingOk = now - lastTransientFireUs >= c.pulseSpacingUs || effect.priority > lastTransientPriority
            when {
                !mayInterrupt -> stats.suppressedPriority++
                !spacingOk -> stats.suppressedSpacing++
                else -> {
                    val lateness = (now - fireAt).coerceAtLeast(0L)
                    val skipMs = if (lateness > SKIP_THRESHOLD_US) lateness / 1000f else 0f
                    val rendered = renderer.renderTransient(effect, c, speed, skipMs)
                    if (rendered != null && play(rendered, tickEpoch, nowNanos)) {
                        val mediaDurationUs = (rendered.durationMs * 1000f * speed).toLong()
                        activeTransientStartUs = now
                        activeTransientUntilUs = now + mediaDurationUs
                        activePriority = effect.priority
                        lastTransientFireUs = now
                        lastTransientPriority = effect.priority
                        continuousUntilUs = NONE
                        stats.recordDispatch(now - fireAt, effect.timeUs - now)
                        history.add(effect.timeUs, mediaDurationUs, rendered.peakAmplitude, true, effect.category.ordinal)
                    }
                }
            }
        }

        // 2. Continuous texture, when no transient is playing.
        if (now >= activeTransientUntilUs) {
            if (now >= continuousUntilUs - REISSUE_MARGIN_US) {
                plan.slice(now, SEGMENT_US, hopDurationUs, slice)
                val rendered = if (slice.count > 0 && c.calibrate(slice.peak) > 0f) {
                    renderer.renderContinuous(slice.amplitudes, slice.sharpness, slice.count, slice.stepMs, slice.firstStepMs, c, speed)
                } else null
                if (rendered != null) {
                    if (nowNanos - lastOutputNanos >= MIN_CALL_INTERVAL_NANOS && play(rendered, tickEpoch, nowNanos)) {
                        continuousUntilUs = now + (rendered.durationMs * 1000f * speed).toLong()
                        stats.continuousSegments++
                        history.add(now, continuousUntilUs - now, rendered.peakAmplitude, false, slice.category)
                    }
                } else {
                    if (vibrating) synchronized(outputLock) { stopLocked() }
                    continuousUntilUs = now + hopDurationUs
                }
            }
            nextWake = min(nextWake, continuousUntilUs - REISSUE_MARGIN_US)
        } else {
            nextWake = min(nextWake, activeTransientUntilUs)
        }

        val waitMediaUs = (nextWake - now).coerceAtLeast(MIN_WAIT_US)
        return ((waitMediaUs / speed) * 1000f).toLong().coerceIn(MIN_WAIT_NANOS, MAX_WAIT_NANOS)
    }

    private fun isMoreImportant(a: TransientEffect, b: TransientEffect): Boolean =
        a.priority > b.priority || (a.priority == b.priority && a.curve.peak > b.curve.peak)

    /** Plays [effect] unless playback stopped or a discontinuity happened since the tick began. */
    private fun play(effect: RenderedEffect, tickEpoch: Int, nowNanos: Long): Boolean = synchronized(outputLock) {
        if (!enabled || !clock.isPlaying || tickEpoch != epoch) return false
        val ok = output.play(effect)
        if (ok) {
            vibrating = true
            lastOutputNanos = nowNanos
        } else {
            stats.outputFailures++
        }
        ok
    }

    /** Clears all state (new media item); keeps the epoch counter increasing. */
    fun reset() {
        discontinuity()
        stats.reset()
        history.clear()
    }

    companion object {
        /** "No time" sentinel; far in the past but safe from arithmetic overflow. */
        private const val NONE = Long.MIN_VALUE / 4

        /** Effects later than this are dropped (unless they carry a larger tolerance). */
        const val STALE_US = 40_000L

        /** Late effects lose this much or more of their beginning to stay in sync. */
        const val SKIP_THRESHOLD_US = 15_000L

        /** Effects due within this window are dispatched now. */
        const val EARLY_US = 1_000L

        /** Length of continuous segments (media time). */
        const val SEGMENT_US = 150_000L

        /** The next continuous segment is issued this long before the current one ends. */
        const val REISSUE_MARGIN_US = 8_000L

        private const val MAX_WAIT_US = 20_000L
        private const val MIN_WAIT_US = 500L
        private const val MIN_WAIT_NANOS = 500_000L
        private const val MAX_WAIT_NANOS = 20_000_000L
        private const val IDLE_WAIT_NANOS = 20_000_000L
        private const val MIN_CALL_INTERVAL_NANOS = 6_000_000L
        private const val MAX_QUEUE = 256
    }
}

/** Ring of recently dispatched commands (media time), for the haptic-output visualisation. */
class OutputHistory(private val capacity: Int = 256) {
    private val times = LongArray(capacity)
    private val durations = LongArray(capacity)
    private val peaks = FloatArray(capacity)
    private val transients = BooleanArray(capacity)
    private val categories = IntArray(capacity)
    private var head = 0
    private var count = 0
    private val lock = Any()

    fun add(mediaTimeUs: Long, durationUs: Long, peak: Float, transient: Boolean, category: Int) = synchronized(lock) {
        times[head] = mediaTimeUs
        durations[head] = durationUs
        peaks[head] = peak
        transients[head] = transient
        categories[head] = category
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() = synchronized(lock) { head = 0; count = 0 }

    /** Commanded intensity at [mediaTimeUs] (the strongest command covering that time). */
    fun intensityAt(mediaTimeUs: Long): Float = synchronized(lock) {
        var best = 0f
        for (i in 0 until count) {
            val index = (head - 1 - i + capacity) % capacity
            val start = times[index]
            if (mediaTimeUs >= start && mediaTimeUs < start + durations[index]) best = max(best, peaks[index])
            if (start + 5_000_000L < mediaTimeUs) break
        }
        best
    }

    /** Copies the most recent records into [out]; returns how many were copied. */
    fun recent(out: Array<OutputRecord>): Int = synchronized(lock) {
        val n = min(count, out.size)
        for (i in 0 until n) {
            val index = (head - 1 - i + capacity) % capacity
            val r = out[i]
            r.mediaTimeUs = times[index]
            r.durationUs = durations[index]
            r.peak = peaks[index]
            r.transient = transients[index]
            r.category = categories[index]
        }
        n
    }
}
