package com.hapticsync.haptics

/** The device vibration output. Implemented on Android by `:platform:vibration`. */
interface HapticOutput {
    val capabilities: DeviceCapabilities

    /** Starts [effect], replacing whatever is currently playing. Returns false on failure. */
    fun play(effect: RenderedEffect): Boolean

    /** Stops any ongoing vibration immediately. */
    fun cancel()
}

/** The media clock that haptics are synchronised to. */
interface MediaClock {
    /** Media time (sink time base) at [systemNanoTime], extrapolated from the last sample. */
    fun mediaTimeUs(systemNanoTime: Long): Long

    /** Whether audio is currently being output (not paused, buffering or ended). */
    val isPlaying: Boolean

    /** Current playback speed. */
    val speed: Float

    /** Whether the clock has received at least one position sample for the current stream. */
    val isValid: Boolean
}

/** Running statistics of the scheduler, for the sync-test and diagnostics screens. */
class SchedulerStats {
    @Volatile var transientsDispatched = 0L
    @Volatile var continuousSegments = 0L
    @Volatile var droppedStale = 0L
    @Volatile var droppedEpoch = 0L
    @Volatile var suppressedPriority = 0L
    @Volatile var suppressedSpacing = 0L
    @Volatile var cancellations = 0L
    @Volatile var outputFailures = 0L

    /** Mean absolute timing error of transient dispatch (µs), exponentially averaged. */
    @Volatile var meanDispatchErrorUs = 0.0

    /** Largest dispatch error observed (µs). */
    @Volatile var maxDispatchErrorUs = 0L

    /** How far ahead of their fire time transients were queued, exponentially averaged (µs). */
    @Volatile var meanLeadUs = 0.0

    fun recordDispatch(errorUs: Long, leadUs: Long) {
        transientsDispatched++
        val e = kotlin.math.abs(errorUs).toDouble()
        meanDispatchErrorUs = if (transientsDispatched == 1L) e else meanDispatchErrorUs * 0.9 + e * 0.1
        if (kotlin.math.abs(errorUs) > maxDispatchErrorUs) maxDispatchErrorUs = kotlin.math.abs(errorUs)
        meanLeadUs = if (transientsDispatched == 1L) leadUs.toDouble() else meanLeadUs * 0.9 + leadUs * 0.1
    }

    fun reset() {
        transientsDispatched = 0; continuousSegments = 0; droppedStale = 0; droppedEpoch = 0
        suppressedPriority = 0; suppressedSpacing = 0; cancellations = 0; outputFailures = 0
        meanDispatchErrorUs = 0.0; maxDispatchErrorUs = 0; meanLeadUs = 0.0
    }
}

/** A dispatched command, recorded for the "haptic output" visualisation. */
class OutputRecord(
    var mediaTimeUs: Long = 0L,
    var durationUs: Long = 0L,
    var peak: Float = 0f,
    var transient: Boolean = false,
    var category: Int = -1,
)
