package de.schranner.tastatur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

/** Simuliert ungenaues Tippen und misst, wie viele Fehlgriffe die Treffhilfe verhindert. */
class TouchModelTest {

    private val px = 106.7f
    private val py = 105f
    private val targets = ArrayList<TouchModel.Target>().apply {
        "qwertzuiop".forEachIndexed { i, c -> add(TouchModel.Target(c, (i + 0.5f) * px, 0.5f * py)) }
        "asdfghjkl".forEachIndexed { i, c -> add(TouchModel.Target(c, (i + 1f) * px, 1.5f * py)) }
        "yxcvbnm".forEachIndexed { i, c -> add(TouchModel.Target(c, (i + 2f) * px, 2.5f * py)) }
    }
    private val keys = targets.map { it.ch }.toSet()

    private val engine = SuggestionEngine(
        { File("src/main/assets/words.tsv").inputStream() },
        Files.createTempDirectory("t").toFile(),
        { File("src/main/assets/ngrams.tsv").inputStream() },
        { File("src/main/assets/ngrams_en.tsv").inputStream() },
    ).also { it.loadBlocking() }

    @Test fun centerAlwaysHitsItsKey() {
        // Selbst wenn alle Nachbarn maximal wahrscheinlich sind und die Taste selbst gar nicht
        val rnd = Random(1)
        for (t in targets) {
            val w = keys.associateWith { if (it == t.ch) 0f else 1f }
            repeat(20) {
                val x = t.cx + (rnd.nextFloat() - 0.5f) * 2 * 0.14f * px
                val y = t.cy + (rnd.nextFloat() - 0.5f) * 2 * 0.14f * py
                assertEquals(t.ch, TouchModel.pick(x, y, targets, px, py, w, 1f)!!.ch)
            }
        }
    }

    @Test fun assistReducesMisses() {
        val token = Regex("[\\p{L}]+")
        val sentences = File("src/test/resources/heldout_de.txt").readLines().take(500)
        val strengths = floatArrayOf(0f, 0.5f, 1f)
        val errors = IntArray(strengths.size)
        var taps = 0
        val rnd = Random(7)
        val sigma = 0.25f     // Streuung des Fingers in Tastenbreiten
        for (line in sentences) {
            val words = token.findAll(line).map { it.value }.toList()
            for ((i, word) in words.withIndex()) {
                val prev = words.getOrNull(i - 1)
                val prev2 = if (i >= 2) words[i - 2] else if (i == 1) SuggestionEngine.START else null
                engine.setRecentWords(words.subList(maxOf(0, i - 6), i))
                for (k in word.indices) {
                    val want = word[k].lowercaseChar().let { if (it in keys) it else SuggestionEngine.baseChar(it) }
                    val target = targets.firstOrNull { it.ch == want } ?: continue
                    val w = TouchModel.weights(engine.nextLetters(word.substring(0, k), prev, prev2, i == 0), keys)
                    val x = target.cx + rnd.nextGaussian().toFloat() * sigma * px
                    val y = target.cy + rnd.nextGaussian().toFloat() * sigma * py
                    taps++
                    for ((si, s) in strengths.withIndex()) {
                        if (TouchModel.pick(x, y, targets, px, py, w, s)?.ch != want) errors[si]++
                    }
                }
            }
        }
        fun pct(e: Int) = String.format("%.1f %%", 100.0 * e / taps)
        println("Treffhilfe: $taps simulierte Tastendrücke (Streuung ${sigma} Tastenbreiten)")
        println("  falsche Taste ohne Treffhilfe:  ${pct(errors[0])}")
        println("  mit Stärke 50 %:                ${pct(errors[1])}")
        println("  mit Stärke 100 %:               ${pct(errors[2])}")
        assertTrue(errors[1] < errors[0] * 0.8)
        assertTrue(errors[2] < errors[1])
    }
}
