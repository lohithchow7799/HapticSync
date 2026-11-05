package com.hapticsync.analysis

import com.hapticsync.dsp.FloatHistory
import com.hapticsync.dsp.NoiseFloorTracker

/**
 * Detects slow, deep rumbles (thunder rolls) that build up over hundreds of milliseconds and
 * therefore produce no sharp onset.
 *
 * A rumble is reported when the sub-bass/bass level
 * - stands at least [RISE_DB] above its noise floor and has stayed ≥ 6 dB above it for 250 ms,
 * - was still close to the floor 400 ms earlier (a *new* rumble, not a steady drone),
 * - is loud relative to the programme, and
 * - is not matched by a comparable rise in the high bands.
 *
 * Engine sound, music, speech, the tail of an already reported big event and low-frequency
 * transients (kicks, footsteps, gunshot thumps) block detection; a preceding high-frequency
 * crack does not (close thunder starts with one). The start of the rise is reported, after the
 * fact, together with a lateness tolerance.
 */
internal class ThunderDetector(private val hopRateHz: Float) {

    private val lowFloor = NoiseFloorTracker(riseMs = 8000f, fallMs = 600f, updateRateHz = hopRateHz)
    private val subFloor = NoiseFloorTracker(riseMs = 8000f, fallMs = 600f, updateRateHz = hopRateHz)
    private val lowMidFloor = NoiseFloorTracker(riseMs = 8000f, fallMs = 600f, updateRateHz = hopRateHz)
    private val onsetTimes = LongArray(8)
    private var onsetCount = 0
    private val highFloor = NoiseFloorTracker(riseMs = 8000f, fallMs = 600f, updateRateHz = hopRateHz)
    private val lowExcess = FloatHistory(100)
    private val times = LongArray(100)
    private var count = 0L
    private var lastDetectionHop = -100_000L

    /** Records a detected onset (repeated onsets during a rise mean a burst, not a rumble). */
    fun onOnset(timeUs: Long) {
        onsetTimes[onsetCount % onsetTimes.size] = timeUs
        onsetCount++
    }

    private fun onsetsSince(timeUs: Long): Int {
        var n = 0
        for (i in 0 until minOf(onsetCount, onsetTimes.size)) if (onsetTimes[i] >= timeUs) n++
        return n
    }

    /** Returns the rise start time, or [Long.MIN_VALUE] when nothing was detected. */
    fun update(
        timeUs: Long,
        subDb: Float,
        lowDb: Float,
        lowMidDb: Float,
        highDb: Float,
        loudnessReferenceDb: Float,
        silent: Boolean,
        blocked: Boolean,
    ): Long {
        val lf = lowFloor.update(lowDb)
        val sf = subFloor.update(subDb)
        val lmf = lowMidFloor.update(lowMidDb)
        val hf = highFloor.update(highDb)
        val excess = (lowDb - lf).coerceAtLeast(0f)
        val highExcess = (highDb - hf).coerceAtLeast(0f)
        lowExcess.push(excess)
        times[(count % times.size).toInt()] = timeUs
        count++
        if (silent || blocked || count < WARM_UP_HOPS) return Long.MIN_VALUE
        if (count - lastDetectionHop < (3f * hopRateHz).toLong()) return Long.MIN_VALUE
        // Thunder is deep: the sub-bass itself must surge (wind and voices barely reach below 60 Hz).
        val subExcess = subDb - sf
        if (excess < RISE_DB || subExcess < SUB_RISE_DB || highExcess > excess - 6f) return Long.MIN_VALUE
        // A rumble is deep: low-mids rise far less (wind and engines lift them together).
        if (lowMidDb - lmf > subExcess - 6f) return Long.MIN_VALUE
        if (lowDb < loudnessReferenceDb - 8f || lowDb < MIN_LEVEL_DB) return Long.MIN_VALUE
        for (age in 0 until SUSTAIN_HOPS) if (lowExcess[age] < 6f) return Long.MIN_VALUE
        if (lowExcess[NEW_RISE_AGE] >= 4f) return Long.MIN_VALUE
        // Find where the rise started: the oldest hop of the current run above 3 dB.
        var age = 0
        while (age + 1 < lowExcess.size && age < 90 && lowExcess[age + 1] >= 3f) age++
        val start = times[((count - 1 - age) % times.size).toInt()]
        // A rise that contains a run of onsets is a burst of transients (gunfire, drums). Close
        // thunder may contribute two: its crack and one fluctuation of the building rumble.
        if (onsetsSince(start - 50_000L) >= 3) return Long.MIN_VALUE
        lastDetectionHop = count
        return start
    }

    fun reset() {
        lowFloor.reset()
        subFloor.reset()
        lowMidFloor.reset()
        highFloor.reset()
        onsetCount = 0
        lowExcess.clear()
        count = 0
        lastDetectionHop = -100_000L
    }

    companion object {
        private const val RISE_DB = 10f
        private const val SUB_RISE_DB = 8f
        private const val MIN_LEVEL_DB = -50f
        private const val WARM_UP_HOPS = 200
        private const val SUSTAIN_HOPS = 25
        private const val NEW_RISE_AGE = 40
        const val LATENESS_TOLERANCE_US = 600_000L
    }
}
