package com.hapticsync.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmConverterTest {

    private fun convert(format: PcmFormat, bytes: ByteArray, startByte: Int = 0): Pair<FloatArray, FloatArray> {
        val frames = (bytes.size - startByte) / format.bytesPerFrame
        val mid = FloatArray(frames)
        val side = FloatArray(frames)
        val converter = PcmConverter().apply { configure(format) }
        // Big-endian buffer order on purpose: the converter must not depend on buffer.order().
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        converter.convert(buffer, startByte, frames, mid, side, 0)
        assertEquals("buffer position must be untouched", 0, buffer.position())
        return mid to side
    }

    @Test
    fun pcm16StereoProducesMidAndSide() {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(16384).putShort(0)           // L = 0.5, R = 0
        bb.putShort(-32768).putShort(-32768)     // L = R = -1
        val (mid, side) = convert(PcmFormat(48_000, 2, PcmEncoding.PCM_16BIT), bb.array())
        assertEquals(0.25f, mid[0], 1e-4f)
        assertEquals(0.25f, side[0], 1e-4f)
        assertEquals(-1f, mid[1], 1e-4f)
        assertEquals(0f, side[1], 1e-4f)
    }

    @Test
    fun pcm16BigEndian() {
        val bb = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(-16384)
        val (mid, _) = convert(PcmFormat(44_100, 1, PcmEncoding.PCM_16BIT_BIG_ENDIAN), bb.array())
        assertEquals(-0.5f, mid[0], 1e-4f)
    }

    @Test
    fun pcm8Unsigned() {
        val (mid, _) = convert(PcmFormat(8_000, 1, PcmEncoding.PCM_8BIT), byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x00))
        assertEquals(0f, mid[0], 1e-6f)
        assertEquals(127f / 128f, mid[1], 1e-6f)
        assertEquals(-1f, mid[2], 1e-6f)
    }

    @Test
    fun pcm24LittleAndBigEndian() {
        // -0.5 in 24-bit = -4194304 = 0xC00000
        val le = byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x3F)
        val (midLe, _) = convert(PcmFormat(96_000, 1, PcmEncoding.PCM_24BIT), le)
        assertEquals(-0.5f, midLe[0], 1e-6f)
        assertEquals(0.5f, midLe[1], 1e-6f)
        val be = byteArrayOf(0xC0.toByte(), 0x00, 0x00)
        val (midBe, _) = convert(PcmFormat(96_000, 1, PcmEncoding.PCM_24BIT_BIG_ENDIAN), be)
        assertEquals(-0.5f, midBe[0], 1e-6f)
    }

    @Test
    fun pcm32IntegerAndFloat() {
        val ints = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE).putInt(1 shl 30).array()
        val (midInt, _) = convert(PcmFormat(48_000, 1, PcmEncoding.PCM_32BIT), ints)
        assertEquals(-1f, midInt[0], 1e-6f)
        assertEquals(0.5f, midInt[1], 1e-6f)

        val intsBe = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1 shl 30).array()
        val (midBe, _) = convert(PcmFormat(48_000, 1, PcmEncoding.PCM_32BIT_BIG_ENDIAN), intsBe)
        assertEquals(0.5f, midBe[0], 1e-6f)

        val floats = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.75f).putFloat(Float.NaN).putFloat(-0.25f).array()
        val (midF, _) = convert(PcmFormat(48_000, 1, PcmEncoding.PCM_FLOAT), floats)
        assertEquals(0.75f, midF[0], 1e-6f)
        assertEquals("NaN must be sanitised", 0f, midF[1], 0f)
        assertEquals(-0.25f, midF[2], 1e-6f)
    }

    @Test
    fun honoursStartOffset() {
        val bb = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(8192).putShort(-8192)
        val (mid, _) = convert(PcmFormat(48_000, 1, PcmEncoding.PCM_16BIT), bb.array(), startByte = 2)
        assertEquals(2, mid.size)
        assertEquals(0.25f, mid[0], 1e-4f)
        assertEquals(-0.25f, mid[1], 1e-4f)
    }

    @Test
    fun surround51KeepsLfeInTheMix() {
        val format = PcmFormat(48_000, 6, PcmEncoding.PCM_16BIT)
        val bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        // Only the LFE channel (index 3) carries signal.
        for (c in 0 until 6) bb.putShort(if (c == 3) 32767 else 0)
        val (mid, side) = convert(format, bb.array())
        val weights = PcmConverter.downmixWeights(6)
        val expected = 32767f / 32768f * weights[3] / weights.sum()
        assertEquals(expected, mid[0], 1e-4f)
        assertEquals(0f, side[0], 1e-6f)
    }

    @Test
    fun convertFloatsMatchesByteConversion() {
        val converter = PcmConverter().apply { configure(PcmFormat(44_100, 2, PcmEncoding.PCM_FLOAT)) }
        val interleaved = floatArrayOf(0.2f, 0.6f, -0.4f, 0f)
        val mid = FloatArray(2)
        val side = FloatArray(2)
        converter.convertFloats(interleaved, 0, 2, mid, side, 0)
        assertEquals(0.4f, mid[0], 1e-6f)
        assertEquals(-0.2f, side[0], 1e-6f)
        assertEquals(-0.2f, mid[1], 1e-6f)
    }

    @Test
    fun timeMathIsExact() {
        assertEquals(1_000_000L, TimeMath.framesToUs(48_000, 48_000))
        assertEquals(10_000L, TimeMath.framesToUs(441, 44_100))
        assertEquals(441L, TimeMath.usToFrames(10_000, 44_100))
        // Hours of audio at high sample rates do not overflow.
        val hourFrames = 768_000L * 3600 * 5
        assertEquals(5L * 3600 * 1_000_000, TimeMath.framesToUs(hourFrames, 768_000))
        val format = PcmFormat(22_050, 2, PcmEncoding.PCM_16BIT)
        assertEquals(4, format.bytesPerFrame)
        assertEquals(500_000L, format.framesToUs(11_025))
    }
}
