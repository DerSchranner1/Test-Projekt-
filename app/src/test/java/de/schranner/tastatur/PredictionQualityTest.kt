package de.schranner.tastatur

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Misst die Vorhersage-Qualität an Sätzen, die NICHT in den Trainingsdaten waren
 * (src/test/resources/heldout_de.txt): Wie oft steht das richtige Wort unter den 3 Vorschlägen?
 */
class PredictionQualityTest {

    private fun engine(withNgrams: Boolean) = SuggestionEngine(
        { File("src/main/assets/words.tsv").inputStream() },
        Files.createTempDirectory("q").toFile(),
        if (withNgrams) ({ File("src/main/assets/ngrams.tsv").inputStream() }) else null,
        if (withNgrams) ({ File("src/main/assets/ngrams_en.tsv").inputStream() }) else null,
    ).also { it.loadBlocking() }

    private val token = Regex("[\\p{L}]+(?:['’-][\\p{L}]+)*|[.!?…]+|\\d+")

    /** Sätze -> Listen von Wörtern (Satzzeichen trennen, Zahlen brechen ab). */
    private fun sentences(file: String = "heldout_de.txt"): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (line in File("src/test/resources/$file").readLines()) {
            var cur = ArrayList<String>()
            for (m in token.findAll(line)) {
                val t = m.value
                if (!t[0].isLetter()) { if (cur.size > 1) out += cur; cur = ArrayList() } else cur += t
            }
            if (cur.size > 1) out += cur
        }
        return out
    }

    private class Result(val next: Double, val after1: Double, val after2: Double, val saved: Double)

    private fun measure(e: SuggestionEngine, data: List<List<String>>): Result {
        var n = 0; var hitNext = 0
        var n1 = 0; var hit1 = 0; var hit2 = 0
        var keys = 0; var needed = 0
        for ((si, s) in data.withIndex()) {
            for (i in s.indices) {
                val w = s[i]
                val prev = if (i > 0) s[i - 1] else null
                val prev2 = if (i > 1) s[i - 2] else if (i == 1) SuggestionEngine.START else null
                // Wie in der Tastatur: die letzten Wörter des Texts bestimmen die Sprache.
                // Am Satzanfang zählen hier die Wörter des vorherigen Satzes (gleiche Sprache).
                e.setRecentWords(if (i > 0) s.subList(maxOf(0, i - 6), i) else data[maxOf(0, si - 1)].takeLast(4))
                val next = e.predictNext(prev, i == 0, prev2)
                n++
                val nextHit = next.any { it.equals(w, true) }
                if (nextHit) hitNext++
                if (si % 4 != 0 || w.length < 2) continue   // Tipp-Simulation nur auf einem Teil (Laufzeit)
                n1++
                var typedNeeded = w.length
                if (nextHit) typedNeeded = 0
                for (k in 1 until w.length) {
                    val hit = e.suggest(w.substring(0, k), prev, prev2, i == 0).take(3).any { it.equals(w, true) }
                    if (k == 1 && hit) hit1++
                    if (k == 2 && (hit || w.length == 2)) hit2++
                    if (hit && typedNeeded > k) typedNeeded = k
                    if (k >= 2 && typedNeeded <= k) break
                    if (k >= 4) break
                }
                if (w.length == 2 && typedNeeded == 2) hit2++
                keys += w.length
                needed += minOf(w.length, typedNeeded + if (typedNeeded < w.length) 1 else 0)
            }
        }
        return Result(100.0 * hitNext / n, 100.0 * hit1 / n1, 100.0 * hit2 / n1, 100.0 * (keys - needed) / keys)
    }

    @Test fun ngramsImprovePredictions() {
        val data = sentences()
        val old = measure(engine(false), data)
        val new = measure(engine(true), data)
        fun f(d: Double) = String.format("%.1f %%", d)
        println("Sätze: ${data.size}")
        println("Richtiges Wort unter 3 Vorschlägen      vorher   jetzt")
        println("  ohne einen Buchstaben zu tippen:      ${f(old.next)}   ${f(new.next)}")
        println("  nach 1 Buchstaben:                    ${f(old.after1)}   ${f(new.after1)}")
        println("  nach 2 Buchstaben:                    ${f(old.after2)}   ${f(new.after2)}")
        println("  eingesparte Tastendrücke:             ${f(old.saved)}   ${f(new.saved)}")
        assertTrue(new.next > old.next + 10)
        assertTrue(new.after1 > old.after1)
        assertTrue(new.saved > old.saved)
    }

    @Test fun englishWorksToo() {
        val data = sentences("heldout_en.txt").map { s -> s.map { it.replace('’', '\'') } }
        val old = measure(engine(false), data)
        val new = measure(engine(true), data)
        fun f(d: Double) = String.format("%.1f %%", d)
        println("Englisch – Sätze: ${data.size}")
        println("Richtiges Wort unter 3 Vorschlägen      vorher   jetzt")
        println("  ohne einen Buchstaben zu tippen:      ${f(old.next)}   ${f(new.next)}")
        println("  nach 1 Buchstaben:                    ${f(old.after1)}   ${f(new.after1)}")
        println("  nach 2 Buchstaben:                    ${f(old.after2)}   ${f(new.after2)}")
        println("  eingesparte Tastendrücke:             ${f(old.saved)}   ${f(new.saved)}")
        assertTrue(new.next > 15)
        assertTrue(new.saved > old.saved)
    }

    @Test fun languageIsDetected() {
        val e = engine(true)
        e.setRecentWords(listOf("ich", "habe", "heute"))
        assertTrue(e.englishShare() < 0.05f)
        e.setRecentWords(listOf("thank", "you", "for", "the"))
        assertTrue(e.englishShare() > 0.9f)
        println("EN: how are -> " + e.predictNext("are", false, "how"))
        println("EN: thank -> " + e.predictNext("thank", false, SuggestionEngine.START))
        println("EN: I | th -> " + e.suggest("th", "I", SuggestionEngine.START))
        println("EN: a nice ar -> " + e.suggest("ar", "nice", "a"))
        assertTrue(e.predictNext("are", false, "how").first() == "you")
        e.setRecentWords(emptyList())
        println("Start: 'th' -> " + e.suggest("th", null, null, true) + "   'i' -> " + e.suggest("i", null, null, true))
        e.setRecentWords(listOf("das", "ist"))
        println("DE: das ist -> " + e.predictNext("ist", false, "das") + "   'ar' -> " + e.suggest("ar", "ist", "das"))
    }

    @Test fun examples() {
        val e = engine(true)
        fun show(prev: String?, prev2: String?, start: Boolean = false) =
            println("${prev2 ?: ""} ${prev ?: "<Anfang>"} -> ${e.predictNext(prev, start, prev2)}")
        show(null, null, true)
        show("Wie", SuggestionEngine.START); show("geht", "wie"); show("es", "geht"); show("Vielen", SuggestionEngine.START)
        show("Guten", SuggestionEngine.START); show("habe", "ich"); show("bin", "ich"); show("freue", "ich"); show("mich", "freue")
        show("Bis", SuggestionEngine.START); show("Liebe", SuggestionEngine.START); show("zum", "glückwunsch")
        println("ge | nach 'wie' -> " + e.suggest("ge", "wie", SuggestionEngine.START))
        println("m | nach 'guten' -> " + e.suggest("m", "Guten", SuggestionEngine.START))
        println("d | nach 'vielen' -> " + e.suggest("d", "vielen", SuggestionEngine.START))
        assertTrue(e.predictNext("geht", false, "wie").first().equals("es", true))
        assertTrue(e.predictNext("vielen", false, SuggestionEngine.START).first().equals("Dank", true))
    }
}
