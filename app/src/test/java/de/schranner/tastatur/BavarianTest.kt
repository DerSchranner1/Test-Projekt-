package de.schranner.tastatur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Bairisch: wird verstanden, ohne Hochdeutsch und Englisch zu verschlechtern. */
class BavarianTest {

    private fun engine(dialect: Boolean) = SuggestionEngine(
        { File("src/main/assets/words.tsv").inputStream() },
        Files.createTempDirectory("b").toFile(),
        { File("src/main/assets/ngrams.tsv").inputStream() },
        { File("src/main/assets/ngrams_en.tsv").inputStream() },
        { name -> File("src/main/assets/$name").inputStream() },
        dialect,
    ).also { it.loadBlocking() }

    private val bar by lazy { engine(true) }
    private val plain by lazy { engine(false) }

    @Test fun bavarianWordsAreKnown() {
        val words = listOf(
            "i", "hob", "des", "ned", "gsogt", "dahoam", "Brezn", "griaß", "pfiat", "oida", "scho", "woaß",
            "gscheid", "freili", "gfreit", "kimmst", "gemma", "Schmarrn", "Spezl", "zefix", "moang", "boid",
        )
        val unknownBefore = words.filter { !plain.isKnown(it) }
        println("Ohne Bairisch unbekannt: $unknownBefore")
        for (w in words) assertTrue("$w sollte bekannt sein", bar.isKnown(w))
        assertTrue(unknownBefore.size >= 10)
        // Echte Tippfehler bleiben Fehler
        for (typo in listOf("fehelr", "vieleicht", "wiederrum", "tschüs s".replace(" ", "x"))) {
            assertFalse("$typo ist ein Fehler", bar.isKnown(typo))
        }
    }

    @Test fun bavarianPredictions() {
        fun next(e: SuggestionEngine, vararg ctx: String): List<String> {
            e.setRecentWords(ctx.toList())
            val prev = ctx.lastOrNull()
            val prev2 = if (ctx.size >= 2) ctx[ctx.size - 2] else if (ctx.size == 1) SuggestionEngine.START else null
            return e.predictNext(prev, ctx.isEmpty(), prev2)
        }
        val cases = listOf(
            listOf("griaß") to "di",
            listOf("pfiat") to "di",
            listOf("des") to "is",
            listOf("i") to "hob",
            listOf("passt") to "scho",
            listOf("ja") to "mei",
            listOf("mia") to "san",
            listOf("i", "woas") to "ned",
        )
        var hits = 0
        for ((ctx, want) in cases) {
            val got = next(bar, *ctx.toTypedArray())
            println("Bairisch: ${ctx.joinToString(" ")} -> $got (ohne: ${next(plain, *ctx.toTypedArray())})")
            if (got.any { it.equals(want, true) }) hits++
        }
        assertTrue("nur $hits von ${cases.size}", hits >= cases.size - 1)
        // Beim Tippen: „gs“ -> „gsogt“ nach „hob i“
        val s = bar.suggest("gso", "i", "hob", false)
        println("hob i gso… -> $s")
        assertTrue(s.any { it.startsWith("gso") })
    }

    @Test fun touchAssistKnowsBavarian() {
        // Nach „i h“ ist bairisch „hob“ wahrscheinlich: das o bekommt mehr Gewicht als ohne Bairisch
        val withBar = bar.nextLetters("h", "i", SuggestionEngine.START, false)
        val without = plain.nextLetters("h", "i", SuggestionEngine.START, false)
        println("Treffhilfe nach „i h“: o=${withBar['o']} (ohne Bairisch ${without['o']}), a=${withBar['a']}")
        assertTrue((withBar['o'] ?: 0f) > (without['o'] ?: 0f))
        // Nach „ne“ soll „ned“ möglich sein: d bekommt Gewicht
        val ne = bar.nextLetters("ne", "des", "is", false)
        println("Treffhilfe nach „des is ne“: d=${ne['d']} t=${ne['t']}")
        assertTrue((ne['d'] ?: 0f) > 0f)
    }

    @Test fun germanAndEnglishStayGood() {
        // Hochdeutsch: Vorhersage darf durch Bairisch kaum schlechter werden
        val data = File("src/test/resources/heldout_de.txt").readLines().take(400)
            .map { l -> Regex("\\p{L}+").findAll(l).map { it.value }.toList() }.filter { it.size > 2 }
        fun score(e: SuggestionEngine): Double {
            var n = 0; var hit = 0
            for (s in data) for (i in 1 until s.size) {
                e.setRecentWords(s.subList(maxOf(0, i - 6), i))
                val p = e.predictNext(s[i - 1], false, if (i > 1) s[i - 2] else SuggestionEngine.START)
                n++; if (p.any { it.equals(s[i], true) }) hit++
            }
            return 100.0 * hit / n
        }
        val a = score(plain)
        val b = score(bar)
        println(String.format("Hochdeutsch nächstes Wort: ohne Bairisch %.1f %%, mit %.1f %%", a, b))
        assertTrue(b > a - 2.0)
        // Englisch wird weiterhin erkannt
        bar.setRecentWords(listOf("how", "are", "you", "doing", "today"))
        assertTrue(bar.englishShare() > 0.5f)
        bar.setRecentWords(listOf("i", "hob", "des", "ned", "gwusst"))
        println("Anteil Englisch bei „i hob des ned gwusst“: ${bar.englishShare()}")
        assertTrue(bar.englishShare() < 0.5f)
        // Hochdeutsche Schreibweisen bleiben
        assertEquals("Ich", bar.predictNext(null, true).first())
    }
}
