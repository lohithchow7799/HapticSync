package com.hapticsync.platform.playback

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Test media: AAC encoded on the device, written as MP4 by the platform muxer, as MP4 with an
 * encoder delay (edit list, like iTunes/ffmpeg files), or as fragmented MP4 without an index.
 */
object AacTestMedia {

    class Encoded(val sampleRate: Int, val channels: Int, val csd: ByteArray, val frames: List<ByteArray>, val format: MediaFormat)

    /** Stereo test signal: a quiet tone with sharp clicks at [clickTimesSec]. */
    fun clicks(sampleRate: Int, seconds: Double, clickTimesSec: List<Double>): ShortArray {
        val frames = (sampleRate * seconds).toInt()
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            var v = 0.02 * sin(2 * PI * 330.0 * i / sampleRate)
            for (t in clickTimesSec) {
                val k = i - (t * sampleRate).toInt()
                if (k in 0 until (0.004 * sampleRate).toInt()) v += 0.8 * (if (k % 8 < 4) 1.0 else -1.0)
            }
            val s = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            out[2 * i] = s
            out[2 * i + 1] = s
        }
        return out
    }

    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int = 2): Encoded {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val frames = ArrayList<ByteArray>()
        var csd = ByteArray(0)
        var outputFormat: MediaFormat? = null
        var inputFrame = 0
        val totalFrames = pcm.size / channels
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    val n = minOf(totalFrames - inputFrame, buffer.capacity() / (2 * channels), 1024)
                    if (n <= 0) {
                        codec.queueInputBuffer(index, 0, 0, inputFrame * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val bytes = ByteBuffer.allocate(n * 2 * channels).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n * channels) bytes.putShort(pcm[inputFrame * channels + i])
                        bytes.flip()
                        buffer.put(bytes)
                        codec.queueInputBuffer(index, 0, n * 2 * channels, inputFrame * 1_000_000L / sampleRate, 0)
                        inputFrame += n
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 10_000)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = codec.outputFormat
                outputFormat.getByteBuffer("csd-0")?.let { b -> csd = ByteArray(b.remaining()).also { b.duplicate().get(it) } }
            } else if (out >= 0) {
                val buffer = codec.getOutputBuffer(out)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    if (csd.isEmpty()) csd = ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                } else if (info.size > 0) {
                    frames += ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop()
        codec.release()
        return Encoded(sampleRate, channels, csd, frames, outputFormat ?: format)
    }

    /** Plain (indexed) MP4 written by the platform muxer. */
    fun writeMuxerMp4(file: File, encoded: Encoded) {
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = muxer.addTrack(encoded.format)
        muxer.start()
        val info = MediaCodec.BufferInfo()
        encoded.frames.forEachIndexed { i, frame ->
            info.set(0, frame.size, i * 1024L * 1_000_000L / encoded.sampleRate, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            muxer.writeSampleData(track, ByteBuffer.wrap(frame), info)
        }
        muxer.stop()
        muxer.release()
    }

    /**
     * Plain MP4 whose edit list declares [delayFrames] of encoder delay (priming samples), which
     * players trim at the start of playback, but not after a seek.
     */
    fun writeMp4WithDelay(file: File, encoded: Encoded, delayFrames: Int) {
        val n = encoded.frames.size
        val durationTicks = n * 1024L
        val ftyp = box("ftyp", "M4A ".toByteArray(), u32(0), "M4A ".toByteArray(), "isom".toByteArray(), "mp42".toByteArray())
        fun moovWith(dataOffset: Long): ByteArray {
            val stbl = box(
                "stbl",
                stsd(encoded),
                fullBox("stts", 0, 0, u32(1), u32(n.toLong()), u32(1024)),
                fullBox("stsc", 0, 0, u32(1), u32(1), u32(n.toLong()), u32(1)),
                fullBox("stsz", 0, 0, u32(0), u32(n.toLong()), encoded.frames.fold(ByteArray(0)) { acc, f -> acc + u32(f.size.toLong()) }),
                fullBox("stco", 0, 0, u32(1), u32(dataOffset)),
            )
            // One edit: skip the delay, play the rest (movie timescale = sample rate).
            val edts = box("edts", fullBox("elst", 0, 0, u32(1), u32(durationTicks - delayFrames), u32(delayFrames.toLong()), u16(1), u16(0)))
            return moov(encoded, movieTimescale = encoded.sampleRate.toLong(), durationTicks = durationTicks, stbl = stbl, edts = edts, fragmented = false)
        }
        val moovSize = moovWith(0).size
        val mdat = box("mdat", encoded.frames.fold(ByteArray(0)) { acc, f -> acc + f })
        file.writeBytes(ftyp + moovWith(ftyp.size + moovSize + 8L) + mdat)
    }

    /** Fragmented MP4 without a `sidx` box: players cannot seek it without building an index. */
    fun writeFragmentedMp4(file: File, encoded: Encoded, framesPerFragment: Int = 43) {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "iso6".toByteArray(), u32(0), "iso6".toByteArray(), "mp41".toByteArray()))
        val emptyStbl = box("stbl", stsd(encoded), fullBox("stts", 0, 0, u32(0)), fullBox("stsc", 0, 0, u32(0)), fullBox("stsz", 0, 0, u32(0), u32(0)), fullBox("stco", 0, 0, u32(0)))
        out.write(moov(encoded, movieTimescale = 1000, durationTicks = 0, stbl = emptyStbl, edts = null, fragmented = true))
        var sequence = 1L
        var frame = 0
        while (frame < encoded.frames.size) {
            val n = minOf(framesPerFragment, encoded.frames.size - frame)
            val samples = encoded.frames.subList(frame, frame + n)
            val baseTime = frame * 1024L
            // trun: data offset + per-sample duration and size.
            fun moofWith(dataOffset: Long): ByteArray {
                val trunEntries = samples.fold(ByteArray(0)) { acc, s -> acc + u32(1024) + u32(s.size.toLong()) }
                val trun = fullBox("trun", 0, 0x000301, u32(n.toLong()), u32(dataOffset), trunEntries)
                val tfhd = fullBox("tfhd", 0, 0x020000, u32(1))
                val tfdt = fullBox("tfdt", 1, 0, u64(baseTime))
                return box("moof", fullBox("mfhd", 0, 0, u32(sequence)), box("traf", tfhd, tfdt, trun))
            }
            val size = moofWith(0).size
            out.write(moofWith(size + 8L))
            out.write(box("mdat", samples.fold(ByteArray(0)) { acc, s -> acc + s }))
            sequence++
            frame += n
        }
        file.writeBytes(out.toByteArray())
    }

    private fun stsd(e: Encoded): ByteArray {
        val esds = fullBox("esds", 0, 0, esDescriptor(e.csd))
        val mp4a = box("mp4a", ByteArray(6), u16(1), ByteArray(8), u16(e.channels), u16(16), u16(0), u16(0), u32(e.sampleRate.toLong() shl 16), esds)
        return fullBox("stsd", 0, 0, u32(1), mp4a)
    }

    private fun moov(e: Encoded, movieTimescale: Long, durationTicks: Long, stbl: ByteArray, edts: ByteArray?, fragmented: Boolean): ByteArray {
        val matrix = u32(0x00010000) + u32(0) + u32(0) + u32(0) + u32(0x00010000) + u32(0) + u32(0) + u32(0) + u32(0x40000000)
        val mvhd = fullBox("mvhd", 0, 0, u32(0), u32(0), u32(movieTimescale), u32(durationTicks), u32(0x00010000), u16(0x0100), ByteArray(10), matrix, ByteArray(24), u32(2))
        val tkhd = fullBox("tkhd", 0, 7, u32(0), u32(0), u32(1), u32(0), u32(durationTicks), ByteArray(8), u16(0), u16(0), u16(0x0100), u16(0), matrix, u32(0), u32(0))
        val mdhd = fullBox("mdhd", 0, 0, u32(0), u32(0), u32(e.sampleRate.toLong()), u32(durationTicks), u16(0x55C4), u16(0))
        val hdlr = fullBox("hdlr", 0, 0, u32(0), "soun".toByteArray(), ByteArray(12), "SoundHandler".toByteArray() + byteArrayOf(0))
        val dinf = box("dinf", fullBox("dref", 0, 0, u32(1), fullBox("url ", 0, 1)))
        val minf = box("minf", fullBox("smhd", 0, 0, u16(0), u16(0)), dinf, stbl)
        val mdia = box("mdia", mdhd, hdlr, minf)
        val trak = if (edts != null) box("trak", tkhd, edts, mdia) else box("trak", tkhd, mdia)
        if (!fragmented) return box("moov", mvhd, trak)
        val mvex = box("mvex", fullBox("trex", 0, 0, u32(1), u32(1), u32(0), u32(0), u32(0)))
        return box("moov", mvhd, trak, mvex)
    }

    private fun esDescriptor(csd: ByteArray): ByteArray {
        val decoderSpecific = byteArrayOf(0x05, csd.size.toByte()) + csd
        val decoderConfig = byteArrayOf(0x04, (13 + decoderSpecific.size).toByte(), 0x40, 0x15, 0, 0x18, 0) +
            u32(128_000) + u32(128_000) + decoderSpecific
        val sl = byteArrayOf(0x06, 0x01, 0x02)
        val body = byteArrayOf(0, 1, 0) + decoderConfig + sl
        return byteArrayOf(0x03, body.size.toByte()) + body
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u64(v: Long) = u32(v ushr 32) + u32(v and 0xFFFFFFFFL)
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return u32(8L + body.size) + type.toByteArray() + body
    }
    private fun fullBox(type: String, version: Int, flags: Int, vararg payload: ByteArray) =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()), *payload)
}
