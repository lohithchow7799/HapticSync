package com.hapticsync.platform.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class SeekIndexersTest {

    private fun reader(bytes: ByteArray) = RandomReader { position, buffer, offset, length ->
        if (position >= bytes.size) -1 else {
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            n
        }
    }

    // ---- Fragmented MP4 -----------------------------------------------------------------------

    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u64(v: Long) = u32(v ushr 32) + u32(v and 0xFFFFFFFFL)
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return u32(8L + body.size) + type.toByteArray() + body
    }
    private fun fullBox(type: String, version: Int, vararg payload: ByteArray) = box(type, byteArrayOf(version.toByte(), 0, 0, 0), *payload)

    private fun fragmentedMp4(fragments: Int, withVideo: Boolean = true): Pair<ByteArray, List<Long>> {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "isom".toByteArray(), u32(0), "iso6".toByteArray()))
        val mvhd = fullBox("mvhd", 0, u32(0), u32(0), u32(1000), u32(0), ByteArray(80))
        val mehd = fullBox("mehd", 0, u32(fragments * 2000L))
        val tkhd = fullBox("tkhd", 0, u32(0), u32(0), u32(1), ByteArray(64))
        val mdhd = fullBox("mdhd", 0, u32(0), u32(0), u32(90_000), u32(0), ByteArray(4))
        val hdlr = fullBox("hdlr", 0, u32(0), (if (withVideo) "vide" else "soun").toByteArray(), ByteArray(12))
        out.write(box("moov", mvhd, box("mvex", mehd), box("trak", tkhd, box("mdia", mdhd, hdlr))))
        val offsets = ArrayList<Long>()
        for (i in 0 until fragments) {
            offsets += out.size().toLong()
            val tfhd = fullBox("tfhd", 0, u32(1))
            val tfdt = fullBox("tfdt", 1, u64(i * 180_000L))
            out.write(box("moof", fullBox("mfhd", 0, u32(i + 1L)), box("traf", tfhd, tfdt)))
            out.write(box("mdat", ByteArray(500 + i * 10)))
        }
        return out.toByteArray() to offsets
    }

    @Test
    fun fragmentedMp4WithoutSidxGetsAnIndex() {
        val (bytes, offsets) = fragmentedMp4(6)
        val index = FragmentedMp4Indexer.build(reader(bytes), bytes.size.toLong())
        assertNotNull(index)
        assertArrayEquals(offsets.toLongArray(), index!!.offsets)
        assertArrayEquals(LongArray(6) { it * 2_000_000L }, index.timesUs)
        assertEquals(12_000_000L, index.durationUs)
        assertEquals(bytes.size.toLong(), index.fileLength)
    }

    @Test
    fun audioOnlyFragmentsUseTheirOwnTrack() {
        val (bytes, _) = fragmentedMp4(4, withVideo = false)
        val index = FragmentedMp4Indexer.build(reader(bytes), bytes.size.toLong())
        assertArrayEquals(LongArray(4) { it * 2_000_000L }, index!!.timesUs)
    }

    @Test
    fun plainMp4OrGarbageHasNoFragmentIndex() {
        val plain = box("ftyp", "isom".toByteArray()) + box("moov", fullBox("mvhd", 0, ByteArray(96))) + box("mdat", ByteArray(1000))
        assertNull(FragmentedMp4Indexer.build(reader(plain), plain.size.toLong()))
        val garbage = ByteArray(4096) { (it * 31).toByte() }
        assertNull(FragmentedMp4Indexer.build(reader(garbage), garbage.size.toLong()))
    }

    // ---- Matroska -------------------------------------------------------------------------------

    private fun id(v: Long): ByteArray {
        val bytes = ArrayList<Byte>()
        var x = v
        while (x != 0L) { bytes.add(0, (x and 0xFF).toByte()); x = x ushr 8 }
        return bytes.toByteArray()
    }
    /** 4-byte EBML size vint (marker 0x10). */
    private fun size(n: Long): ByteArray =
        byteArrayOf((0x10 or ((n shr 24).toInt() and 0x0F)).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun unknownSize() = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)
    private fun el(idValue: Long, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return id(idValue) + size(body.size.toLong()) + body
    }

    private fun matroska(clusters: Int, unknownSizeCluster: Int = -1): Pair<ByteArray, List<Long>> {
        val out = ByteArrayOutputStream()
        out.write(el(0x1A45DFA3L, el(0x4282L, "webm".toByteArray())))
        out.write(id(0x18538067L) + unknownSize())
        val duration = java.lang.Double.doubleToLongBits(clusters * 2000.0)
        out.write(el(0x1549A966L, el(0x2AD7B1L, u32(1_000_000L)), el(0x4489L, u64(duration))))
        out.write(el(0x1654AE6BL, ByteArray(40)))
        val offsets = ArrayList<Long>()
        for (i in 0 until clusters) {
            offsets += out.size().toLong()
            val children = el(0xE7L, u32(i * 2000L)) + el(0xA3L, ByteArray(300)) + el(0xA3L, ByteArray(200))
            if (i == unknownSizeCluster) out.write(id(0x1F43B675L) + unknownSize() + children)
            else out.write(el(0x1F43B675L, children))
        }
        return out.toByteArray() to offsets
    }

    @Test
    fun matroskaWithoutCuesGetsAnIndex() {
        val (bytes, offsets) = matroska(5)
        val index = MatroskaIndexer.build(reader(bytes), bytes.size.toLong())
        assertNotNull(index)
        assertArrayEquals(offsets.toLongArray(), index!!.offsets)
        assertArrayEquals(LongArray(5) { it * 2_000_000L }, index.timesUs)
        assertEquals(10_000_000L, index.durationUs)
    }

    @Test
    fun unknownSizeClustersAreWalked() {
        val (bytes, offsets) = matroska(4, unknownSizeCluster = 1)
        val index = MatroskaIndexer.build(reader(bytes), bytes.size.toLong())
        assertArrayEquals(offsets.toLongArray(), index!!.offsets)
    }

    @Test
    fun seekMapCoversTheWholeFile() {
        val (bytes, _) = fragmentedMp4(3)
        val index = FragmentedMp4Indexer.build(reader(bytes), bytes.size.toLong())!!
        val map = index.toSeekMap()
        assertEquals(true, map.isSeekable)
        assertEquals(6_000_000L, map.durationUs)
        assertEquals(index.offsets[1], map.getSeekPoints(2_500_000L).first.position)
    }
}
