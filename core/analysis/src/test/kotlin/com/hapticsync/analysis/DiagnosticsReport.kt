package com.hapticsync.analysis

import com.hapticsync.dsp.synth.DemoClips
import com.hapticsync.dsp.synth.DemoScenario
import com.hapticsync.model.SoundCategory
import org.junit.Test
import java.io.File

/**
 * Writes a human-readable report of what the analyser detects in every demo scene to
 * `build/reports/analysis-diagnostics.txt`. Used for tuning; it asserts nothing.
 */
class DiagnosticsReport {

    @Test
    fun writeReport() {
        val out = StringBuilder()
        for (scenario in DemoScenario.entries) {
            val clip = DemoClips.render(scenario)
            val result = ClipRunner.run(clip)
            out.appendLine("=== ${scenario.name} (${"%.1f".format(clip.durationSec)} s)")
            out.appendLine("truth: " + clip.truth.joinToString { "${it.label}@${"%.2f".format(it.startSec)}" + if (it.endSec > it.startSec) "-${"%.1f".format(it.endSec)}" else "" })
            out.appendLine("events (${result.events.size}): " + result.events.joinToString { "${it.category.name}@${"%.3f".format(it.timeUs / 1e6)}(${"%.2f".format(it.strength)})" })
            val detailed = System.getProperty("diag.detail", "").split(",").contains(scenario.name)
            for (event in result.events.take(if (detailed) 60 else 0)) {
                val sc = result.scores[event] ?: continue
                val top = SoundCategory.entries.sortedByDescending { sc[it.ordinal] }.take(3)
                    .joinToString { "${it.name}=${"%.2f".format(sc[it.ordinal])}" }
                out.appendLine("   ${event.category.name}@${"%.3f".format(event.timeUs / 1e6)} attack=${"%.0f".format(event.attackMs)} decay=${"%.0f".format(event.decayDbPerSecond)} band=${event.dominantBand} top: $top")
            }
            val continuous = SoundCategory.entries.filter { it.kind != com.hapticsync.model.SoundKind.TRANSIENT }
            val sb = StringBuilder("continuous means: ")
            for (c in continuous) {
                val m = result.meanLevel(c, 0.0, clip.durationSec)
                if (m > 0.02f) sb.append("${c.name}=${"%.2f".format(m)} ")
            }
            out.appendLine(sb)
            // 1-second timeline of the dominant continuous level and engine metrics.
            val timeline = StringBuilder("timeline: ")
            var t = 0.0
            while (t < clip.durationSec) {
                val e = result.meanOf(t, t + 1) { it.engine }
                val sp = result.meanOf(t, t + 1) { it.speech }
                val rpm = result.meanOf(t, t + 1) { it.rpm }
                val acc = result.meanLevel(SoundCategory.ACCELERATION, t, t + 1)
                val rev = result.meanLevel(SoundCategory.ENGINE_REV, t, t + 1)
                val rh = result.meanOf(t, t + 1) { it.rhythm }
                timeline.append("[${t.toInt()}s e=${"%.2f".format(e)} rpm=${"%.2f".format(rpm)} acc=${"%.2f".format(acc)} rev=${"%.2f".format(rev)} sp=${"%.2f".format(sp)} rh=${"%.2f".format(rh)}] ")
                t += 1.0
            }
            out.appendLine(timeline)
            out.appendLine()
        }
        val file = File("build/reports/analysis-diagnostics.txt")
        file.parentFile.mkdirs()
        file.writeText(out.toString())
    }
}
