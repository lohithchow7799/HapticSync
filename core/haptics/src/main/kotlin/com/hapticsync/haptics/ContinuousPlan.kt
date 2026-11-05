package com.hapticsync.haptics

/**
 * Thread-safe ring of upcoming continuous-layer samples in media time. The analysis thread
 * appends one sample per hop, usually a few hundred milliseconds ahead of playback; the
 * scheduler slices the samples that cover the next output segment.
 */
class ContinuousPlan(private val capacity: Int = 512) {
    private val times = LongArray(capacity)
    private val amplitudes = FloatArray(capacity)
    private val sharpness = FloatArray(capacity)
    private val categories = IntArray(capacity)
    private var head = 0 // next write index
    private var count = 0
    private val lock = Any()

    /** Latest media time present in the plan, or Long.MIN_VALUE when empty. */
    val frontierUs: Long
        get() = synchronized(lock) { if (count == 0) Long.MIN_VALUE else times[(head - 1 + capacity) % capacity] }

    fun add(sample: PlanSample) = synchronized(lock) {
        // Keep the plan monotonic: an older timestamp means a discontinuity the caller missed.
        if (count > 0 && sample.timeUs <= times[(head - 1 + capacity) % capacity]) clearLocked()
        times[head] = sample.timeUs
        amplitudes[head] = sample.amplitude
        sharpness[head] = sample.sharpness
        categories[head] = sample.category
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        head = 0
        count = 0
    }

    /** Result of [slice]. */
    class Slice(capacity: Int) {
        val amplitudes = FloatArray(capacity)
        val sharpness = FloatArray(capacity)
        var count = 0
        /** Media duration of the first (partial) sample, in ms. */
        var firstStepMs = 0f
        var stepMs = 0f
        var category = -1
        val peak: Float get() { var p = 0f; for (i in 0 until count) if (amplitudes[i] > p) p = amplitudes[i]; return p }
        val durationUs: Long get() = if (count == 0) 0L else ((firstStepMs + (count - 1) * stepMs) * 1000f).toLong()
    }

    /**
     * Copies the samples covering `[fromUs, fromUs + spanUs)` into [out]. The first sample is
     * the one in effect at [fromUs]; samples are assumed to be one hop ([stepUs]) apart.
     */
    fun slice(fromUs: Long, spanUs: Long, stepUs: Long, out: Slice): Slice = synchronized(lock) {
        out.count = 0
        out.category = -1
        out.stepMs = stepUs / 1000f
        if (count == 0) return out
        val oldest = (head - count + capacity) % capacity
        // Find the last sample with time <= fromUs (binary search over the ring).
        var lo = 0
        var hi = count - 1
        var first = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = times[(oldest + mid) % capacity]
            if (t <= fromUs) { first = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (first < 0) {
            // The plan starts in the future; nothing covers "now".
            return out
        }
        val firstTime = times[(oldest + first) % capacity]
        if (fromUs - firstTime >= stepUs * 2) return out // stale: plan has a gap
        out.firstStepMs = ((firstTime + stepUs - fromUs).coerceAtLeast(1L)) / 1000f
        val endUs = fromUs + spanUs
        var i = first
        var bestAmplitude = -1f
        while (i < count && out.count < out.amplitudes.size) {
            val index = (oldest + i) % capacity
            if (i > first && times[index] >= endUs) break
            out.amplitudes[out.count] = amplitudes[index]
            out.sharpness[out.count] = sharpness[index]
            if (amplitudes[index] > bestAmplitude) { bestAmplitude = amplitudes[index]; out.category = categories[index] }
            out.count++
            i++
        }
        return out
    }
}
