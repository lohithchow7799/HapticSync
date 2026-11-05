package com.hapticsync.engine

import com.hapticsync.haptics.MediaClock

/**
 * The media clock haptics follow: the audio sink's output position, sampled whenever the player
 * queries the sink (about every 10 ms while playing) and extrapolated in between using the
 * playback speed. Its time base is the sink's, the same as the PCM presentation timestamps.
 */
class PlaybackClock : MediaClock {
    private val lock = Any()
    private var positionUs = 0L
    private var sampleNanos = 0L
    private var hasSample = false

    @Volatile
    override var isPlaying: Boolean = false
        private set

    @Volatile
    override var speed: Float = 1f
        private set

    override val isValid: Boolean get() = synchronized(lock) { hasSample }

    fun update(positionUs: Long, systemNanoTime: Long) = synchronized(lock) {
        this.positionUs = positionUs
        this.sampleNanos = systemNanoTime
        hasSample = true
    }

    fun setPlaying(playing: Boolean, systemNanoTime: Long) = synchronized(lock) {
        if (hasSample && isPlaying && !playing) {
            // Freeze the clock at the extrapolated position at the moment playback stopped.
            positionUs = extrapolate(systemNanoTime)
            sampleNanos = systemNanoTime
        } else if (!isPlaying && playing) {
            sampleNanos = systemNanoTime
        }
        isPlaying = playing
    }

    fun setSpeed(newSpeed: Float, systemNanoTime: Long) = synchronized(lock) {
        if (hasSample) {
            positionUs = extrapolate(systemNanoTime)
            sampleNanos = systemNanoTime
        }
        speed = newSpeed.coerceIn(0.1f, 8f)
    }

    /** Forgets the position (after a seek or reset) until the sink reports a new one. */
    fun invalidate() = synchronized(lock) { hasSample = false }

    override fun mediaTimeUs(systemNanoTime: Long): Long = synchronized(lock) {
        if (!hasSample) Long.MIN_VALUE / 4 else extrapolate(systemNanoTime)
    }

    private fun extrapolate(now: Long): Long {
        if (!isPlaying) return positionUs
        val elapsedUs = ((now - sampleNanos).coerceAtLeast(0L)) / 1000L
        // Never extrapolate further than a fraction of a second without a fresh sample.
        return positionUs + (elapsedUs.coerceAtMost(MAX_EXTRAPOLATION_US) * speed).toLong()
    }

    companion object {
        const val MAX_EXTRAPOLATION_US = 500_000L
    }
}
