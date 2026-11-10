package de.schranner.tastatur

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class GestureDecoderTest {

    private val keyW = 100f
    private val centers: Map<Char, GestureDecoder.Pt> = HashMap<Char, GestureDecoder.Pt>().apply {
        "qwertzuiopü".forEachIndexed { i, c -> put(c, GestureDecoder.Pt((i + 0.5f) * keyW, 50f)) }
        "asdfghjklöä".forEachIndexed { i, c -> put(c, GestureDecoder.Pt((i + 0.5f) * keyW, 150f)) }
        "yxcvbnmß".forEachIndexed { i, c -> put(c, GestureDecoder.Pt((i + 2f) * keyW, 250f)) }
    }

    private val engine = SuggestionEngine(
        { File("src/main/assets/words.tsv").inputStream() },
        Files.createTempDirectory("g").toFile(),
    ).also { it.loadBlocking() }
    private val decoder = GestureDecoder(engine)

    /** Wischspur wie von einem echten Finger: zittrig, Ecken leicht abgerundet. */
    private fun swipe(word: String, rnd: Random, noise: Float): List<GestureDecoder.Pt> {
        val keys = word.lowercase().map { centers[it]!! }
        val out = ArrayList<GestureDecoder.Pt>()
        fun jitter() = (rnd.nextFloat() - 0.5f) * 2 * noise
        val offX = jitter() * 0.8f
        val offY = jitter() * 0.8f
        for (k in 0 until keys.size - 1) {
            val a = keys[k]; val b = keys[k + 1]
            val steps = 12
            for (s in 0 until steps) {
                val t = s / steps.toFloat()
                out += GestureDecoder.Pt(a.x + (b.x - a.x) * t + offX + jitter() * 0.4f, a.y + (b.y - a.y) * t + offY + jitter() * 0.4f)
            }
        }
        out += GestureDecoder.Pt(keys.last().x + offX, keys.last().y + offY)
        return out
    }

    @Test fun decodesCommonWords() {
        val words = listOf(
            "hallo", "danke", "heute", "morgen", "haus", "gut", "wir", "ich", "machen", "straße",
            "bitte", "schön", "später", "wochenende", "kommst", "essen", "genau", "vielleicht",
            "arbeit", "zuhause", "liebe", "frage", "jetzt", "nicht", "habe", "schreiben", "tastatur", "fußball",
        )
        val rnd = Random(42)
        var top1 = 0
        var top3 = 0
        val misses = mutableListOf<String>()
        val t = System.nanoTime()
        for (w in words) {
            val res = decoder.decode(swipe(w, rnd, 35f), centers, keyW, null)
            if (res.firstOrNull() == w) top1++ else misses += "$w -> $res"
            if (res.take(3).contains(w)) top3++
        }
        val ms = (System.nanoTime() - t) / 1_000_000 / words.size
        println("Treffer Platz 1: $top1/${words.size}, Top 3: $top3/${words.size}, ~$ms ms pro Wort")
        misses.forEach { println("  verfehlt: $it") }
        assertTrue(top1 >= words.size * 0.75)
        assertTrue(top3 >= words.size * 0.9)
    }
}
