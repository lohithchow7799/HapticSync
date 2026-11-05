package com.hapticsync.dsp

import java.nio.ByteBuffer

/**
 * Converts interleaved PCM bytes into the engine's internal representation: two planar float
 * signals in [-1, 1],
 *
 * - **mid**: a weighted mono downmix of all channels, used for general analysis, and
 * - **side**: `(left − right) / 2`, which preserves stereo (panning) information.
 *
 * Multichannel layouts follow Android's canonical channel order (FL, FR, FC, LFE, BL, BR, SL,
 * SR). The LFE channel is mixed in at full weight because it carries exactly the cinematic
 * sub-bass content that matters most for haptics.
 *
 * Reads use absolute indexed accessors and never touch the buffer's position, limit or byte
 * order, so the player's buffer is left exactly as it was. Allocation-free after [configure].
 */
class PcmConverter {

    private var encoding = PcmEncoding.PCM_16BIT
    private var channels = 2
    private var bytesPerSample = 2
    private val weights = FloatArray(PcmFormat.MAX_CHANNELS)

    /** Format currently configured. */
    var format: PcmFormat? = null
        private set

    fun configure(format: PcmFormat) {
        this.format = format
        encoding = format.encoding
        channels = format.channelCount
        bytesPerSample = format.encoding.bytesPerSample
        val raw = downmixWeights(channels)
        var sum = 0f
        for (w in raw) sum += w
        for (c in weights.indices) weights[c] = if (c < raw.size) raw[c] / sum else 0f
    }

    /**
     * Converts [frames] frames starting at absolute byte index [startByte] of [buffer] and writes
     * them to `mid[outOffset ..]` / `side[outOffset ..]`.
     */
    fun convert(
        buffer: ByteBuffer,
        startByte: Int,
        frames: Int,
        mid: FloatArray,
        side: FloatArray,
        outOffset: Int,
    ) {
        checkNotNull(format) { "configure() must be called first" }
        val frameBytes = channels * bytesPerSample
        var byteIndex = startByte
        var out = outOffset
        if (channels == 1) {
            for (f in 0 until frames) {
                mid[out] = readSample(buffer, byteIndex)
                side[out] = 0f
                byteIndex += frameBytes
                out++
            }
        } else if (channels == 2) {
            for (f in 0 until frames) {
                val l = readSample(buffer, byteIndex)
                val r = readSample(buffer, byteIndex + bytesPerSample)
                mid[out] = 0.5f * (l + r)
                side[out] = 0.5f * (l - r)
                byteIndex += frameBytes
                out++
            }
        } else {
            for (f in 0 until frames) {
                var m = 0f
                var sampleIndex = byteIndex
                var l = 0f
                var r = 0f
                for (c in 0 until channels) {
                    val s = readSample(buffer, sampleIndex)
                    if (c == 0) l = s else if (c == 1) r = s
                    m += s * weights[c]
                    sampleIndex += bytesPerSample
                }
                mid[out] = m
                side[out] = 0.5f * (l - r)
                byteIndex += frameBytes
                out++
            }
        }
    }

    /**
     * Same as [convert] for samples held in a float array (interleaved), e.g. synthesised audio
     * in tests and the demo generator.
     */
    fun convertFloats(
        interleaved: FloatArray,
        startFrame: Int,
        frames: Int,
        mid: FloatArray,
        side: FloatArray,
        outOffset: Int,
    ) {
        checkNotNull(format) { "configure() must be called first" }
        var i = startFrame * channels
        for (f in 0 until frames) {
            if (channels == 1) {
                mid[outOffset + f] = interleaved[i]
                side[outOffset + f] = 0f
            } else {
                var m = 0f
                for (c in 0 until channels) m += interleaved[i + c] * weights[c]
                mid[outOffset + f] = m
                side[outOffset + f] = 0.5f * (interleaved[i] - interleaved[i + 1])
            }
            i += channels
        }
    }

    private fun readSample(buffer: ByteBuffer, index: Int): Float = when (encoding) {
        PcmEncoding.PCM_16BIT -> {
            val v = (buffer.get(index).toInt() and 0xFF) or (buffer.get(index + 1).toInt() shl 8)
            v.toShort() * INV_32768
        }
        PcmEncoding.PCM_FLOAT -> {
            val bits = (buffer.get(index).toInt() and 0xFF) or
                ((buffer.get(index + 1).toInt() and 0xFF) shl 8) or
                ((buffer.get(index + 2).toInt() and 0xFF) shl 16) or
                (buffer.get(index + 3).toInt() shl 24)
            val f = Float.fromBits(bits)
            if (f.isNaN()) 0f else f.coerceIn(-4f, 4f)
        }
        PcmEncoding.PCM_8BIT -> ((buffer.get(index).toInt() and 0xFF) - 128) * INV_128
        PcmEncoding.PCM_16BIT_BIG_ENDIAN -> {
            val v = (buffer.get(index + 1).toInt() and 0xFF) or (buffer.get(index).toInt() shl 8)
            v.toShort() * INV_32768
        }
        PcmEncoding.PCM_24BIT -> {
            val v = (buffer.get(index).toInt() and 0xFF) or
                ((buffer.get(index + 1).toInt() and 0xFF) shl 8) or
                (buffer.get(index + 2).toInt() shl 16)
            v * INV_8388608
        }
        PcmEncoding.PCM_24BIT_BIG_ENDIAN -> {
            val v = (buffer.get(index + 2).toInt() and 0xFF) or
                ((buffer.get(index + 1).toInt() and 0xFF) shl 8) or
                (buffer.get(index).toInt() shl 16)
            v * INV_8388608
        }
        PcmEncoding.PCM_32BIT -> {
            val v = (buffer.get(index).toInt() and 0xFF) or
                ((buffer.get(index + 1).toInt() and 0xFF) shl 8) or
                ((buffer.get(index + 2).toInt() and 0xFF) shl 16) or
                (buffer.get(index + 3).toInt() shl 24)
            (v * INV_2147483648).toFloat()
        }
        PcmEncoding.PCM_32BIT_BIG_ENDIAN -> {
            val v = (buffer.get(index + 3).toInt() and 0xFF) or
                ((buffer.get(index + 2).toInt() and 0xFF) shl 8) or
                ((buffer.get(index + 1).toInt() and 0xFF) shl 16) or
                (buffer.get(index).toInt() shl 24)
            (v * INV_2147483648).toFloat()
        }
    }

    companion object {
        private const val INV_128 = 1f / 128f
        private const val INV_32768 = 1f / 32768f
        private const val INV_8388608 = 1f / 8388608f
        private const val INV_2147483648 = 1.0 / 2147483648.0

        /**
         * Un-normalised downmix weights in Android channel order. Centre and surrounds are
         * attenuated by ~3 dB as in common ITU-style downmixes; LFE is kept at unity.
         */
        fun downmixWeights(channelCount: Int): FloatArray = when (channelCount) {
            1 -> floatArrayOf(1f)
            2 -> floatArrayOf(1f, 1f)
            3 -> floatArrayOf(1f, 1f, 0.707f)                         // FL FR FC
            4 -> floatArrayOf(1f, 1f, 0.707f, 0.707f)                 // FL FR BL BR (quad)
            5 -> floatArrayOf(1f, 1f, 0.707f, 0.707f, 0.707f)         // FL FR FC BL BR
            6 -> floatArrayOf(1f, 1f, 0.707f, 1f, 0.707f, 0.707f)     // 5.1: FL FR FC LFE BL BR
            7 -> floatArrayOf(1f, 1f, 0.707f, 1f, 0.707f, 0.5f, 0.5f) // 6.1
            8 -> floatArrayOf(1f, 1f, 0.707f, 1f, 0.5f, 0.5f, 0.707f, 0.707f) // 7.1
            else -> FloatArray(channelCount) { 1f }
        }
    }
}
