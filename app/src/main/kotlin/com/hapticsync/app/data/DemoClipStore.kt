package com.hapticsync.app.data

import android.content.Context
import android.net.Uri
import com.hapticsync.dsp.synth.DemoClips
import com.hapticsync.dsp.synth.DemoScenario
import com.hapticsync.dsp.synth.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders the built-in demo scenes to WAV files in the app cache on first use. The scenes are
 * synthesised procedurally (no bundled media), then played through the normal player pipeline:
 * the haptics come from analysing that audio, exactly as for a user's own video.
 */
class DemoClipStore(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "demo")

    fun isRendered(scenario: DemoScenario): Boolean = file(scenario).exists()

    suspend fun uri(scenario: DemoScenario): Uri = withContext(Dispatchers.Default) {
        val target = file(scenario)
        if (!target.exists()) {
            directory.mkdirs()
            val clip = DemoClips.render(scenario, SAMPLE_RATE)
            val temp = File(directory, target.name + ".tmp")
            temp.outputStream().buffered().use { WavWriter.write16(it, clip.interleaved(), SAMPLE_RATE, 2) }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        Uri.fromFile(target)
    }

    private fun file(scenario: DemoScenario) = File(directory, "${scenario.name.lowercase()}_v$VERSION.wav")

    private companion object {
        const val SAMPLE_RATE = 44_100
        /** Bump when the synthesis changes so cached renders are refreshed. */
        const val VERSION = 1
    }
}
