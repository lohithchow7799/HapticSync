package com.hapticsync.platform.playback

import android.net.Uri
import com.hapticsync.engine.EngineThreadFactory
import com.hapticsync.engine.HapticTrack
import com.hapticsync.engine.HapticTrackBuilder
import com.hapticsync.model.HapticProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Prepares the haptic track of one media item in the background ("preparing haptics").
 *
 * The audio is decoded and analysed much faster than real time, starting at the requested
 * position. After a seek outside the prepared range, [ensure] restarts preparation at the new
 * position (with a short warm-up before it); prepared ranges are never analysed twice, and a
 * job that reaches an already prepared range continues after it. Once preparation is
 * [MAX_LEAD_US] ahead of playback it idles until playback catches up, to save battery on long
 * films.
 */
class HapticTrackPreparer(
    private val decoder: MediaAudioDecoder,
    private val uri: Uri,
    val track: HapticTrack,
    private val profile: HapticProfile,
    threadFactory: EngineThreadFactory,
) : AutoCloseable {

    /** Preparation outcome, for the UI. */
    sealed interface Status {
        /** Decoding and analysing. */
        data object Working : Status

        /** The whole item is prepared. */
        data object Complete : Status

        /** The audio cannot be decoded ahead of playback; haptics fall back to live analysis. */
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Working)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Current playback position (media time), for throttling. Set by the player. */
    @Volatile var playbackPositionUs: Long = 0L

    private val lock = Object()
    private var request: Long? = null
    @Volatile private var restartRequested = false
    @Volatile private var closed = false
    private val thread = threadFactory.newThread("HapticSync-prepare", false) { run() }

    /** Starts preparing at [fromUs]. */
    fun start(fromUs: Long) {
        ensure(fromUs, force = true)
        thread.start()
    }

    /**
     * Makes sure the range from [positionUs] gets prepared: if it is neither prepared nor being
     * prepared right now, preparation jumps there.
     */
    fun ensure(positionUs: Long, force: Boolean = false) {
        if (closed) return
        if (!force && track.readyEndFrom(positionUs) != null) return
        synchronized(lock) {
            if (!force && jobCovers(positionUs)) return
            request = positionUs.coerceAtLeast(0L)
            restartRequested = true
            lock.notifyAll()
        }
    }

    // Range the running job is about to cover: from its start to a little ahead of its frontier.
    @Volatile private var jobStartUs = Long.MAX_VALUE
    @Volatile private var jobFrontierUs = Long.MIN_VALUE

    private fun jobCovers(positionUs: Long): Boolean =
        positionUs >= jobStartUs + HapticTrackBuilder.WARM_UP_US && positionUs <= jobFrontierUs + JOB_REACH_US

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
    }

    private fun run() {
        while (!closed) {
            val target = synchronized(lock) {
                while (request == null && !closed) lock.wait()
                val next = request
                request = null
                restartRequested = false
                next
            } ?: break
            try {
                prepareFrom(target)
            } catch (e: AudioDecodeException) {
                _status.value = Status.Failed(e.message ?: "The audio could not be decoded.")
                return
            } catch (e: Exception) {
                _status.value = Status.Failed("Preparing haptics failed (${e.javaClass.simpleName}).")
                return
            } finally {
                jobStartUs = Long.MAX_VALUE
                jobFrontierUs = Long.MIN_VALUE
            }
        }
    }

    private fun prepareFrom(targetUs: Long) {
        var position = targetUs
        while (!closed && !restartRequested) {
            // Skip what is ready; continue right after it.
            val readyEnd = track.readyEndFrom(position)
            if (readyEnd != null) {
                if (track.complete && readyEnd >= track.durationUs - END_TOLERANCE_US) {
                    if (track.isReady(0, track.durationUs)) _status.value = Status.Complete
                    return
                }
                position = readyEnd
            }
            val startUs = if (position <= HapticTrackBuilder.WARM_UP_US) 0L else position - HapticTrackBuilder.WARM_UP_US
            val builder = HapticTrackBuilder(track, profile)
            jobStartUs = startUs
            jobFrontierUs = startUs
            val result = decoder.decode(
                uri,
                startUs,
                shouldStop = { closed || restartRequested },
                onFormat = builder::configure,
                onPcm = { buffer, from, to, timeUs ->
                    throttle(builder)
                    val more = builder.write(buffer, from, to, timeUs)
                    jobFrontierUs = builder.analysedUntilUs
                    more
                },
            )
            builder.finish(endOfStream = result.endOfStream && !restartRequested)
            if (track.durationUs <= 0L && result.durationUs > 0L) track.durationUs = result.durationUs
            if (restartRequested || closed) return
            when {
                builder.reachedAnalysedRange -> position = builder.analysedUntilUs
                result.endOfStream -> {
                    if (track.isReady(0, track.durationUs)) _status.value = Status.Complete
                    return
                }
                else -> return
            }
        }
    }

    private fun throttle(builder: HapticTrackBuilder) {
        while (!closed && !restartRequested && builder.analysedUntilUs - playbackPositionUs > MAX_LEAD_US) {
            Thread.sleep(THROTTLE_SLEEP_MS)
        }
    }

    companion object {
        /** Preparation pauses this far ahead of playback. */
        const val MAX_LEAD_US = 180_000_000L

        /** A running job "covers" positions up to this far beyond its frontier (it gets there soon). */
        private const val JOB_REACH_US = 10_000_000L
        private const val END_TOLERANCE_US = 25_000L
        private const val THROTTLE_SLEEP_MS = 200L
    }
}
