package com.hapticsync.platform.playback

/** Positional reads from a media file (any thread). */
fun interface RandomReader {
    /** Reads up to [length] bytes at [position] into [buffer]; returns the count, or -1 at the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** Seek points of a file without a container index: byte offsets and their media times. */
class SeekIndex(
    val offsets: LongArray,
    val timesUs: LongArray,
    val durationUs: Long,
    val fileLength: Long,
) {
    val size: Int get() = offsets.size
}

/** Big-endian reads with a small cursor over a [RandomReader]. */
private class Reader(private val source: RandomReader, val length: Long) {
    private val scratch = ByteArray(16)

    fun bytes(position: Long, count: Int): ByteArray? {
        if (position < 0 || position + count > length) return null
        val out = if (count <= scratch.size) scratch else ByteArray(count)
        var read = 0
        while (read < count) {
            val n = source.read(position + read, out, read, count - read)
            if (n <= 0) return null
            read += n
        }
        return out
    }

    fun u32(position: Long): Long? = bytes(position, 4)?.let {
        ((it[0].toLong() and 0xFF) shl 24) or ((it[1].toLong() and 0xFF) shl 16) or ((it[2].toLong() and 0xFF) shl 8) or (it[3].toLong() and 0xFF)
    }

    fun u64(position: Long): Long? {
        val hi = u32(position) ?: return null
        val lo = u32(position + 4) ?: return null
        return (hi shl 32) or lo
    }

    fun u8(position: Long): Int? = bytes(position, 1)?.let { it[0].toInt() and 0xFF }
}

/**
 * Builds a seek index for fragmented MP4 files that have no `sidx` box (which players need to
 * seek them): the offsets of the `moof` boxes and the decode times their fragments start at.
 */
object FragmentedMp4Indexer {

    private fun fourCc(s: String): Long = s.fold(0L) { acc, c -> (acc shl 8) or c.code.toLong() }
    private val MOOV = fourCc("moov")
    private val MOOF = fourCc("moof")
    private val TRAK = fourCc("trak")
    private val TKHD = fourCc("tkhd")
    private val MDIA = fourCc("mdia")
    private val MDHD = fourCc("mdhd")
    private val HDLR = fourCc("hdlr")
    private val MVHD = fourCc("mvhd")
    private val MVEX = fourCc("mvex")
    private val MEHD = fourCc("mehd")
    private val TRAF = fourCc("traf")
    private val TFHD = fourCc("tfhd")
    private val TFDT = fourCc("tfdt")
    private val VIDE = fourCc("vide")

    private class Box(val type: Long, val start: Long, val headerSize: Int, val end: Long)

    private fun box(r: Reader, position: Long, limit: Long): Box? {
        val size32 = r.u32(position) ?: return null
        val type = r.u32(position + 4) ?: return null
        var header = 8
        val size = when (size32) {
            1L -> { header = 16; r.u64(position + 8) ?: return null }
            0L -> limit - position
            else -> size32
        }
        if (size < header || position + size > limit) return null
        return Box(type, position, header, position + size)
    }

    private fun children(r: Reader, parent: Box, block: (Box) -> Unit) {
        var p = parent.start + parent.headerSize
        while (p + 8 <= parent.end) {
            val b = box(r, p, parent.end) ?: return
            block(b)
            p = b.end
        }
    }

    fun build(source: RandomReader, fileLength: Long, maxFragments: Int = 100_000): SeekIndex? {
        val r = Reader(source, fileLength)
        val timescales = HashMap<Long, Long>()
        var videoTrack = -1L
        var movieTimescale = 0L
        var movieDurationUnits = 0L
        var fragmentDurationUnits = 0L
        val offsets = ArrayList<Long>()
        val times = ArrayList<Long>()
        var position = 0L
        var boxes = 0
        while (position + 8 <= fileLength && boxes++ < maxFragments * 3) {
            val top = box(r, position, fileLength) ?: break
            when (top.type) {
                MOOV -> children(r, top) { child ->
                    when (child.type) {
                        MVHD -> {
                            val version = r.u8(child.start + 8) ?: 0
                            if (version == 1) {
                                movieTimescale = r.u32(child.start + 8 + 20) ?: 0L
                                movieDurationUnits = r.u64(child.start + 8 + 24) ?: 0L
                            } else {
                                movieTimescale = r.u32(child.start + 8 + 12) ?: 0L
                                movieDurationUnits = r.u32(child.start + 8 + 16) ?: 0L
                            }
                        }
                        MVEX -> children(r, child) { m ->
                            if (m.type == MEHD) {
                                val version = r.u8(m.start + 8) ?: 0
                                fragmentDurationUnits = (if (version == 1) r.u64(m.start + 12) else r.u32(m.start + 12)) ?: 0L
                            }
                        }
                        TRAK -> {
                            var id = -1L
                            var scale = 0L
                            var video = false
                            children(r, child) { t ->
                                when (t.type) {
                                    TKHD -> {
                                        val version = r.u8(t.start + 8) ?: 0
                                        id = r.u32(t.start + 8 + if (version == 1) 20 else 12) ?: -1L
                                    }
                                    MDIA -> children(r, t) { m ->
                                        when (m.type) {
                                            MDHD -> {
                                                val version = r.u8(m.start + 8) ?: 0
                                                scale = r.u32(m.start + 8 + if (version == 1) 20 else 12) ?: 0L
                                            }
                                            HDLR -> video = r.u32(m.start + 8 + 8) == VIDE
                                        }
                                    }
                                }
                            }
                            if (id >= 0 && scale > 0) {
                                timescales[id] = scale
                                if (video && videoTrack < 0) videoTrack = id
                            }
                        }
                    }
                }
                MOOF -> {
                    var timeUs = -1L
                    var fallbackUs = -1L
                    children(r, top) { traf ->
                        if (traf.type != TRAF) return@children
                        var trackId = -1L
                        var decodeTime = -1L
                        children(r, traf) { t ->
                            when (t.type) {
                                TFHD -> trackId = r.u32(t.start + 12) ?: -1L
                                TFDT -> {
                                    val version = r.u8(t.start + 8) ?: 0
                                    decodeTime = (if (version == 1) r.u64(t.start + 12) else r.u32(t.start + 12)) ?: -1L
                                }
                            }
                        }
                        val scale = timescales[trackId] ?: return@children
                        if (decodeTime < 0) return@children
                        val us = decodeTime * 1_000_000L / scale
                        if (trackId == videoTrack) timeUs = us else if (fallbackUs < 0) fallbackUs = us
                    }
                    val t = if (timeUs >= 0) timeUs else fallbackUs
                    if (t >= 0 && (times.isEmpty() || t >= times.last())) {
                        offsets += top.start
                        times += t
                        if (offsets.size >= maxFragments) break
                    }
                }
            }
            position = top.end
        }
        if (offsets.size < 2) return null
        val durationUs = when {
            fragmentDurationUnits > 0 && movieTimescale > 0 -> fragmentDurationUnits * 1_000_000L / movieTimescale
            movieDurationUnits > 0 && movieTimescale > 0 -> movieDurationUnits * 1_000_000L / movieTimescale
            else -> times.last() + (times.last() - times.first()) / (times.size - 1)
        }.coerceAtLeast(times.last() + 1)
        return SeekIndex(offsets.toLongArray(), times.toLongArray(), durationUs, fileLength)
    }
}

/**
 * Builds a seek index for Matroska/WebM files without `Cues`: the positions of the `Cluster`
 * elements and their timecodes.
 */
object MatroskaIndexer {
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_DURATION = 0x4489L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMECODE = 0xE7L
    private const val UNKNOWN_SIZE = -1L

    /** Top-level children of a Segment. An unknown-size Cluster ends where one of these starts. */
    private val LEVEL1 = setOf(0x114D9B74L, ID_INFO, 0x1654AE6BL, 0x1C53BB6BL, ID_CLUSTER, 0x1941A469L, 0x1043A770L, 0x1254C367L)

    private class Element(val id: Long, val dataStart: Long, val size: Long)

    private fun vint(r: Reader, position: Long, keepMarker: Boolean): Pair<Long, Int>? {
        val first = r.u8(position) ?: return null
        if (first == 0) return null
        var length = 1
        var mask = 0x80
        while (first and mask == 0) { mask = mask shr 1; length++ }
        if (length > 8) return null
        var value = (if (keepMarker) first else first and (mask - 1)).toLong()
        var allOnes = (first and (mask - 1)) == mask - 1
        for (i in 1 until length) {
            val b = r.u8(position + i) ?: return null
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        if (!keepMarker && allOnes) return UNKNOWN_SIZE to length
        return value to length
    }

    private fun element(r: Reader, position: Long): Element? {
        val (id, idLength) = vint(r, position, keepMarker = true) ?: return null
        val (size, sizeLength) = vint(r, position + idLength, keepMarker = false) ?: return null
        return Element(id, position + idLength + sizeLength, size)
    }

    private fun uint(r: Reader, e: Element): Long? {
        if (e.size !in 1..8) return null
        var v = 0L
        for (i in 0 until e.size.toInt()) v = (v shl 8) or (r.u8(e.dataStart + i) ?: return null).toLong()
        return v
    }

    private fun float(r: Reader, e: Element): Double? = when (e.size) {
        4L -> uint(r, e)?.let { java.lang.Float.intBitsToFloat(it.toInt()).toDouble() }
        8L -> uint(r, e)?.let { java.lang.Double.longBitsToDouble(it) }
        else -> null
    }

    fun build(source: RandomReader, fileLength: Long, maxClusters: Int = 100_000): SeekIndex? {
        val r = Reader(source, fileLength)
        // EBML header, then the Segment.
        val header = element(r, 0) ?: return null
        if (header.id != 0x1A45DFA3L) return null
        val segment = element(r, header.dataStart + header.size) ?: return null
        if (segment.id != ID_SEGMENT) return null
        val segmentEnd = if (segment.size == UNKNOWN_SIZE) fileLength else minOf(fileLength, segment.dataStart + segment.size)
        var timecodeScaleNs = 1_000_000L
        var durationTicks = -1.0
        val offsets = ArrayList<Long>()
        val times = ArrayList<Long>()
        var p = segment.dataStart
        var guard = 0
        while (p < segmentEnd && guard++ < maxClusters * 4) {
            val e = element(r, p) ?: break
            when (e.id) {
                ID_INFO -> {
                    var c = e.dataStart
                    val end = e.dataStart + e.size
                    while (c < end) {
                        val child = element(r, c) ?: break
                        if (child.size == UNKNOWN_SIZE) break
                        when (child.id) {
                            ID_TIMECODE_SCALE -> uint(r, child)?.let { if (it > 0) timecodeScaleNs = it }
                            ID_DURATION -> float(r, child)?.let { durationTicks = it }
                        }
                        c = child.dataStart + child.size
                    }
                }
                ID_CLUSTER -> {
                    // The Timecode is normally the first child (a CRC-32 or Position may precede it).
                    var first = element(r, e.dataStart)
                    var hops = 0
                    while (first != null && first.id != ID_CLUSTER_TIMECODE && first.size != UNKNOWN_SIZE && hops++ < 3) {
                        first = element(r, first.dataStart + first.size)
                    }
                    if (first != null && first.id == ID_CLUSTER_TIMECODE) {
                        uint(r, first)?.let { ticks ->
                            val us = ticks * timecodeScaleNs / 1000L
                            if (times.isEmpty() || us >= times.last()) {
                                offsets += p
                                times += us
                            }
                        }
                    }
                    if (offsets.size >= maxClusters) break
                }
            }
            p = if (e.size == UNKNOWN_SIZE) {
                if (e.id != ID_CLUSTER) break
                endOfUnknownCluster(r, e.dataStart, segmentEnd) ?: break
            } else {
                e.dataStart + e.size
            }
        }
        if (offsets.size < 2) return null
        val durationUs = if (durationTicks > 0) (durationTicks * timecodeScaleNs / 1000.0).toLong()
        else times.last() + (times.last() - times.first()) / (times.size - 1)
        return SeekIndex(offsets.toLongArray(), times.toLongArray(), durationUs.coerceAtLeast(times.last() + 1), fileLength)
    }

    /** Walks the children of an unknown-size Cluster to the next top-level element. */
    private fun endOfUnknownCluster(r: Reader, start: Long, limit: Long): Long? {
        var c = start
        var guard = 0
        while (c < limit && guard++ < 1_000_000) {
            val child = element(r, c) ?: return null
            if (child.id in LEVEL1) return c
            if (child.size == UNKNOWN_SIZE) return null
            c = child.dataStart + child.size
        }
        return limit
    }
}
