package com.hapticsync.platform.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Extractors that can seek in more files than the defaults.
 *
 * ExoPlayer treats a file as unseekable when seeking would need an index the container does
 * not carry. Seeking then silently restarts playback from the beginning. This factory:
 * - enables constant-bitrate seeking (MPEG-TS, MP3, ADTS AAC, AMR without an index), and
 * - builds the missing index for fragmented MP4 without `sidx` and Matroska/WebM without `Cues`
 *   by scanning the file's fragment/cluster headers once (a few milliseconds per minute of
 *   media), and hands the player a real seek map.
 */
@OptIn(UnstableApi::class)
class SeekIndexingExtractorsFactory(private val context: Context) : ExtractorsFactory {

    private val delegate = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)

    override fun createExtractors(): Array<Extractor> = delegate.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { wrap(it, uri) }.toTypedArray()

    private fun wrap(extractor: Extractor, uri: Uri): Extractor = when (extractor.underlyingImplementation) {
        is FragmentedMp4Extractor -> IndexingExtractor(extractor) { index(uri, FragmentedMp4Indexer::build) }
        is MatroskaExtractor -> IndexingExtractor(extractor) { index(uri, MatroskaIndexer::build) }
        else -> extractor
    }

    /** Scans [uri] with [builder]; null when the file cannot be read randomly (e.g. a stream). */
    private fun index(uri: Uri, builder: (RandomReader, Long) -> SeekIndex?): SeekIndex? {
        val descriptor = when (uri.scheme) {
            ContentResolver.SCHEME_FILE, null -> null
            else -> context.contentResolver.openFileDescriptor(uri, "r")
        }
        val stream = descriptor?.let { FileInputStream(it.fileDescriptor) }
            ?: uri.path?.let { path -> File(path).takeIf { it.isFile }?.let { FileInputStream(it) } }
            ?: return null
        return try {
            val channel = stream.channel
            val length = channel.size()
            if (length <= 0) return null
            builder(RandomReader { position, buffer, offset, count -> channel.read(ByteBuffer.wrap(buffer, offset, count), position) }, length)
        } finally {
            runCatching { stream.close() }
            runCatching { descriptor?.close() }
        }
    }
}

/** Replaces an unseekable seek map with one built from a scanned [SeekIndex]. */
@OptIn(UnstableApi::class)
private class IndexingExtractor(
    private val delegate: Extractor,
    private val index: () -> SeekIndex?,
) : Extractor {

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput by output {
            override fun seekMap(seekMap: SeekMap) {
                if (seekMap.isSeekable) {
                    output.seekMap(seekMap)
                    return
                }
                val built = runCatching { index() }.getOrNull()
                output.seekMap(built?.toSeekMap() ?: seekMap)
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

@OptIn(UnstableApi::class)
internal fun SeekIndex.toSeekMap(): SeekMap {
    val n = size
    val sizes = IntArray(n) { i ->
        val next = if (i + 1 < n) offsets[i + 1] else fileLength
        (next - offsets[i]).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }
    val durations = LongArray(n) { i ->
        val next = if (i + 1 < n) timesUs[i + 1] else durationUs
        (next - timesUs[i]).coerceAtLeast(1L)
    }
    return ChunkIndex(sizes, offsets, durations, timesUs)
}
