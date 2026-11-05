package com.hapticsync.dsp.synth

import java.io.OutputStream
import kotlin.math.roundToInt

/** Writes 16-bit little-endian PCM WAV files. */
object WavWriter {

    /**
     * Writes [interleaved] float samples (nominal range [-1, 1]) with [channels] channels at
     * [sampleRate] as a 16-bit PCM WAV stream. Samples are clipped to the 16-bit range.
     */
    fun write16(out: OutputStream, interleaved: FloatArray, sampleRate: Int, channels: Int) {
        val dataBytes = interleaved.size * 2
        val header = ByteArray(44)
        fun putInt(offset: Int, value: Int) {
            header[offset] = value.toByte()
            header[offset + 1] = (value shr 8).toByte()
            header[offset + 2] = (value shr 16).toByte()
            header[offset + 3] = (value shr 24).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = value.toByte()
            header[offset + 1] = (value shr 8).toByte()
        }
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        putInt(4, 36 + dataBytes)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        putInt(16, 16)                       // PCM fmt chunk size
        putShort(20, 1)                      // audio format: PCM
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, sampleRate * channels * 2) // byte rate
        putShort(32, channels * 2)           // block align
        putShort(34, 16)                     // bits per sample
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        putInt(40, dataBytes)
        out.write(header)

        val chunk = ByteArray(8192)
        var pos = 0
        for (sample in interleaved) {
            val v = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt()
            chunk[pos++] = v.toByte()
            chunk[pos++] = (v shr 8).toByte()
            if (pos == chunk.size) {
                out.write(chunk, 0, pos)
                pos = 0
            }
        }
        if (pos > 0) out.write(chunk, 0, pos)
        out.flush()
    }
}
